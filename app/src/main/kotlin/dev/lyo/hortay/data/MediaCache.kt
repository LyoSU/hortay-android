package dev.lyo.hortay.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    // TDLib emits Downloading→Downloading progress at 30+ Hz per active file. Each one
    // currently writes to a MutableStateFlow → recompose. With several concurrent
    // downloads (album fullscreen) that's real CPU + GC tax for sub-pixel progress
    // changes the user can't perceive anyway.
    private val lastProgressEmitMs = ConcurrentHashMap<Int, Long>()

    // Per-file stall tracker: last observed downloadedSize, when it last *changed*, and
    // how many watchdog reissues we've done. Populated as updates arrive and seeded on
    // ensureSlow so even an UpdateFile dropped at the SharedFlow boundary doesn't leave
    // the watchdog blind.
    private data class StallTrack(val bytes: Long, val changedAt: Long, val retries: Int)
    private val stallTrack = ConcurrentHashMap<Int, StallTrack>()

    // In-flight cancel jobs awaiting their debounce window. ensure() pulls and cancels
    // these so a re-mounted item is not torn down by an earlier dispose.
    private val pendingCancels = ConcurrentHashMap<Int, Job>()

    init {
        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            // Updates for files we never observed are dropped at the reducer (see
            // applyFileEvent) — TDLib emits UpdateFile for everything in its database,
            // not just things this app rendered.
            .onEach { update -> applyFileEvent(update.file) }
            .launchIn(scope)

        // Battery-aware stall watchdog. Three regimes:
        //   • App backgrounded → suspend on `foreground.first { it }` (zero CPU; resumes
        //     when ProcessLifecycleOwner reports ON_START again). We don't need recovery
        //     while no one is looking at the screen, and TDLib resumes downloads itself
        //     when the app foregrounds.
        //   • Foreground but no active downloads → tick every IDLE_TICK_MS (30 s). The
        //     loop still has to wake occasionally so a fresh ensure() that started just
        //     after the last empty-check is picked up — but the wake-up cost at idle is
        //     two orders of magnitude lower than at the active 2 s cadence.
        //   • Foreground + active downloads → tick every WATCHDOG_TICK_MS (2 s). At the
        //     STALL_THRESHOLD_MS (10 s) of silence the next tick reissues; worst-case
        //     recovery is ~12 s.
        scope.launch(ioDispatcher) {
            while (isActive) {
                if (!foreground.value) {
                    foreground.filter { it }.first()
                }
                val cadence = if (stallTrack.isEmpty()) IDLE_TICK_MS else WATCHDOG_TICK_MS
                delay(cadence)
                if (stallTrack.isEmpty()) continue
                if (connection.value != ConnectionStatus.Ready) continue
                checkStalled()
            }
        }
    }

    /**
     * Reducer for an EXISTING slot's TDLib [TdApi.File] event. We deliberately do NOT
     * create slots from inbound updates — TDLib emits [TdApi.UpdateFile] for every file
     * in its database, including ones the UI never observes (avatars from chats we
     * scrolled past, thumbs of media we never tapped). Creating a [MutableStateFlow] per
     * such id leaks proportional to TDLib's file table over a long session. Slots are
     * created only by [observe] / [ensure] when a Composable actually mounts; the race
     * where an [TdApi.UpdateFile] arrives before the first observe is closed by
     * [ensureSlow] which calls [TdApi.GetFile] and routes the result through this same
     * reducer (after [slot] has already materialised the entry).
     *
     * Two real-world quirks force the extra logic over a naive `slot.value = newState`:
     *
     *   1. **Out-of-order events.** When a download finishes, TDLib sometimes emits the
     *      completion `UpdateFile` first and a stale "still downloading" one a moment
     *      later (the window where it renames `temp/<id>` → `photos/<…>.jpg`). A naive
     *      reducer would flash the photo on screen and yank it back into a spinner.
     *      Once we see Ready, we refuse to slide back to Downloading/Idle.
     *
     *   2. **Permanent failures with no Failed event.** If `canBeDownloaded=false` and
     *      the file isn't downloading or completed, TDLib won't fire any further update
     *      — it just stops. Without surfacing Failed here the UI sat on a 0% spinner
     *      forever for expired stickers / restricted media.
     */
    private fun applyFileEvent(file: TdApi.File) {
        // No slot → no observer → drop. Callers that need to seed state for a brand-new
        // fileId (e.g. ensureSlow after GetFile) must call slot() first; the inbound
        // UpdateFile collector deliberately does not.
        val s = states[file.id] ?: return
        val incoming = file.toMediaState()
        val previous = s.value
        val merged = when {
            previous is MediaState.Ready && incoming !is MediaState.Ready -> previous
            else -> incoming
        }
        // Update the stall tracker BEFORE the throttle so a watchdog tick that lands
        // between two throttled bursts still sees that bytes are flowing. Real progress
        // resets the retry counter — without this, a download that survives one
        // watchdog reissue and then stalls again later wouldn't get the full retry
        // budget the second time around.
        if (merged is MediaState.Downloading) {
            val downloaded = file.local.downloadedSize
            val prev = stallTrack[file.id]
            if (prev == null || prev.bytes != downloaded) {
                stallTrack[file.id] = StallTrack(downloaded, System.currentTimeMillis(), retries = 0)
            }
        } else {
            stallTrack.remove(file.id)
        }
        // Throttle Downloading→Downloading bursts to PROGRESS_MIN_INTERVAL_MS. Terminal
        // transitions (any state involving Idle/Ready/Failed) always pass through. The
        // 100% emit is also exempt — it's our last chance to show "complete, just waiting
        // on rename" before Ready arrives, and dropping it makes the bar look frozen at 99%.
        if (previous is MediaState.Downloading && merged is MediaState.Downloading
            && merged.progress < 1f) {
            val now = System.currentTimeMillis()
            val last = lastProgressEmitMs[file.id] ?: 0L
            if (now - last < PROGRESS_MIN_INTERVAL_MS) return
            lastProgressEmitMs[file.id] = now
        }
        s.value = merged
        if (merged is MediaState.Ready || merged is MediaState.Failed) {
            activePriority.remove(file.id)
            lastProgressEmitMs.remove(file.id)
            stallTrack.remove(file.id)
        }
    }

    /**
     * Per-tick walk of active downloads. For each fileId still in [stallTrack], if the
     * downloadedSize hasn't changed for [STALL_THRESHOLD_MS] we reissue [TdApi.DownloadFile]
     * at the priority TDLib was already running it at — which TDLib treats as "wake this
     * up" rather than "start a new download" (priority field is what triggers the kick).
     * After [MAX_STALL_RETRIES] failed reissues we give up and mark the slot Failed so the
     * UI can surface "tap to retry" instead of an indefinite spinner.
     */
    private suspend fun checkStalled() {
        val now = System.currentTimeMillis()
        // Snapshot to a list — we mutate stallTrack inside the loop.
        val snapshot = stallTrack.entries.toList()
        for ((fileId, track) in snapshot) {
            if (now - track.changedAt < STALL_THRESHOLD_MS) continue
            val slot = states[fileId] ?: run {
                stallTrack.remove(fileId)
                continue
            }
            if (slot.value !is MediaState.Downloading) {
                stallTrack.remove(fileId)
                continue
            }
            // Re-read the live tracker — between the snapshot above and now, an
            // applyFileEvent could have delivered real bytes and reset retries. Without
            // this guard a watchdog tick could overwrite a healthy Downloading state
            // with Failed in the millisecond window where the snapshot was already
            // stale. Same idea for the reissue path: only act if the snapshot is still
            // the canonical state.
            val live = stallTrack[fileId] ?: continue
            if (live.bytes != track.bytes || live.changedAt != track.changedAt) continue
            if (track.retries >= MAX_STALL_RETRIES) {
                Log.w(TAG, "stall watchdog: giving up on $fileId after ${track.retries} retries")
                slot.value = MediaState.Failed("завантаження зависло")
                stallTrack.remove(fileId)
                activePriority.remove(fileId)
                continue
            }
            val priority = activePriority[fileId] ?: DownloadPriority.VisibleMedia.tdValue
            try {
                td.send(TdApi.DownloadFile(fileId, priority, 0, 0, /* synchronous */ false))
                // compute() so we don't overwrite a fresh tracker that applyFileEvent
                // installed concurrently — only bump retries off the snapshot we acted on.
                stallTrack.compute(fileId) { _, current ->
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
            if (current !is MediaState.Downloading) {
                val file = td.send(TdApi.GetFile(fileId))
                // Route through the same reducer the UpdateFile collector uses — keeps
                // the Ready-stick / Failed-on-not-downloadable invariants in one place.
                applyFileEvent(file)
                if (slot(fileId).value is MediaState.Ready) return
            }
            td.send(TdApi.DownloadFile(fileId, priority.tdValue, 0, 0, /* synchronous */ false))
            activePriority[fileId] = priority.tdValue
            // Seed the stall tracker so the watchdog has something to compare against
            // even if the very first UpdateFile is dropped (SharedFlow buffer overflow,
            // or it lands before our subscriber is wired up after a hot restart).
            stallTrack.putIfAbsent(fileId, StallTrack(0L, System.currentTimeMillis(), 0))
        } catch (t: Throwable) {
            // A Composable leaving composition cancels its LaunchedEffect — that surfaces
            // here as LeftCompositionCancellationException (a CancellationException). Don't
            // log it as a failure or mark the slot Failed; the user just scrolled past.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "ensure($fileId, ${priority.name}) failed", t)
            slot(fileId).value = MediaState.Failed(t.message ?: "download failed")
            activePriority.remove(fileId)
            stallTrack.remove(fileId)
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
        scope.launch(ioDispatcher) {
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ false)) }
            activePriority.remove(fileId)
            stallTrack.remove(fileId)
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
        stallTrack.remove(fileId)
        ensure(fileId, priority)
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
    fun cancelIfPendingAsync(fileId: Int) {
        val job = scope.launch(ioDispatcher) {
            delay(CANCEL_DEBOUNCE_MS)
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ true)) }
            activePriority.remove(fileId)
            stallTrack.remove(fileId)
            pendingCancels.remove(fileId)
        }
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
        // Cadence while no downloads are in flight. The loop still wakes (so a freshly
        // queued download isn't missed for too long), but at this rate the wake-up cost
        // is negligible — well under one wake per second of foreground time.
        const val IDLE_TICK_MS = 30_000L
        // No bytes for this long → reissue. Below ~6s false-positives on slow 3G;
        // above ~15s the user is already convinced the app is broken. 10s is the
        // sweet spot used by the official Telegram Android client.
        const val STALL_THRESHOLD_MS = 10_000L
        // After two reissues with no progress we surface Failed. A third reissue
        // is almost always thrashing against a server-side problem (file gone,
        // DC unreachable) or a hard offline state we can't fix from the client.
        const val MAX_STALL_RETRIES = 2
        // Window during which a re-mount can rescue a queued cancel. 250ms covers a
        // LazyColumn dispose-and-remount cycle (one frame at 60fps + some slack)
        // without keeping cancelled downloads pinned long enough for a real
        // navigation-away to feel laggy.
        const val CANCEL_DEBOUNCE_MS = 250L
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
    val progress = if (resolvedTotal > 0) {
        (local.downloadedSize.toFloat() / resolvedTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f
    return MediaState.Downloading(
        progress = progress,
        downloadedBytes = local.downloadedSize,
        totalBytes = resolvedTotal,
    )
}
