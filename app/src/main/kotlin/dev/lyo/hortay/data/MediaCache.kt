package dev.lyo.hortay.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Semantic priority class for [MediaCache.ensure]. Maps to TDLib's 1..32 download priority.
 * TDLib runs ~4 simultaneous downloads at once and serves higher priority first; reissuing
 * the same fileId with a higher priority promotes the in-flight job, so callers can safely
 * "upgrade" a queued avatar to foreground when the user taps into a fullscreen viewer.
 *
 * The numbers are deliberately spaced so even a burst of one priority can't fully starve the
 * one above: photo thumbs (16) never block the foreground viewer (32); avatar pyramids (2)
 * never block visible photo thumbs.
 */
enum class DownloadPriority(val tdValue: Int) {
    /** Active full-screen viewer / playing video. */
    Foreground(32),
    /** Photo / video thumb currently visible in the timeline. */
    VisibleMedia(16),
    /** Off-screen but next-up — speculative prefetch. */
    Prefetch(8),
    /** Avatar small (160×160). Always loses to media. */
    Avatar(2),
}

/**
 * App-scoped cache for TDLib file downloads.
 *
 * TDLib emits [TdApi.UpdateFile] whenever a file's local state changes (progress, completion,
 * deletion). We mirror those into [StateFlow]s keyed by `fileId` so that arbitrarily many UI
 * nodes can [observe] the same file without each owning its own download lifecycle. Cards
 * that recycle during scroll do not lose progress, and a download triggered from one screen
 * is reused by every other consumer.
 *
 * Robustness over the bare TDLib contract:
 *
 *   • **Stall watchdog.** TDLib's docs say the only signal of completion is a stream of
 *     [TdApi.UpdateFile]. In practice, a network glitch mid-chunk leaves the file with
 *     `is_downloading_active=true` and TDLib goes silent — see tdlib/td#2585. The naive
 *     "subscribe and wait" loop hangs forever. We periodically check active downloads and
 *     reissue [TdApi.DownloadFile] when no progress has been observed for [STALL_THRESHOLD_MS].
 *
 *   • **Debounced cancel.** A LazyColumn item disposed-and-remounted in the same frame races
 *     [cancelIfPendingAsync] against the next [ensure]. Both run on the IO dispatcher in
 *     undefined order, so a cancel can land *after* the new DownloadFile and leave the slot
 *     stuck in Downloading(0). Cancels are deferred [CANCEL_DEBOUNCE_MS]; a re-mount inside
 *     the window aborts the cancel.
 *
 * Thread-safe by design: state is held in [ConcurrentHashMap]s; mutations are confined to a
 * single update collector running on [ioDispatcher].
 */
