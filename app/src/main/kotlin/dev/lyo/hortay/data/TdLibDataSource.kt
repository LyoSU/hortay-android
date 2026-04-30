package dev.lyo.hortay.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Media3 [DataSource] that streams a TDLib-managed file. Lets ExoPlayer start
 * playback the moment a usable prefix is on disk — typically 1-3 seconds in,
 * instead of waiting for the whole video to download. This is what the official
 * Telegram clients do; the difference between "blank for 30 seconds" and "video
 * starts immediately and buffers ahead" is exactly the difference between
 * tolerating downloads serially vs. streaming a partial.
 *
 * URI scheme: `tdlib://<fileId>`
 *
 * Lifecycle:
 *  1. ExoPlayer calls [open]. We send `DownloadFile(fileId, priority, offset, …)`
 *     to TDLib, then suspend until [TdApi.UpdateFile] reports a contiguous prefix
 *     of at least [INITIAL_PREFETCH_BYTES] from our requested offset (or the file
 *     is already complete). Once TDLib materialises a `local.path`, we open it
 *     for read.
 *  2. ExoPlayer calls [read] repeatedly. Each call reads from a [RandomAccessFile]
 *     positioned at our current cursor. If the cursor approaches the end of the
 *     contiguous prefix, we suspend on the next `UpdateFile` until more bytes
 *     land. EOF is signalled when bytesRemaining hits 0 (we know the total) or
 *     when TDLib reports completion at a position the cursor caught up to.
 *  3. ExoPlayer calls [close] in `release()`/error paths. We interrupt any
 *     blocking await on the read thread, close the RAF, and detach.
 *
 * Single-use: ExoPlayer creates a fresh DataSource per playback (or per range
 * read for seeks); the [Factory] returns a new instance each call.
 *
 * Thread safety: ExoPlayer drives [open]/[read]/[close] from the same loader
 * thread; [close] may be called from a different thread to abort. We bridge to
 * coroutines via [runBlocking] but track the blocked thread so [close] can
 * interrupt and bust us out of a stuck await.
 */
