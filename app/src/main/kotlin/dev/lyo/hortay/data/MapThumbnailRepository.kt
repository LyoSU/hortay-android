package dev.lyo.hortay.data

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * One static map picture: a point, a zoom level and the picture's size in TDLib's terms
 * ([width] / [height] before [scale], 16..1024; [scale] 1..3). Also the dedupe key — two
 * cards of the same point at the same measured size share one request.
 */
@Immutable
data class MapThumbnailRequest(
    val latitude: Double,
    val longitude: Double,
    val zoom: Int,
    val width: Int,
    val height: Int,
    val scale: Int,
)

/**
 * Static map pictures for location posts (`getMapThumbnailFile`). Resolves a request to the
 * id of a TDLib file that is already on disk; the UI hands only that finished file to the
 * media pipeline ([MediaCache], via `TdMediaImage`).
 *
 * How TDLib makes the picture (td/telegram/files/FileGenerateManager.cpp,
 * `WebFileDownloadGenerateActor`): the file is *generated* (`#map#zoom#x#y#w#h#scale#`) by ONE
 * `upload.getWebFile(offset = 0, limit = 1 MiB)` to the webfile DC. There are no partial
 * bytes — `downloaded_size` stays 0 until the whole PNG lands — and on a phone that reply has
 * been seen to take from tens of seconds to minutes. Download priority doesn't speed it up:
 * the generator sends its query straight to the network, outside TDLib's download queue;
 * priority only has to be non-zero.
 *
 * Contract, each point load-bearing:
 *  - **Nothing ever cancels a running generation.** `cancelDownloadFile` on a generating file
 *    tears down the generator actor and drops the in-flight `getWebFile` reply, so the next
 *    attempt starts the slow request from zero. That is why the incomplete file never goes to
 *    [MediaCache]: its dispose-time `cancelDeferred` on every scroll-away did exactly that
 *    (its stall-watchdog reissue is a bare `downloadFile`, which TDLib ignores for a file
 *    already generating). It is also why there is no app-side timeout.
 *  - **Completion is read from `updateFile`**, not from the synchronous `downloadFile` reply
 *    alone: [TdClient.send] gives up waiting after its own timeout ([TdClient.SEND_TIMEOUT_CODE])
 *    while TDLib carries on, so a send timeout is "still generating", never a failure.
 *  - **The fetch runs in [AccountSession.scope]** and is shared per [MapThumbnailRequest], so
 *    a card that scrolls away doesn't abort it, scrolling back joins it instead of starting a
 *    second generation, and the picture is ready the moment it completes. After a send timeout the synchronous
 *    `downloadFile` is simply asked again, which TDLib joins to the same pending request.
 *  - **A definite TDLib error** (the synchronous `downloadFile` fails, e.g. "File is too big"
 *    or `LOCATION_INVALID`) resolves to null and is logged under [TAG] with TDLib's message;
 *    the request is forgotten, so a later composition tries again. There is no back-off
 *    that could hide a picture that is merely slow.
 *
 * Logs (tag `MapThumbnails`, size/scale/zoom only, never coordinates): elapsed time when a
 * picture lands, a note when our send gives up waiting, and every TDLib error.
 */
class MapThumbnailRepository(
    private val td: TdSender,
    private val session: AccountSession,
    /** Monotonic clock for the elapsed-time log; injectable because SystemClock is 0 under JVM tests. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val resolved = ConcurrentHashMap<MapThumbnailRequest, Deferred<Int?>>()

    init {
        session.onEnd("mapThumbnails") { resolved.clear() }
    }

    /**
     * TDLib file id of the map picture for [request], suspending until the picture is on disk
     * (however long that takes); null when TDLib refused the request or failed the picture.
     * Cancelling the caller only stops its wait — the fetch carries on for the next caller.
     */
    suspend fun resolve(request: MapThumbnailRequest): Int? {
        val call = resolved.computeIfAbsent(request) { key ->
            session.scope.async(start = CoroutineStart.LAZY) { fetch(key) }
        }
        call.start()
        val fileId = call.await()
        if (fileId == null) resolved.remove(request, call)
        return fileId
    }

    private suspend fun fetch(request: MapThumbnailRequest): Int? {
        val label = "map ${request.width}x${request.height}@${request.scale} z${request.zoom}"
        val file = suspendRunCatching {
            td.send(
                TdApi.GetMapThumbnailFile(
                    TdApi.Location(request.latitude, request.longitude, 0.0),
                    request.zoom,
                    request.width,
                    request.height,
                    request.scale,
                    // Only sets the generated file's owner (and the secret-chat file type) in
                    // FileManager::get_map_thumbnail_file_id; the network request ignores it.
                    0L,
                ),
            )
        }.warnUnlessCancelled(TAG, "getMapThumbnailFile $label").getOrNull() ?: return null
        if (file.isOnDisk()) return file.id
        return awaitGenerated(file.id, label)
    }

    private suspend fun awaitGenerated(fileId: Int, label: String): Int? = coroutineScope {
        val startedAt = clock()
        val outcome = CompletableDeferred<Int?>()
        fun landed(via: String) {
            if (outcome.complete(fileId)) {
                Log.i(TAG, "file $fileId ($label) ready in ${clock() - startedAt}ms ($via)")
            }
        }
        // Subscribed before downloadFile is sent, so a completion can't slip past.
        launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.filterIsInstance<TdApi.UpdateFile>()
                .first { it.file.id == fileId && it.file.isOnDisk() }
            landed("updateFile")
        }
        launch {
            // Re-asking after our own send timeout is safe: a synchronous downloadFile with the
            // same offset/limit only adds a promise to the pending request
            // (FileManager::download_file) and a generating file ignores the repeat
            // (FileManager::run_generate), so the getWebFile in flight is never restarted.
            var waiting = true
            while (waiting) {
                val result = suspendRunCatching {
                    td.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, 0L, 0L, /* synchronous */ true))
                }
                val error = result.exceptionOrNull()
                val timedOut = error is TdClient.TdException && error.code == TdClient.SEND_TIMEOUT_CODE
                when {
                    error == null -> if (result.getOrThrow().isOnDisk()) landed("downloadFile")
                    timedOut -> Log.i(TAG, "file $fileId ($label) still generating after ${clock() - startedAt}ms")
                    else -> {
                        Log.w(TAG, "downloadFile $fileId ($label) failed after ${clock() - startedAt}ms", error)
                        outcome.complete(null)
                    }
                }
                waiting = timedOut
            }
        }
        val id = outcome.await()
        // Stops only our listeners; the TDLib side has finished (or failed) by now.
        coroutineContext.cancelChildren()
        id
    }

    private fun TdApi.File.isOnDisk(): Boolean =
        local.isDownloadingCompleted && !local.path.isNullOrEmpty()

    private companion object {
        const val TAG = "MapThumbnails"

        /** Any non-zero priority starts generation; the generator bypasses the download queue. */
        val DOWNLOAD_PRIORITY = DownloadPriority.VisibleMedia.tdValue
    }
}
