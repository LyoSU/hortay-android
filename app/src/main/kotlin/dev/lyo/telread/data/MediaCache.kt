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
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val states = ConcurrentHashMap<Int, MutableStateFlow<MediaState>>()
    private val activePriority = ConcurrentHashMap<Int, Int>()

    init {
        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            // Always seed/update the slot — even if no observer existed yet — so when a
            // Composable later mounts and calls ensure, GetFile and ongoing UpdateFile both
            // converge on the same slot.
            .onEach { update -> applyFileEvent(update.file) }
            .launchIn(scope)
    }

    /**
     * Reducer for a single TDLib [TdApi.File] event. Two real-world quirks force the
     * extra logic over a naive `slot.value = newState`:
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
        val incoming = file.toMediaState()
        val s = slot(file.id)
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
    suspend fun ensure(fileId: Int, priority: DownloadPriority = DownloadPriority.VisibleMedia) =
        withContext(ioDispatcher) {
            val current = slot(fileId).value
            if (current is MediaState.Ready) return@withContext

            val currentPriority = activePriority[fileId] ?: 0
            if (current is MediaState.Downloading && currentPriority >= priority.tdValue) {
                return@withContext
            }

            try {
                if (current !is MediaState.Downloading) {
                    val file = td.send(TdApi.GetFile(fileId))
                    slot(fileId).value = file.toMediaState()
                    if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
                        return@withContext
                    }
                }
                td.send(TdApi.DownloadFile(fileId, priority.tdValue, 0, 0, /* synchronous */ false))
                activePriority[fileId] = priority.tdValue
            } catch (t: Throwable) {
                Log.w(TAG, "ensure($fileId, ${priority.name}) failed", t)
                slot(fileId).value = MediaState.Failed(t.message ?: "download failed")
                activePriority.remove(fileId)
            }
        }

    /**
     * Cancel a queued (not-yet-started) download. We pass `onlyIfPending = true` so partial
     * progress is preserved — when the user scrolls back, [ensure] resumes from where TDLib
     * left off. Safe to call even if no download exists.
     */
    suspend fun cancelIfPending(fileId: Int) = withContext(ioDispatcher) {
        runCatching { td.send(TdApi.CancelDownloadFile(fileId, /* onlyIfPending */ true)) }
        activePriority.remove(fileId)
    }

    @Deprecated("Use ensure(fileId, priority)", ReplaceWith("ensure(fileId, priority)"))
    suspend fun ensureDownloaded(fileId: Int) = ensure(fileId, DownloadPriority.VisibleMedia)

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