class MediaCache(
    private val td: TdSender,
    private val scope: CoroutineScope,
    private val connection: StateFlow<ConnectionStatus>,
    private val foreground: StateFlow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val states = ConcurrentHashMap<Int, MutableStateFlow<MediaState>>()
    private val activePriority = ConcurrentHashMap<Int, Int>()

    // Per-file bookkeeping for the watchdog AND the StateFlow throttle. Merged into one
    // record so we don't keep two ConcurrentHashMaps in lockstep:
    //   • `bytes`/`changedAt`/`retries` drive the stall watchdog. `bytes` is the last
    //     observed `local.downloadedSize`; `changedAt` flips whenever it actually moves;
    //     `retries` counts how many DownloadFile reissues we've done since the last move.
    //   • `lastEmitMs` throttles Downloading→Downloading recomposition. TDLib reports
    //     progress at 30+ Hz per active file; the UI doesn't need that granularity, and
    //     unconditional emits cause real CPU + GC tax in album fullscreen. Throttle is
    //     bypassed for the 100% emit so the bar doesn't look frozen at 99%.
    private data class Track(
        val bytes: Long,
        val changedAt: Long,
        val retries: Int,
        val lastEmitMs: Long,
    )
    private val tracks = ConcurrentHashMap<Int, Track>()

    // In-flight cancel jobs awaiting their debounce window. ensure() pulls and cancels
    // these so a re-mounted item is not torn down by an earlier dispose.
    private val pendingCancels = ConcurrentHashMap<Int, Job>()

    // Per-fileId post-completion resync jobs. TDLib *sometimes* emits two UpdateFile
    // bursts at the tail of a download — first `isDownloadingCompleted=true, path=""`
    // (just-finished, before its internal rename to `photos/...jpg`), then the same
    // with the path filled in. The first event maps to Downloading(progress=1.0)
    // because [toMediaState] gates Ready on a non-empty path. If the second event is
    // dropped or never sent (rarer, but happens when the file was already in TDLib's
    // local store and the rename was a no-op), the slot would sit at "100% spinner"
    // until the user scrolled away and back — exactly the symptom we kept seeing.
    // We schedule a one-shot GetFile through this map: after a short delay, if the
    // slot is still Downloading at 1.0, we ask TDLib for the authoritative File and
    // route it through the reducer.
    private val postCompletionResync = ConcurrentHashMap<Int, Job>()

    // Single-coroutine reducer for all File mutations. Inbound `td.updates` events and
    // `ensureSlow`'s GetFile responses BOTH flow through this channel, so the state
    // machine has one writer instead of two-on-different-dispatchers (which silently
    // dropped the final Ready emit when a stale Downloading snapshot from one thread
    // landed after the Ready from the other). Capacity is unlimited — we'd rather pay
    // a bit of memory than risk dropping a terminal event under burst pressure.
    private val fileEvents = Channel<TdApi.File>(capacity = Channel.UNLIMITED)

    init {
        // Reducer. Single writer = no data race over `states[id].value`, `tracks[id]`,
        // or `activePriority`'s read-write pairs.
        scope.launch(ioDispatcher) {
            for (file in fileEvents) reduce(file)
        }

        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            // Updates for files we never observed are dropped at the reducer (see
            // [reduce]) — TDLib emits UpdateFile for everything in its database, not
            // just things this app rendered. Slots are created lazily by [observe]
            // / [ensure].
            .onEach { update -> fileEvents.send(update.file) }
            .launchIn(scope)

        // Battery-aware stall watchdog. Three regimes:
        //   • App backgrounded → suspend on `foreground.first { it }` (zero CPU; resumes
        //     when ProcessLifecycleOwner reports ON_START again). We don't need recovery
        //     while no one is looking at the screen, and TDLib resumes downloads itself
        //     when the app foregrounds.
        //   • Foreground but no active downloads → tick every IDLE_TICK_MS (5 s). The
        //     loop still wakes occasionally so a fresh ensure() that started just after
        //     the last empty-check is picked up promptly — at this cadence the wake-up
        //     cost is negligible while still feeling responsive on the very first stall.
        //   • Foreground + active downloads → tick every WATCHDOG_TICK_MS (2 s). At the
        //     STALL_THRESHOLD_MS (15 s) of silence the next tick reissues; worst-case
        //     recovery is ~17 s. Reissue is free for TDLib (treated as "kick the queue",
        //     not "restart"), and real progress resets the timer — so a slow-but-moving
        //     transfer never trips the watchdog.
        scope.launch(ioDispatcher) {
            while (isActive) {
                if (!foreground.value) {
                    foreground.filter { it }.first()
                }
                val cadence = if (tracks.isEmpty()) IDLE_TICK_MS else WATCHDOG_TICK_MS
                delay(cadence)
                if (tracks.isEmpty()) continue
                // Updating means "TDLib is catching up on missed updates" — downloads
                // still progress, so we keep watching. Only WaitingForNetwork is a hard
                // stop: reissuing during it just queues a request that goes nowhere.
                if (connection.value == ConnectionStatus.WaitingForNetwork) continue
                checkStalled()
            }
        }
    }

    /**
     * Reducer for an existing slot's TDLib [TdApi.File] event. Always invoked from the
     * single [fileEvents] consumer coroutine, so writes to `states[].value`, [tracks],
     * and [activePriority] don't race with each other.
     *
     * Slots are created only by [observe] / [ensure] when a Composable actually mounts
     * — TDLib emits [TdApi.UpdateFile] for every file in its database, including ones
     * the UI never rendered, and creating a [MutableStateFlow] per such id leaks in
     * proportion to TDLib's file table over a long session. The race where an inbound
     * [TdApi.UpdateFile] arrives before the first observe is closed by [ensureSlow]:
     * after creating the slot it dispatches the [TdApi.GetFile] result through the same
     * channel, so the reducer sees the up-to-date file and seeds the slot.
     *
     * Three real-world quirks force the extra logic over a naive `slot.value = next`:
     *
     *   1. **Rename race.** When a download finishes, TDLib briefly emits a stale
     *      `state=Downloading, path='temp/<id>'` event right after the completion event,
     *      during the internal rename to `photos/...jpg`. A naive reducer would flash
     *      the photo and yank it back to a spinner. Once we see Ready, we refuse to
     *      slide back as long as TDLib still reports *some* on-disk presence (path or
     *      bytes).
     *
     *   2. **Cache eviction.** TDLib's storage optimiser deletes previously-completed
     *      files; the maintainer's official guidance (#3178) is that "the local file
     *      path can become invalid in many ways. The app is supposed to call DownloadFile
     *      before using the file." When eviction wipes both `path` and `downloadedSize`,
     *      we accept the demotion so the watchdog (and any re-mount) can reissue
     *      DownloadFile and the UI ends up on a real on-disk file.
     *
     *   3. **Permanent failures with no Failed event.** If `canBeDownloaded=false` and
     *      the file isn't downloading or completed, TDLib won't fire any further
     *      update — it just stops. Without surfacing Failed here the UI sat on a 0%
     *      spinner forever for expired stickers / restricted media. Handled in
     *      [toMediaState].
     */
    private fun reduce(file: TdApi.File) {
        val s = states[file.id] ?: return
        val incoming = file.toMediaState()
        val previous = s.value
        val merged = when {
            // New Ready always wins — TDLib may have rotated the file's on-disk path
            // (cache layout) and we must surface the new one.
            incoming is MediaState.Ready -> incoming
            // Don't slide back from Ready while TDLib still reports the file on disk:
            // the rename-race event still has `path='temp/<id>'` and non-zero bytes.
            // A genuine eviction wipes both — and only then do we let the demotion
            // through so the watchdog can recover the slot.
            previous is MediaState.Ready
                && (file.local.path.orEmpty().isNotEmpty() || file.local.downloadedSize > 0L) -> previous
            else -> incoming
        }

        val now = System.currentTimeMillis()
        if (merged is MediaState.Downloading) {
            val bytes = file.local.downloadedSize
            val prev = tracks[file.id]
            // Real progress resets the retry counter — a download that survives one
            // watchdog reissue and then stalls again later still gets the full budget.
            val movedBytes = prev == null || prev.bytes != bytes
            val base = when {
                prev == null -> Track(bytes, now, retries = 0, lastEmitMs = 0L)
                movedBytes -> prev.copy(bytes = bytes, changedAt = now, retries = 0)
                else -> prev
            }
            // Throttle Downloading→Downloading recomposition to PROGRESS_MIN_INTERVAL_MS.
            // The 100% emit and any non-Downloading transition are exempt — they're
            // either terminal or the last chance to show "complete, awaiting rename"
            // before Ready arrives.
            val emitting = previous !is MediaState.Downloading
                || merged.progress >= 1f
                || now - base.lastEmitMs >= PROGRESS_MIN_INTERVAL_MS
            tracks[file.id] = if (emitting) base.copy(lastEmitMs = now) else base
            if (!emitting) return
        } else {
            tracks.remove(file.id)
        }

        s.value = merged

        // Schedule a post-completion resync any time TDLib reports the download as
        // finished but we still don't have a usable path. Two signals fold into this:
        //
        //   (a) `isDownloadingCompleted=true` with `path=""` — TDLib's authoritative
        //       "done, awaiting rename" snapshot. We can't trust progress for this:
        //       `expectedSize` is a heuristic that's often *larger* than the real
        //       final byte count, so `downloadedSize/expectedSize` lands at e.g. 0.94
        //       and we'd otherwise miss this branch entirely.
        //   (b) `progress >= 1f` — the legacy gate, still useful for the symmetric
        //       case where `expectedSize == size` and TDLib announces completion via
        //       progress hitting 1.0 first (rather than the explicit flag).
        //
        // Both routes converge on the same single-shot resync. Without (a), the
        // "100% spinner sticks until I scroll away" symptom that Codex traced was
        // unreachable from the resync path — the user only recovered via the
        // unmount/cancel cycle's GetFile.
        val tdlibCompleted = file.local.isDownloadingCompleted && file.local.path.orEmpty().isEmpty()
        if (merged is MediaState.Downloading && (merged.progress >= 1f || tdlibCompleted)) {
            schedulePostCompletionResync(file.id)
        } else if (merged is MediaState.Ready || merged is MediaState.Failed) {
            postCompletionResync.remove(file.id)?.cancel()
        }

        if (merged is MediaState.Ready || merged is MediaState.Failed) {
            activePriority.remove(file.id)
            tracks.remove(file.id)
        }
    }

    /**
     * Per-tick walk of active downloads. TDLib's documented behaviour (#2585) is that
     * a flaky network can leave `is_downloading_active=true` indefinitely — the daemon
     * itself never gives up, so a download stuck without progress for [STALL_THRESHOLD_MS]
     * gets a [TdApi.DownloadFile] reissue at the priority it was running at. TDLib
     * treats reissue as "kick the queue", not "start a new download", which matches
     * what the official Telegram clients do.
     *
     * After [MAX_STALL_RETRIES] failed reissues we surface Failed so the UI can render
     * a tap-to-retry affordance instead of a perpetual spinner.
     */
    private suspend fun checkStalled() {
        val now = System.currentTimeMillis()
        // Snapshot — we mutate the map inside the loop.
        val snapshot = tracks.entries.toList()
        for ((fileId, track) in snapshot) {
            if (now - track.changedAt < STALL_THRESHOLD_MS) continue
            val slot = states[fileId] ?: run {
                tracks.remove(fileId)
                continue
            }
            if (slot.value !is MediaState.Downloading) {
                tracks.remove(fileId)
                continue
            }
            // Re-read the live tracker. Between the snapshot and now the reducer could
            // have delivered real bytes and reset retries; acting on the stale snapshot
            // would overwrite a healthy state with Failed in that window.
            val live = tracks[fileId] ?: continue
            if (live.bytes != track.bytes || live.changedAt != track.changedAt) continue
            if (track.retries >= MAX_STALL_RETRIES) {
                Log.w(TAG, "stall watchdog: giving up on $fileId after ${track.retries} retries")
                slot.value = MediaState.Failed("завантаження зависло")
                tracks.remove(fileId)
                activePriority.remove(fileId)
                continue
            }
            val priority = activePriority[fileId] ?: DownloadPriority.VisibleMedia.tdValue
            try {
                td.send(TdApi.DownloadFile(fileId, priority, 0, 0, /* synchronous */ false))
                tracks.compute(fileId) { _, current ->
                    if (current == null || current.bytes != track.bytes) current
                    else current.copy(changedAt = now, retries = current.retries + 1)
                }
                Log.i(TAG, "stall watchdog: reissued $fileId (retry ${track.retries + 1}/$MAX_STALL_RETRIES)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "stall watchdog: reissue failed for $fileId", t)
            }
        }
    }

    fun observe(fileId: Int): StateFlow<MediaState> = slot(fileId).asStateFlow()

    /**
     * Idempotent: safe to call from each Composable that mounts.
     *  - Ready → no-op.
     *  - Downloading at ≥ requested priority → no-op.
     *  - Downloading at lower priority → reissue DownloadFile to upgrade the priority.
     *  - Failed → retry once.
     *  - Idle → fetch metadata + start download.
     *
     * Also aborts any debounced cancel for the same fileId — a quick scroll past a card
     * and back must not tear the in-flight download down.
     */
    suspend fun ensure(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        // Aborting first means a re-mount that beats the debounce window keeps the
        // in-flight download. Done before the hot-path guards because the cancel job
        // also clears activePriority — racing it would mean we early-return on a stale
        // priority record and miss reissuing DownloadFile.
        pendingCancels.remove(fileId)?.cancel()

        // Hot-path guards run *before* the dispatcher hop. A LazyColumn full of items
        // re-fires LaunchedEffect(fileId, priority) on every recompose, so this method
        // is hit dozens of times per second during a scroll — almost always for files
        // that are already Ready or already enqueued at ≥ this priority. Skipping the
        // withContext switch in that case is a real win.
        val current = slot(fileId).value
        if (current is MediaState.Ready) return
        val currentPriority = activePriority[fileId] ?: 0
        if (current is MediaState.Downloading && currentPriority >= priority.tdValue) return

        withContext(ioDispatcher) { ensureSlow(fileId, priority) }
    }

    private suspend fun ensureSlow(fileId: Int, priority: DownloadPriority) {
        // Re-read state inside the io context — another caller may have raced ahead.
        val current = slot(fileId).value
        if (current is MediaState.Ready) return
        val currentPriority = activePriority[fileId] ?: 0
        if (current is MediaState.Downloading && currentPriority >= priority.tdValue) return

        try {
            // Trust slot.value's Downloading only when we also still own an
            // activePriority record. The cancel job clears activePriority/tracks
            // before TDLib's post-cancel UpdateFile lands (and TDLib may emit no
            // UpdateFile at all if onlyIfPending=true was a no-op against an active
            // download). Without a fresh GetFile here, we'd skip metadata sync,
            // fire DownloadFile against a stale snapshot, and the user would see
            // the spinner sit at the pre-cancel percentage until the watchdog
            // reissues — that's the "hangs until I restart" symptom.
            val needsResync = current !is MediaState.Downloading || currentPriority == 0
            if (needsResync) {
                val file = td.send(TdApi.GetFile(fileId))
                // Hand the file to the reducer through the same channel the inbound
                // UpdateFile stream uses — the Ready-stick / eviction / throttle logic
                // lives in one place and runs on a single thread.
                fileEvents.send(file)
                // Don't peek `slot.value` here: the reducer hasn't necessarily run yet.
                // Inspect the file directly — if TDLib already has it on disk we skip
                // the redundant DownloadFile.
                if (file.local.isDownloadingCompleted && file.local.path.orEmpty().isNotEmpty()) return
            }
            td.send(TdApi.DownloadFile(fileId, priority.tdValue, 0, 0, /* synchronous */ false))
            activePriority[fileId] = priority.tdValue
            // Seed the watchdog tracker so it has something to compare against even if
            // the very first UpdateFile is dropped (SharedFlow boundary, hot restart,
            // or TDLib silence under bad network — see #2585).
            tracks.putIfAbsent(fileId, Track(0L, System.currentTimeMillis(), retries = 0, lastEmitMs = 0L))
        } catch (t: Throwable) {
            // A Composable leaving composition cancels its LaunchedEffect — that surfaces
            // here as LeftCompositionCancellationException (a CancellationException). Don't
            // log it as a failure or mark the slot Failed; the user just scrolled past.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "ensure($fileId, ${priority.name}) failed", t)
            // `update {}` is an atomic CAS-loop — a concurrent UpdateFile that just
            // finalised the slot to Ready won't be clobbered by our Failed write.
            slot(fileId).update { previous ->
                if (previous is MediaState.Ready) previous
                else MediaState.Failed(t.message ?: "download failed")
            }
            activePriority.remove(fileId)
            tracks.remove(fileId)
        }
    }

    /**
     * User-initiated cancel — fires immediately (no debounce window) and forces the
     * slot to [MediaState.Idle] so the UI can show "tap to retry". Unlike
     * [cancelIfPendingAsync] this passes `onlyIfPending=false` so an already-running
     * download is also stopped.
     */
    fun cancelExplicit(fileId: Int) {
        // Aborting any debounced cancel for the same id is a no-op safety net — the
        // explicit path runs immediately and writes the same state below.
        pendingCancels.remove(fileId)?.cancel()
        postCompletionResync.remove(fileId)?.cancel()
        scope.launch(ioDispatcher) {
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ false)) }
            activePriority.remove(fileId)
            tracks.remove(fileId)
            states[fileId]?.value = MediaState.Idle
        }
    }

    /**
     * Force a retry on a [MediaState.Failed] slot. Resets the slot to Idle and re-runs
     * the normal [ensure] path — same as a fresh observe, but without the caller having
     * to know the failure happened. Intended for UI "tap to retry" affordances on the
     * failed-state placeholder.
     */
    suspend fun retry(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        states[fileId]?.value = MediaState.Idle
        activePriority.remove(fileId)
        tracks.remove(fileId)
        postCompletionResync.remove(fileId)?.cancel()
        ensure(fileId, priority)
    }

    /**
     * Invalidate a [MediaState.Ready] slot whose on-disk file has gone missing. TDLib
     * does not emit [TdApi.UpdateFile] when its storage optimiser deletes a previously
     * completed file (the maintainer's official advice in #3178: *"the local file path
     * can become invalid in many ways. The app is supposed to call DownloadFile before
     * using the file."*). Without this hook the slot would stay Ready forever while
     * the renderer loaded a phantom path.
     *
     * Call site: image / video loaders that detect a load failure on a Ready slot
     * (e.g. Coil's onError when the [java.io.File] no longer exists). No-op for slots
     * that aren't currently Ready. Fire-and-forget: runs on the app scope so it isn't
     * cancelled by the very recomposition the state flip triggers.
     */
    fun invalidate(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        val s = states[fileId] ?: return
        if (s.value !is MediaState.Ready) return
        Log.i(TAG, "invalidate: $fileId — Ready file missing on disk; re-downloading")
        scope.launch(ioDispatcher) {
            s.value = MediaState.Idle
            activePriority.remove(fileId)
            tracks.remove(fileId)
            postCompletionResync.remove(fileId)?.cancel()
            ensure(fileId, priority)
        }
    }

    /**
     * Debounced cancellation. TDLib's [TdApi.CancelDownloadFile] with `onlyIfPending=true`
     * is meant to be cheap — partial bytes are preserved and a re-mount picks up where
     * left off. The catch is timing: dispose-and-remount in the same frame (LazyColumn
     * scroll) used to issue cancel + ensure on the IO dispatcher with no ordering, so a
     * cancel could land *after* the new DownloadFile and silently kill the slot.
     *
     * Now we wait [CANCEL_DEBOUNCE_MS] before sending. A new [ensure] inside the window
     * aborts this job and the in-flight download keeps running. Any previously queued
     * cancel for the same fileId is replaced and cancelled.
     */
    /**
     * Single-shot, atomically-deduped GetFile [POST_COMPLETION_RESYNC_MS] from now.
     * Called whenever the reducer sees Downloading(1.0) — the canonical "tail of
     * download, awaiting TDLib rename" snapshot.
     *
     * Why single-shot: if TDLib's GetFile reply lands and is *still* Downloading(1.0)
     * (file's not yet renamed on TDLib's side, e.g. very large file), a re-arm here
     * would set up a 400ms poll loop until rename happens. We let the reducer's
     * Ready/Failed transition clear the registration instead — this means subsequent
     * Downloading(1.0) emits during the same completion sequence are no-ops, and the
     * watchdog (`STALL_THRESHOLD_MS`) is the next escalation if rename never lands.
     *
     * Why lazy start + putIfAbsent: gives us a clean "first writer wins" race without
     * a separate `containsKey` check that would itself be racy. The losers' jobs are
     * built but never started, so they cost almost nothing to discard.
     */
    private fun schedulePostCompletionResync(fileId: Int) {
        val newJob = scope.launch(ioDispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(POST_COMPLETION_RESYNC_MS)
            // If the reducer already landed on Ready/Failed, our work is moot — bail
            // without an extra TDLib roundtrip. The reducer also proactively removes
            // (and cancels) this job on the terminal transition, so we'd typically
            // be cancelled before reaching here; this check covers the race where
            // the transition happens between delay() and now.
            val current = states[fileId]?.value
            if (current !is MediaState.Downloading) return@launch
            runCatching {
                val freshFile = td.send(TdApi.GetFile(fileId))
                fileEvents.send(freshFile)
            }
        }
        val existing = postCompletionResync.putIfAbsent(fileId, newJob)
        if (existing == null) newJob.start() else newJob.cancel()
    }

    fun cancelIfPendingAsync(fileId: Int) {
        lateinit var jobRef: Job
        val job = scope.launch(ioDispatcher) {
            delay(CANCEL_DEBOUNCE_MS)
            // Past the debounce window, do the cancel + resync as one atomic unit.
            // [kotlinx.coroutines.NonCancellable] guards against a stray ensure() that
            // raced past `pendingCancels.remove(fileId)?.cancel()` after we'd already
            // sent CancelDownloadFile — without this, cancel() would tear down the
            // in-flight GetFile mid-resync and leave the slot in a stale Downloading
            // snapshot that no watchdog event would correct.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ true))
                    // TDLib does not necessarily emit UpdateFile after CancelDownloadFile
                    // — for an active (non-pending) download the cancel is a no-op, and
                    // for a real cancel TDLib may stay silent. Without resyncing here,
                    // the slot would keep the pre-cancel Downloading snapshot until the
                    // watchdog (or a user re-mount) noticed. A targeted GetFile makes
                    // the reducer see authoritative state and corrects the slot
                    // immediately.
                    val file = td.send(TdApi.GetFile(fileId))
                    fileEvents.send(file)
                }
                activePriority.remove(fileId)
                tracks.remove(fileId)
                // Compare-and-remove: if a fresh cancelIfPendingAsync raced ahead of
                // us and put a new job in this slot, leave that one alone.
                pendingCancels.remove(fileId, jobRef)
            }
        }
        jobRef = job
        pendingCancels.put(fileId, job)?.cancel()
    }

    private fun slot(fileId: Int): MutableStateFlow<MediaState> =
        states.computeIfAbsent(fileId) { MutableStateFlow(MediaState.Idle) }

    private companion object {
        const val TAG = "MediaCache"
        // Throttle Downloading→Downloading progress emits per fileId. TDLib reports
        // sub-percent progress at 30+ Hz; the UI doesn't need that granularity.
        const val PROGRESS_MIN_INTERVAL_MS = 100L
        // How often the watchdog scans active downloads for stalls. Cheap walk, so
        // a 2s cadence still gives us sub-12s recovery on the worst case.
        const val WATCHDOG_TICK_MS = 2_000L
        // Cadence while no downloads are in flight. We don't need this to be high —
        // a fresh ensure() that landed just after the empty-check shouldn't have to
        // wait 30s for the next sweep. 5s keeps wake-ups cheap (12/min when truly
        // idle) while making first-stall detection feel instant.
        const val IDLE_TICK_MS = 5_000L
        // No bytes for this long → reissue. Tuned down from 30s: TDLib treats
        // DownloadFile reissue as a free "kick the queue" (not a restart), so an
        // unnecessary nudge during a slow-but-progressing transfer costs us nothing
        // (real progress resets `changedAt` — only true zero-byte intervals trip the
        // watchdog), while a genuine stall recovers ~2× faster.
        const val STALL_THRESHOLD_MS = 15_000L
        // After three reissues with no progress we surface Failed (~60s total). One
        // more retry than before, since a single flaky DC-migration window can eat
        // the first reissue cycle without that being a permanent failure.
        const val MAX_STALL_RETRIES = 3
        // Window during which a re-mount can rescue a queued cancel. 250ms covers a
        // LazyColumn dispose-and-remount cycle (one frame at 60fps + some slack)
        // without keeping cancelled downloads pinned long enough for a real
        // navigation-away to feel laggy.
        const val CANCEL_DEBOUNCE_MS = 250L
        // Delay between seeing Downloading(1.0) and forcing a GetFile resync. Long
        // enough that the happy-path follow-up UpdateFile (which arrives in single-
        // digit ms) has every chance to land first and cancel the resync; short
        // enough that the user perceives the eventual recovery as instant. Below
        // 200ms we'd often race ahead of TDLib's rename and end up with the same
        // empty-path snapshot we're trying to fix.
        const val POST_COMPLETION_RESYNC_MS = 400L
    }
}

