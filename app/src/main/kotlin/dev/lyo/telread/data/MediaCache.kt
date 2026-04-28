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

    init {
        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            .onEach { update -> states[update.file.id]?.value = update.file.toMediaState() }
            .launchIn(scope)
    }

    fun observe(fileId: Int): StateFlow<MediaState> = slot(fileId).asStateFlow()

    /** Idempotent: safe to call from each Composable that mounts. */
    suspend fun ensureDownloaded(fileId: Int) = withContext(ioDispatcher) {
        if (slot(fileId).value is MediaState.Ready) return@withContext

        try {
            val file = td.send(TdApi.GetFile(fileId))
            slot(fileId).value = file.toMediaState()
            if (!file.local.isDownloadingCompleted) {
                td.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0, 0, /* synchronous */ false))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureDownloaded($fileId) failed", t)
            slot(fileId).value = MediaState.Failed(t.message ?: "download failed")
        }
    }

    private fun slot(fileId: Int): MutableStateFlow<MediaState> =
        states.computeIfAbsent(fileId) { MutableStateFlow(MediaState.Idle) }

    private companion object {
        const val TAG = "MediaCache"
        const val DOWNLOAD_PRIORITY = 16  // 1..32, higher = more urgent
    }
}

sealed interface MediaState {
    data object Idle : MediaState
    data class Downloading(val progress: Float) : MediaState
    data class Ready(val path: String) : MediaState
    data class Failed(val reason: String) : MediaState
}

private fun TdApi.File.toMediaState(): MediaState {
    val localPath = local.path
    if (local.isDownloadingCompleted && !localPath.isNullOrEmpty()) {
        return MediaState.Ready(localPath)
    }
    val totalBytes = if (size > 0) size.toFloat() else expectedSize.toFloat().coerceAtLeast(1f)
    val progress = (local.downloadedSize.toFloat() / totalBytes).coerceIn(0f, 1f)
    return MediaState.Downloading(progress)
}
