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
 *     [cancelDeferred] against the next [ensure]. Both run on the IO dispatcher in
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
    private val res: StringResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val states = ConcurrentHashMap<Int, MutableStateFlow<MediaState>>()
    private val activePriority = ConcurrentHashMap<Int, Int>()

    // Per-file bookkeeping for the watchdog AND the StateFlow throttle. Merged into one
    // record so we don't keep two ConcurrentHashMaps in lockstep:
    //   • `bytes`/`changedAt`/`retries` drive the stall watchdog. `bytes` is the last
    //     observed `local.downloadedSize`; `changedAt` flips whenever it actually moves;
    //     `retries` counts how many DownloadFile reissues we've done since the last move.
    //   • `isActive` mirrors TDLib's `local.isDownloadingActive`. Critical for the
    //     watchdog: a file that's queued in TDLib's per-DC slot pool reads the same
    //     `(bytes=0, changedAt=ensure-time)` as a file actively trying to download but
    //     stalled at zero progress. Without this flag, the watchdog burst-reissues every
    //     queued file the moment it crosses the threshold — see logcat sample with 22
    //     reissues in 5ms during heavy scroll. Reissuing a queued file is a no-op for
    //     TDLib (it's already in the queue) and only churns LIFO order pointlessly.
    //   • `lastEmitMs` throttles Downloading→Downloading recomposition. TDLib reports
    //     progress at 30+ Hz per active file; the UI doesn't need that granularity, and
    //     unconditional emits cause real CPU + GC tax in album fullscreen. Throttle is
    //     bypassed for the 100% emit so the bar doesn't look frozen at 99%.
    private data class Track(
        val bytes: Long,
        val changedAt: Long,
        val retries: Int,
        val lastEmitMs: Long,
        val isActive: Boolean,
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

    // Single-coroutine reducer for all File-state mutations. Three producers feed it
    // through this channel, in strict single-writer discipline — any other write to
    // `states[id].value` / `tracks[id]` / `activePriority[id]` is a documented bug and
    // races the reducer's own writes on Dispatchers.IO's worker pool:
    //   1. Inbound TDLib UpdateFile events (the live stream).
    //   2. ensureSlow's GetFile response when seeding a fresh slot.
    //   3. User-initiated [cancelExplicit] / [retry] / [invalidate] resets, modelled as
    //      a synthetic [FileEvent.Reset] — these used to write `states[id].value =
    //      MediaState.Idle` directly, which raced with concurrent UpdateFile drains
    //      (reducer flips slot back to Downloading mid-cancel → user sees a flicker).
    // Capacity is unlimited — we'd rather pay a bit of memory than risk dropping a
    // terminal event under burst pressure.
    private sealed interface FileEvent {
        /** Inbound TdApi.File payload (UpdateFile or GetFile response). */
        data class FromTdLib(val file: TdApi.File) : FileEvent
        /**
         * User-initiated reset of a slot to a target state — bypasses [reduce]'s
         * "don't slide back from Ready" guard because the cancel/retry/invalidate
         * intent is explicit and authoritative.
         */
        data class Reset(val fileId: Int, val to: MediaState) : FileEvent
        /**
         * Conditional Reset: only flip the slot to [to] if it is currently in
         * [MediaState.Ready] (Coil's onError callback semantics — "the file I
         * just tried to render is gone"). Routed through the reducer so the
         * is-Ready check + the state flip happen as one atomic step against
         * the inbound UpdateFile stream; otherwise a concurrent UpdateFile
         * landing between the IO-thread `is Ready` peek and the write would
         * be silently clobbered.
         */
        data class ResetIfReady(val fileId: Int, val to: MediaState) : FileEvent
    }
    private val fileEvents = Channel<FileEvent>(capacity = Channel.UNLIMITED)

    init {
        // Reducer. Single writer = no data race over `states[id].value`, `tracks[id]`,
        // or `activePriority`'s read-write pairs.
        scope.launch(ioDispatcher) {
            for (event in fileEvents) when (event) {
                is FileEvent.FromTdLib -> reduce(event.file)
                is FileEvent.Reset -> reduceReset(event.fileId, event.to)
                is FileEvent.ResetIfReady -> {
                    val slot = states[event.fileId]
                    if (slot != null && slot.value is MediaState.Ready) {
                        reduceReset(event.fileId, event.to)
                    }
                }
            }
        }

        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            // Updates for files we never observed are dropped at the reducer (see
            // [reduce]) — TDLib emits UpdateFile for everything in its database, not
            // just things this app rendered. Slots are created lazily by [observe]
            // / [ensure].
            .onEach { update -> fileEvents.send(FileEvent.FromTdLib(update.file)) }
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
                evictTerminalSlots()
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
     * Drop state slots that are no longer useful: terminal state ([MediaState.Ready],
     * [MediaState.Failed], [MediaState.Idle]) AND zero observers AND no in-flight
     * download/cancel/resync bookkeeping. Each [MutableStateFlow] is small (≈100 bytes)
     * but a long browsing session walks past tens of thousands of unique fileIds, and
     * unevicted slots accumulate to multi-MB heap residency that never shrinks.
     *
     * This is gated on [SLOT_EVICTION_THRESHOLD] so the common case (a few hundred
     * resident slots while the user is in a chat) pays nothing. A re-mount of an evicted
     * slot is cheap: [ensure] re-creates the StateFlow and a single [TdApi.GetFile] re-
     * seeds it from TDLib's local cache, which is sub-millisecond on a hot file.
     */
    private fun evictTerminalSlots() {
        if (states.size <= SLOT_EVICTION_THRESHOLD) return
        val iter = states.entries.iterator()
        while (iter.hasNext()) {
            val (id, flow) = iter.next()
            if (flow.subscriptionCount.value > 0) continue
            if (tracks.containsKey(id) || pendingCancels.containsKey(id) || postCompletionResync.containsKey(id)) continue
            val s = flow.value
            val terminal = s is MediaState.Ready || s is MediaState.Failed || s is MediaState.Idle
            if (!terminal) continue
            iter.remove()
            activePriority.remove(id)
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
            val isActive = file.local.isDownloadingActive
            val prev = tracks[file.id]
            // Real progress resets the retry counter — a download that survives one
            // watchdog reissue and then stalls again later still gets the full budget.
            val movedBytes = prev == null || prev.bytes != bytes
            val base = when {
                prev == null -> Track(bytes, now, retries = 0, lastEmitMs = 0L, isActive = isActive)
                movedBytes -> prev.copy(bytes = bytes, changedAt = now, retries = 0, isActive = isActive)
                prev.isActive != isActive -> prev.copy(isActive = isActive)
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
     * Apply a user-initiated slot reset inside the reducer loop. Bypasses the
     * "don't slide back from Ready" guard in [reduce] because the reset intent
     * (cancel / retry / invalidate) is explicit and authoritative — the user
     * either tapped the cancel control, requested a retry, or the renderer
     * detected an evicted on-disk file. Bookkeeping (priority, tracks) follows
     * the same shape as the terminal-transition cleanup in [reduce] so callers
     * that immediately follow with [ensure] start from a clean slate.
     */
    private fun reduceReset(fileId: Int, to: MediaState) {
        val slot = states[fileId] ?: return
        slot.value = to
        // Mirror [reduce]'s terminal cleanup: any non-Downloading target means
        // we no longer hold an in-flight download, so the watchdog tracker and
        // active-priority record become stale and must be cleared in lockstep
        // with the state transition (otherwise the watchdog would think a
        // freshly-Idle slot is mid-stall and reissue DownloadFile).
        if (to !is MediaState.Downloading) {
            activePriority.remove(fileId)
            tracks.remove(fileId)
        }
    }

    /**
     * Per-tick walk of active downloads. TDLib's documented behaviour (#2585) is that
     * a flaky network can leave `is_downloading_active=true` indefinitely — the daemon
     * itself never gives up, so a download stuck without progress past the priority's
     * threshold gets a [TdApi.DownloadFile] reissue at the priority it was running at.
     * TDLib treats reissue as "kick the queue", not "start a new download", which matches
     * what the official Telegram clients do.
     *
     * Threshold is graduated by **how visible the stall is to the user** plus **how
     * confident we are it's a real stall**:
     *
     *   • [STALL_THRESHOLD_FIRST_BYTE_MS] — for visible files where TDLib reports
     *     `is_downloading_active=true` but no byte has landed yet. Almost always a
     *     stuck MTProto request: the bytes-on-the-wire phase normally takes 200-2000 ms
     *     end-to-end on a healthy network, so a 4 s gap is genuinely abnormal. Catches
     *     the "довго грузилось" case the user reports for top-of-feed media.
     *   • [STALL_THRESHOLD_FOREGROUND_MS] — for visible files mid-stream. We can't be
     *     as aggressive here (a chunk-to-chunk gap on a slow-but-progressing connection
     *     is 1-5 s legitimately), so 6 s is the line where it reads as "stuck" rather
     *     than "slow". Once the file gets going, this is the relevant threshold.
     *   • [STALL_THRESHOLD_MS] — for `Prefetch`/`Avatar` (speculative, off-screen, or
     *     always second-class). No UX win from spamming reissue for a thumbnail the
     *     user may never see; 15 s matches TDLib's own session-reconnect window.
     *
     * Skip non-active entries entirely: a file queued in TDLib's per-DC slot pool reads
     * as `is_downloading_active=false` even though it has [MediaState.Downloading] in
     * the slot. Reissuing it is a no-op for TDLib (already in queue) and only churns
     * same-priority LIFO order — see logcat sample with 22 reissues in 5 ms during
     * heavy scroll before this filter was added.
     *
     * After [MAX_STALL_RETRIES] failed reissues we surface Failed so the UI can render
     * a tap-to-retry affordance instead of a perpetual spinner.
     */
    private suspend fun checkStalled() {
        val now = System.currentTimeMillis()
        // Snapshot — we mutate the map inside the loop.
        val snapshot = tracks.entries.toList()
        for ((fileId, track) in snapshot) {
            if (!track.isActive) continue
            val priority = activePriority[fileId] ?: DownloadPriority.VisibleMedia.tdValue
            val isUserFacing = priority >= DownloadPriority.VisibleMedia.tdValue
            val threshold = when {
                isUserFacing && track.bytes == 0L -> STALL_THRESHOLD_FIRST_BYTE_MS
                isUserFacing -> STALL_THRESHOLD_FOREGROUND_MS
                else -> STALL_THRESHOLD_MS
            }
            val ageMs = now - track.changedAt
            if (ageMs < threshold) continue
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
                Log.w(TAG, "stall watchdog: giving up on $fileId after ${track.retries} retries (priority=$priority, bytes=${track.bytes}, age=${ageMs}ms)")
                slot.value = MediaState.Failed(res.getString(dev.lyo.hortay.R.string.media_load_stalled))
                tracks.remove(fileId)
                activePriority.remove(fileId)
                continue
            }
            try {
                td.send(TdApi.DownloadFile(fileId, priority, 0, 0, /* synchronous */ false))
                tracks.compute(fileId) { _, current ->
                    if (current == null || current.bytes != track.bytes) current
                    else current.copy(changedAt = now, retries = current.retries + 1)
                }
                Log.i(TAG, "stall watchdog: reissued $fileId (priority=$priority, bytes=${track.bytes}, age=${ageMs}ms, retry ${track.retries + 1}/$MAX_STALL_RETRIES)")
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
                fileEvents.send(FileEvent.FromTdLib(file))
                // Don't peek `slot.value` here: the reducer hasn't necessarily run yet.
                // Inspect the file directly — if TDLib already has it on disk we skip
                // the redundant DownloadFile.
                if (file.local.isDownloadingCompleted && file.local.path.orEmpty().isNotEmpty()) return
            }
            td.send(TdApi.DownloadFile(fileId, priority.tdValue, 0, 0, /* synchronous */ false))
            activePriority[fileId] = priority.tdValue
            // Seed the watchdog tracker so it has something to compare against even if
            // the very first UpdateFile is dropped (SharedFlow boundary, hot restart,
            // or TDLib silence under bad network — see #2585). isActive=false initially
            // because we have no proof TDLib has started actually transferring yet — the
            // first reducer event will flip this from `file.local.isDownloadingActive`.
            tracks.putIfAbsent(
                fileId,
                Track(0L, System.currentTimeMillis(), retries = 0, lastEmitMs = 0L, isActive = false),
            )
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
     * [cancelDeferred], it bypasses the observer-count and foreground guards (the
     * user explicitly tapped the cancel control, so we honour the intent regardless
     * of who else might be observing).
     */
    fun cancelExplicit(fileId: Int) {
        // Aborting any debounced cancel for the same id is a no-op safety net — the
        // explicit path runs immediately and writes the same state below.
        pendingCancels.remove(fileId)?.cancel()
        postCompletionResync.remove(fileId)?.cancel()
        scope.launch(ioDispatcher) {
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ false)) }
            // Route the slot reset through the same single-writer reducer the inbound
            // UpdateFile stream uses. Direct `states[id].value = Idle` from this
            // coroutine raced concurrent reducer drains: a stale UpdateFile that
            // landed mid-cancel could flip the slot back to Downloading right after
            // we wrote Idle, producing a brief flicker of the spinner the user just
            // dismissed. The Reset event executes inside the reducer's loop, so any
            // queued UpdateFile from before the cancel drains first; everything
            // arriving AFTER the reset is a fresh-state event that the user expects
            // to see.
            fileEvents.send(FileEvent.Reset(fileId, MediaState.Idle))
        }
    }

    /**
     * Force a retry on a [MediaState.Failed] slot. Resets the slot to Idle and re-runs
     * the normal [ensure] path — same as a fresh observe, but without the caller having
     * to know the failure happened. Intended for UI "tap to retry" affordances on the
     * failed-state placeholder.
     */
    suspend fun retry(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        // Reset through the reducer, not direct write — same race rationale as
        // [cancelExplicit]. ensure() then schedules its own GetFile through the
        // same channel, so the reducer sees: Reset → fresh GetFile → Downloading,
        // in strictly the order ensure() emits.
        postCompletionResync.remove(fileId)?.cancel()
        fileEvents.send(FileEvent.Reset(fileId, MediaState.Idle))
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
        // Don't peek `slot.value` here — race window: Coil sees a file-not-found
        // error, calls invalidate(), and between this peek and the launched
        // coroutine's send, the reducer drains an UpdateFile that legitimately
        // pushed the slot from Ready to Downloading (TDLib evicted then started
        // re-downloading). The early-return on the peek would still be racy.
        // Route the is-Ready check through the reducer's single-writer loop via
        // [FileEvent.ResetIfReady] so the check + flip happen against the same
        // serial event stream that the reducer uses.
        if (states[fileId] == null) return
        Log.i(TAG, "invalidate: $fileId — Ready file missing on disk; re-downloading")
        scope.launch(ioDispatcher) {
            postCompletionResync.remove(fileId)?.cancel()
            fileEvents.send(FileEvent.ResetIfReady(fileId, MediaState.Idle))
            // ensure() is a no-op when the slot is already Downloading, so a
            // racing UpdateFile that flipped Ready → Downloading between the
            // ResetIfReady event and here just makes ensure() short-circuit
            // (the existing Downloading state is preserved). Only when
            // ResetIfReady actually flipped to Idle does ensure() restart the
            // download.
            ensure(fileId, priority)
        }
    }

    /**
     * Authoritative state refresh for [fileId]. Issues [TdApi.GetFile] and routes
     * the reply through the reducer's [fileEvents] channel — same path as inbound
     * [TdApi.UpdateFile], so reducer sequencing and Ready-stick guarantees are
     * preserved end-to-end.
     *
     * Intended call site: Compose mount of any TDLib renderer (via
     * [dev.lyo.hortay.ui.media.rememberMediaBinding]). The defensive resync exists
     * to lock down a real bug pattern users described as *"показує що не загружено,
     * але якщо проскролити вниз і знову вверх — все вже там"*. Root cause: a slot
     * that has been left in [MediaState.Downloading] with [activePriority] still set
     * (e.g. because we never observed the terminal Ready transition — lost UpdateFile,
     * background-while-completing race, or a debounced cancel that fired right before
     * TDLib's `isDownloadingCompleted=true` tail event). On re-mount,
     * [ensure] short-circuits (`currentPriority >= priority.tdValue`) and the slot
     * stays pinned at the stale value, even though TDLib has the file Ready on disk.
     *
     * `resync` bypasses ensure's optimisation: it unconditionally asks TDLib *"what
     * is this file, really?"* and routes the answer through the reducer. If TDLib
     * has it Ready, the slot transitions to Ready and the UI updates next frame.
     * If TDLib reports a stale Downloading too, no harm done — the reducer's
     * existing logic handles every shape correctly.
     *
     * Aligned with Levin's tdlib/td#3178 *"the local file path can become invalid in
     * many ways. The app is supposed to call downloadFile before using the file"* —
     * this is the read-side equivalent of his guidance for the write side.
     *
     * No-op for slots that no Composable has yet observed (creating one for a
     * resync-only flow would defeat [evictTerminalSlots]'s memory cap). Idempotent
     * and concurrency-safe: identical events from concurrent resyncs collapse
     * cleanly inside the reducer.
     */
    fun resync(fileId: Int) {
        if (states[fileId] == null) return
        scope.launch(ioDispatcher) {
            runCatching {
                val file = td.send(TdApi.GetFile(fileId))
                fileEvents.send(FileEvent.FromTdLib(file))
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                Log.w(TAG, "resync($fileId) failed", err)
            }
        }
    }

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
        lateinit var jobRef: Job
        val newJob = scope.launch(ioDispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
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
                    fileEvents.send(FileEvent.FromTdLib(freshFile))
                }
            } finally {
                // Whether we ran the resync, were cancelled by the reducer's
                // Ready/Failed cleanup, or returned early — drop our entry from
                // the map. Without this, a job that finished naturally (no
                // subsequent terminal-transition cancel) leaves a stale Job
                // reference in [postCompletionResync] forever, and the next
                // schedulePostCompletionResync(fileId) call's putIfAbsent races
                // against that ghost entry. compare-and-remove via the 2-arg
                // overload protects against a fresh schedulePostCompletionResync
                // having already replaced our slot in the meantime.
                postCompletionResync.remove(fileId, jobRef)
            }
        }
        jobRef = newJob
        val existing = postCompletionResync.putIfAbsent(fileId, newJob)
        if (existing == null) newJob.start() else newJob.cancel()
    }

    /**
     * Debounced "scrolled past" cancellation. Frees TDLib's per-DC download slot for the
     * given fileId so currently-visible media isn't blocked behind ghost downloads from
     * posts the user has already scrolled past.
     *
     * Why this matters: TDLib serves only ~4 simultaneous downloads per DC out of its
     * `small_queue_max_active_operations_count`/`large_queue_max_active_operations_count`
     * pool ([core.telegram.org/api/files]) and serves same-priority files in LIFO order
     * (tdlib/td#2179 — by design: "much better to have some files fully downloaded than
     * have all files partly downloaded"). Without an active cancel on dispose, every
     * media item the user scrolls past keeps consuming a slot until it finishes — a fast
     * scroll through a feed easily ends up with 10–20 ghosts ahead of the now-visible
     * post in TDLib's queue, surfacing as "stalls for several seconds at N bytes" on
     * the visible item.
     *
     * Why `onlyIfPending=false`: that flag only cancels not-yet-started requests, which
     * is exactly the opposite of what we need — the slot-blocking ghosts ARE active.
     * `false` actually stops the active download. Partial bytes survive on disk either
     * way (TDLib never deletes the temp file on cancel), so a re-mount past the debounce
     * window simply resumes from the saved offset on the next `DownloadFile`.
     *
     * Why debounce: a LazyColumn dispose-and-remount within the same frame (typical on
     * fast scroll) would otherwise issue cancel + ensure on the IO dispatcher with no
     * ordering, and a cancel landing *after* the fresh DownloadFile would silently kill
     * the slot. [CANCEL_DEBOUNCE_MS] gives the re-mount a window to abort the cancel
     * before it fires; any previously queued cancel for the same fileId is replaced and
     * cancelled.
     */
    fun cancelDeferred(fileId: Int) {
        lateinit var jobRef: Job
        val job = scope.launch(ioDispatcher) {
            delay(CANCEL_DEBOUNCE_MS)
            val slot = states[fileId]
            // No-op for slots that aren't actually downloading — common during scroll-
            // gated transient mounts that dispose without ever triggering ensure(),
            // and for slots that finished or failed in the debounce window. Saves the
            // CancelDownloadFile + GetFile roundtrip pair per such transit (~60 calls
            // per scroll-to-top through 30 intermediate posts).
            if (slot == null || slot.value !is MediaState.Downloading) {
                pendingCancels.remove(fileId, jobRef)
                return@launch
            }
            // Same fileId can be observed from several places at once (e.g. one channel
            // avatar painted in multiple visible posts, the same playback file behind a
            // poster + autoplayer). Each consumer's DisposableEffect schedules its own
            // cancelDeferred on dispose; we only want the cancel to fire when the LAST
            // observer goes away. Past the debounce window, the StateFlow's
            // subscriptionCount is settled — anything > 0 means someone is still using
            // the file and an active cancel here would yank it out from under them.
            //
            // The foreground guard handles a subtle lifecycle edge case: collectAsState-
            // WithLifecycle pauses its collection on ProcessLifecycle ON_STOP, which drops
            // subscriptionCount to 0 even though the consuming composables are still in
            // composition and will resume observing on foreground return. Without this
            // guard, a dispose+background race (cancel scheduled in foreground, debounce
            // window crosses ON_STOP) would silently kill a file that other still-mounted
            // composables expect to be downloading. Skipping the cancel in background is
            // safe — TDLib already throttles network traffic in background, and the next
            // explicit user action (mount, scroll, retry) will reissue DownloadFile.
            if (slot.subscriptionCount.value > 0 || !foreground.value) {
                pendingCancels.remove(fileId, jobRef)
                return@launch
            }
            // Past the debounce window, do the cancel + resync as one atomic unit.
            // [kotlinx.coroutines.NonCancellable] guards against a stray ensure() that
            // raced past `pendingCancels.remove(fileId)?.cancel()` after we'd already
            // sent CancelDownloadFile — without this, cancel() would tear down the
            // in-flight GetFile mid-resync and leave the slot in a stale Downloading
            // snapshot that no watchdog event would correct.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ false))
                    // TDLib's UpdateFile after CancelDownloadFile isn't reliably emitted
                    // (sometimes it lands, sometimes it doesn't). A targeted GetFile makes
                    // the reducer see authoritative state — `is_downloading_active=false`
                    // with the partial `downloadedSize` preserved — instead of leaving the
                    // slot on its pre-cancel Downloading snapshot until the next observe.
                    val file = td.send(TdApi.GetFile(fileId))
                    fileEvents.send(FileEvent.FromTdLib(file))
                }
                activePriority.remove(fileId)
                tracks.remove(fileId)
                // Compare-and-remove: if a fresh cancelDeferred raced ahead of
                // us and put a new job in this slot, leave that one alone.
                pendingCancels.remove(fileId, jobRef)
            }
        }
        jobRef = job
        pendingCancels.put(fileId, job)?.cancel()
    }

    private fun slot(fileId: Int): MutableStateFlow<MediaState> =
        states.computeIfAbsent(fileId) { MutableStateFlow(MediaState.Idle) }

    /**
     * Wipe every file-state slot, in-flight cancel/resync job, and download
     * priority record. Called from [AppGraph] on logout — TDLib's database
     * is being torn down by `LogOut`, so the fileIds we held references to
     * (cached photo/avatar/sticker IDs from account A's chats) are about to
     * become invalid. Without this wipe, surviving observers would see
     * orphan Ready slots whose on-disk paths no longer exist after TDLib
     * recreates its file table for account B.
     *
     * Active observers see their slots flip back to [MediaState.Idle] (via
     * the cleared `states` map's next computeIfAbsent), and their next
     * `ensure()` re-seeds metadata via GetFile against the fresh TDLib
     * database. In practice every observer will already be in the middle
     * of being torn down because the UI is in transition to AuthScreen
     * anyway.
     */
    fun clear() {
        states.clear()
        tracks.clear()
        activePriority.clear()
        pendingCancels.values.forEach { it.cancel() }
        pendingCancels.clear()
        postCompletionResync.values.forEach { it.cancel() }
        postCompletionResync.clear()
    }

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
        // Tighter threshold for files the user is actively staring at
        // (Foreground/VisibleMedia priorities) once bytes have started flowing. Server-side
        // stalls in the 5-15s window are common per TDLib #2585; without this, a "довго
        // грузилось" feeling persists up to 15s before the watchdog kicks. 6s is below
        // human "is this stuck?" attention threshold (≈8-10s for content the user expects).
        const val STALL_THRESHOLD_FOREGROUND_MS = 6_000L
        // Even tighter window for the "first byte never arrived" case on user-visible
        // media: TDLib has reported `is_downloading_active=true` but `downloadedSize`
        // is still 0. Healthy first-byte latency is 200-2000 ms (DC roundtrip + MTProto
        // request scheduling), so 4 s of zero is almost always a stuck request that a
        // free reissue will dislodge. Three retries of this path = ~12s total before
        // we give up — matches the lower edge of TDLib's own 7-44s session-reconnect
        // window, so we always have a chance to either dislodge the request or hand
        // off to TDLib's reconnect-driven recovery.
        const val STALL_THRESHOLD_FIRST_BYTE_MS = 4_000L
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
        // Above this many resident state slots, the watchdog tick sweeps unobserved
        // terminal slots out of [states]/[activePriority]. 512 comfortably holds a
        // foreground viewport plus realistic recent scroll history; beyond it we're in
        // long-session territory where the overhead of an O(N) walk per tick is dwarfed
        // by the multi-MB heap savings from dropping zombie StateFlows.
        const val SLOT_EVICTION_THRESHOLD = 512
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
