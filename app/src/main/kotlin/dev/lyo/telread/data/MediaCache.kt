package dev.lyo.telread.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 * Thread-safe by design: state is held in a [ConcurrentHashMap]; mutations are confined to a
 * single update collector running on [ioDispatcher].
 */
class MediaCache(
    private val td: TdClient,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val states = ConcurrentHashMap<Int, MutableStateFlow<MediaState>>()
    private val activePriority = ConcurrentHashMap<Int, Int>()

    init {
        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            // Updates for files we never observed are dropped at the reducer (see
            // applyFileEvent) — TDLib emits UpdateFile for everything in its database,
            // not just things this app rendered.
            .onEach { update -> applyFileEvent(update.file) }
            .launchIn(scope)
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
        val merged = when {
            s.value is MediaState.Ready && incoming !is MediaState.Ready -> s.value
            else -> incoming
        }
        s.value = merged
        if (merged is MediaState.Ready || merged is MediaState.Failed) {
            activePriority.remove(file.id)
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
     */
    suspend fun ensure(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) {
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
        } catch (t: Throwable) {
            // A Composable leaving composition cancels its LaunchedEffect — that surfaces
            // here as LeftCompositionCancellationException (a CancellationException). Don't
            // log it as a failure or mark the slot Failed; the user just scrolled past.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "ensure($fileId, ${priority.name}) failed", t)
            slot(fileId).value = MediaState.Failed(t.message ?: "download failed")
            activePriority.remove(fileId)
        }
    }

    /**
     * Fire-and-forget cancellation. Runs on the cache's own scope so callers don't have
     * to spin up a fresh CoroutineScope just to dispatch a one-shot. Always passes
     * `onlyIfPending = true` so partial progress is preserved — when the user scrolls
     * back, [ensure] resumes from where TDLib left off.
     */
    fun cancelIfPendingAsync(fileId: Int) {
        scope.launch(ioDispatcher) {
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ true)) }
            activePriority.remove(fileId)
        }
    }

    private fun slot(fileId: Int): MutableStateFlow<MediaState> =
        states.computeIfAbsent(fileId) { MutableStateFlow(MediaState.Idle) }

    private companion object {
        const val TAG = "MediaCache"
    }
}

sealed interface MediaState {
    data object Idle : MediaState
    data class Downloading(val progress: Float) : MediaState
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
    val totalBytes = if (size > 0) size.toFloat() else expectedSize.toFloat().coerceAtLeast(1f)
    val progress = (local.downloadedSize.toFloat() / totalBytes).coerceIn(0f, 1f)
    return MediaState.Downloading(progress)
}
