package dev.lyo.telread.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Decodes and renders a TDLib `Minithumbnail.data` payload (~40×40 inline JPEG, base64 inside
 * the protocol) directly. No file download is involved — these bytes ship inside the very same
 * `UpdateNewMessage` / `GetChat` / `GetUser` response that gave us the post or sender.
 *
 * Used for avatars and any other thumbnail surface where the minithumb is "good enough" — it
 * removes per-item `GetFile` + `DownloadFile` traffic that previously fired on every scroll.
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
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.fillMaxSize(),
    )
}