sealed interface MediaState {
    data object Idle : MediaState
    /**
     * @param progress 0..1, what fraction of [totalBytes] has reached disk.
     * @param downloadedBytes mirror of TDLib's `local.downloadedSize` — what the UI shows
     *   on the left of the "5.2 / 12.4 MB" label.
     * @param totalBytes preferred from `size` (server-side total), falling back to
     *   `expectedSize` (heuristic estimate) when the real size is still unknown. 0 when
     *   neither is known — UI should hide the byte label in that case rather than print
     *   "5.2 / 0 MB".
     */
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) : MediaState
    data class Ready(val path: String) : MediaState
    data class Failed(val reason: String) : MediaState
}

private fun TdApi.File.toMediaState(): MediaState {
    val localPath = local.path.orEmpty()
    // TDLib occasionally reports completion in two consecutive UpdateFile bursts: first
    // with isDownloadingCompleted=true but path still empty, then with the path filled in.
    // Treat the second one as the canonical Ready; the first one stays Downloading at 100%.
    if (local.isDownloadingCompleted && localPath.isNotEmpty()) {
        return MediaState.Ready(localPath)
    }
    // canBeDownloaded=false + nothing downloaded yet means the file is gone server-side
    // (expired sticker, restricted media, etc). Without this branch the UI sat on a 0%
    // spinner forever — TDLib never sends a "failed" UpdateFile, just stops emitting.
    if (!local.canBeDownloaded && !local.isDownloadingActive && !local.isDownloadingCompleted) {
        return MediaState.Failed("file not available")
    }
    // Prefer the server-confirmed `size` over the heuristic `expectedSize`. Either may
    // arrive on the first UpdateFile or only later — we show 0 totalBytes until one of
    // them lands so the UI knows to skip the byte label rather than print zero-divided
    // garbage.
    val resolvedTotal: Long = when {
        size > 0 -> size
        expectedSize > 0 -> expectedSize
        else -> 0L
    }
    // When TDLib's authoritative completion flag is up but the path hasn't landed
    // yet, force progress to 1.0 instead of trusting downloadedSize/expectedSize.
    // `expectedSize` is a heuristic and is frequently *larger* than the real final
    // byte count, which would otherwise pin a "completed" file at e.g. 94% — both
    // confusing for the user and a missed signal for the reducer's resync trigger.
    val progress = when {
        local.isDownloadingCompleted -> 1f
        resolvedTotal > 0 -> (local.downloadedSize.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    return MediaState.Downloading(
        progress = progress,
        downloadedBytes = local.downloadedSize,
        totalBytes = resolvedTotal,
    )
}