@UnstableApi
class TdLibDataSource private constructor(
    private val td: TdSender,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val td: TdSender) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdLibDataSource(td)
    }

    private val updates: SharedFlow<TdApi.Update> = td.updates

    private var dataSpec: DataSpec? = null
    private var fileId: Int = -1
    private var raf: RandomAccessFile? = null
    private var localPath: String = ""
    private var totalSize: Long = 0L
    /** Absolute cursor inside the file. */
    private var readPosition: Long = 0L
    /** Bytes still to read in the requested range (or [C.LENGTH_UNSET] if open-ended). */
    private var bytesRemaining: Long = 0L
    /**
     * Last contiguous prefix end we observed: `downloadOffset + downloadedPrefixSize`.
     * Updated as UpdateFile events arrive while we're inside [waitForBytes].
     */
    @Volatile
    private var availableEnd: Long = 0L
    @Volatile
    private var downloadingCompleted: Boolean = false

    /**
     * The thread currently parked inside a [runBlocking] await. Set immediately
     * before suspending in [open] / [read] and cleared once the suspend returns.
     * [close] interrupts this thread so a player teardown doesn't hang waiting
     * on bytes that will never arrive.
     */
    @Volatile
    private var blockedThread: Thread? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        this.dataSpec = dataSpec
        fileId = parseFileId(dataSpec.uri)
        readPosition = dataSpec.position
        availableEnd = 0L
        downloadingCompleted = false

        // Tell TDLib to start (or shift) the download at our requested byte offset. limit=0
        // means "until end". priority 32 = Foreground; the active video is the user's focus.
        val initial = sendBlocking {
            td.send(TdApi.DownloadFile(fileId, FOREGROUND_PRIORITY, readPosition, 0L, /* synchronous */ false))
        }
        ingest(initial)

        // Block until either a) we have a usable prefix, or b) the file is complete.
        // Without this guard ExoPlayer's first read() races the download and trips
        // its own end-of-stream guard, aborting playback before bytes flow.
        val ready = waitForBytes(readPosition + INITIAL_PREFETCH_BYTES)
        ingest(ready)

        if (localPath.isEmpty()) {
            throw IOException("TDLib produced no local path for fileId=$fileId")
        }

        raf = RandomAccessFile(localPath, "r").apply { seek(readPosition) }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> (totalSize - readPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Wait until at least one more byte is available past our cursor — or until
        // the download completes and our cursor is at EOF.
        val needUpTo = readPosition + 1
        if (availableEnd < needUpTo && !downloadingCompleted) {
            try {
                waitForBytes(needUpTo)
            } catch (e: IOException) {
                throw e
            }
        }
        if (downloadingCompleted && totalSize > 0 && readPosition >= totalSize) {
            return C.RESULT_END_OF_INPUT
        }

        val raf = raf ?: throw IOException("DataSource closed")
        val capByRange =
            if (bytesRemaining == C.LENGTH_UNSET.toLong()) length.toLong() else minOf(length.toLong(), bytesRemaining)
        // Never read past the contiguous prefix — TDLib could have allocated a sparse file
        // (zeros past the prefix); a read into that region returns garbage instead of
        // blocking, breaking ExoPlayer's container parser.
        val capByPrefix = (availableEnd - readPosition).coerceAtLeast(0L)
        val toRead = minOf(capByRange, capByPrefix).toInt()
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        raf.seek(readPosition)
        val n = raf.read(buffer, offset, toRead)
        if (n <= 0) {
            // RAF can return -1 if the file was truncated underneath us — surface as EOF.
            return C.RESULT_END_OF_INPUT
        }
        readPosition += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        // Interrupt any in-flight runBlocking before we tear the RAF — without this
        // a stuck await holds the loader thread and ExoPlayer's release() blocks too.
        blockedThread?.interrupt()
        try {
            raf?.close()
        } catch (_: Throwable) {
            // Closing a RAF is best-effort; an exception here would mask the real reason
            // the player asked us to close.
        }
        raf = null
        if (dataSpec != null) {
            transferEnded()
            dataSpec = null
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    /**
     * Pull whatever the latest TDLib state is for [fileId] into our local view. Called
     * after the synchronous DownloadFile reply, and inside the wait loop on every
     * UpdateFile.
     */
    private fun ingest(file: TdApi.File) {
        if (file.id != fileId) return
        val resolvedTotal = when {
            file.size > 0 -> file.size
            file.expectedSize > 0 -> file.expectedSize
            else -> 0L
        }
        if (resolvedTotal > 0 && totalSize <= 0) totalSize = resolvedTotal
        // path arrives lazily — sometimes the first DownloadFile response is empty until
        // TDLib stages a file in tdlib-files/. Keep reading subsequent updates until
        // we have a path AND a usable prefix.
        if (localPath.isEmpty() && file.local.path.orEmpty().isNotEmpty()) {
            localPath = file.local.path
        }
        availableEnd = if (file.local.isDownloadingCompleted) {
            (totalSize.takeIf { it > 0 } ?: (file.local.downloadOffset + file.local.downloadedPrefixSize))
        } else {
            file.local.downloadOffset + file.local.downloadedPrefixSize
        }
        downloadingCompleted = file.local.isDownloadingCompleted
    }

    /**
     * Suspend the calling thread until at least one of the following is true:
     *  - [availableEnd] >= [target]
     *  - the download has fully completed (no further updates expected)
     *  - the underlying coroutine was cancelled (we surface that as IOException, which
     *    Media3 treats as a recoverable load error rather than a fatal player crash).
     *
     * Subscribes to the UpdateFile stream and ALSO re-polls TDLib synchronously via
     * [TdApi.GetFile] right after subscribing. This closes a race where TDLib emits
     * the canonical "downloading completed" update *before* our subscriber attaches —
     * common for small files (typical GIFs <2 MB) which TDLib may finish in a single
     * burst right after DownloadFile returns. Without the post-subscription poll the
     * UpdateFile would never reach us (SharedFlow replay=0) and we'd hang forever
     * waiting for the next byte that already landed.
     */
    private fun waitForBytes(target: Long): TdApi.File {
        if (availableEnd >= target || downloadingCompleted) {
            return sendBlocking { td.send(TdApi.GetFile(fileId)) }
        }
        return sendBlocking {
            updates
                .filterIsInstance<TdApi.UpdateFile>()
                .filter { it.file.id == fileId }
                .map { it.file }
                .onStart {
                    // Race-closing poll. Runs *after* the SharedFlow subscription is
                    // wired up, so any UpdateFile that lands between this poll and the
                    // first real upstream emission is also delivered (no duplicate
                    // observation hazard — ingest() is idempotent).
                    emit(td.send(TdApi.GetFile(fileId)))
                }
                .map { file -> ingest(file); file }
                .first { availableEnd >= target || downloadingCompleted }
        }
    }

    /**
     * Run a suspending block on the loader thread, registering the thread for interrupt
     * so [close] can break us out of a deadlocked wait. We translate cancellation into
     * IOException — Media3 retries an IOException, which is the right behaviour for
     * "ExoPlayer closed the player while we were buffering".
     */
    private inline fun <T> sendBlocking(crossinline block: suspend () -> T): T {
        blockedThread = Thread.currentThread()
        return try {
            runBlocking { block() }
        } catch (ie: InterruptedException) {
            // Re-set interrupt flag for any upstream caller that also checks it.
            Thread.currentThread().interrupt()
            throw IOException("TdLibDataSource interrupted while waiting on fileId=$fileId", ie)
        } catch (ce: CancellationException) {
            throw IOException("TdLibDataSource cancelled while waiting on fileId=$fileId", ce)
        } catch (t: Throwable) {
            // Anything else — a TdException, a JNI failure — is also an I/O fault from
            // ExoPlayer's perspective.
            if (t is IOException) throw t
            throw IOException("TdLibDataSource failed for fileId=$fileId", t)
        } finally {
            blockedThread = null
        }
    }

    private fun parseFileId(uri: Uri): Int {
        // We accept both `tdlib://12345` (host) and `tdlib:12345` (opaque) forms — the
        // latter is what MediaItem.fromUri produces from a non-hierarchical string.
        val candidate = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")
        return candidate?.toIntOrNull()
            ?: throw IOException("invalid TdLibDataSource URI: $uri")
    }

    private companion object {
        const val TAG = "TdLibDataSource"
        // Foreground priority for the active playback file. Matches DownloadPriority.Foreground
        // in MediaCache; we don't hard-depend on that enum from the DataSource layer to avoid
        // a circular dependency, but the values must stay aligned.
        const val FOREGROUND_PRIORITY = 32
        // Bytes to buffer ahead before letting ExoPlayer start probing the container.
        // 256 KB is enough for the moov atom of a typical mp4 to land first, which lets
        // Media3 figure out duration/codecs without seeking backwards. Telegram-Android
        // uses a similar threshold (~512 KB) for HLS, but for direct mp4 streams 256 KB
        // is sufficient and gets the user past the spinner faster.
        const val INITIAL_PREFETCH_BYTES = 256L * 1024L
    }
}
