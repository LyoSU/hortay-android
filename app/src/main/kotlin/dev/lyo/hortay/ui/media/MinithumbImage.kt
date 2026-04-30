package dev.lyo.hortay.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes and renders a TDLib `Minithumbnail.data` payload (~40×40 inline JPEG, base64 inside
 * the protocol) directly. No file download is involved — these bytes ship inside the very same
 * `UpdateNewMessage` / `GetChat` / `GetUser` response that gave us the post or sender.
 *
 * Used for avatars and any other thumbnail surface where the minithumb is "good enough" — it
 * removes per-item `GetFile` + `DownloadFile` traffic that previously fired on every scroll.
 *
 * **Decode pipeline**: a process-wide [MinithumbCache] holds previously-decoded Bitmaps keyed
 * by ByteArray identity. Cache hits render synchronously (zero main-thread work). Cache misses
 * decode on [Dispatchers.Default] — the parent's letter fallback (e.g. `TdAvatar`) shows
 * through during the ~1 ms decode, so the user never sees a blank square. The previous
 * implementation decoded synchronously on the main thread, costing ~10-20 ms per visible frame
 * during fast scroll across a feed of cards (every avatar = one decode).
 *
 * If decoding fails (corrupt bytes), nothing is rendered, so the parent's letter fallback shows
 * through.
 */
@Composable
fun MinithumbImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(bytes) { mutableStateOf(MinithumbCache.peek(bytes)) }
    LaunchedEffect(bytes) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.Default) { MinithumbCache.getOrDecode(bytes) }
    }
    val bmp = bitmap ?: return
    // Cache the wrapper so we don't allocate a new ImageBitmap on every recomposition.
    val image = remember(bmp) { bmp.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Process-wide LRU cache of decoded TDLib minithumbs. ByteArray identity is the key —
 * TimelinePost / chat-cache snapshots are @Immutable, so the same minithumb payload retains
 * its reference across recompositions, and cache hits are O(1) reference equality.
 *
 * Sized at 256 entries × ~6.4 KB (40×40×4) ≈ 1.6 MB — well under the noise of Coil's bitmap
 * cache, and large enough that LazyColumn scrolling past 100 cards and back never re-decodes.
 */
private object MinithumbCache {
    private const val MAX_ENTRIES = 256
    private val cache = LruCache<ByteArray, Bitmap>(MAX_ENTRIES)

    fun peek(bytes: ByteArray): Bitmap? = cache.get(bytes)

    fun getOrDecode(bytes: ByteArray): Bitmap? {
        cache.get(bytes)?.let { return it }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (decoded != null) cache.put(bytes, decoded)
        return decoded
    }
}
