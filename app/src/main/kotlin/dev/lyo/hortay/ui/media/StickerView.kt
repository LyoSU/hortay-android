package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.StickerFormat
import dev.lyo.hortay.data.TdMedia

/**
 * Renders a Telegram sticker by format:
 *
 *   • [StickerFormat.Webp] → static (or animated) WEBP via Coil. Coil 3 decodes both
 *     transparent WEBPs and animated WEBPs natively; the latter is rare in practice but
 *     when present, plays at the WEBP's intrinsic frame rate without extra plumbing.
 *   • [StickerFormat.Tgs]  → Lottie. See [LottieStickerView] for the safe-gunzip pipeline.
 *   • [StickerFormat.Webm] → ExoPlayer-backed silent looper. See [WebmStickerPlayer].
 *
 * For all formats, [thumb] is rendered first as an instant placeholder. TDLib delivers
 * the thumbnail synchronously with the sticker descriptor, so this gives the user a
 * still preview within milliseconds even on cold cache — bridging the gap until the
 * full TGS/WebM file lands and the animation begins.
 */
@Composable
fun StickerView(
    media: TdMedia,
    thumb: TdMedia?,
    format: StickerFormat,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    iterate: Boolean = true,
    repaintColor: Color? = null,
) {
    Box(modifier = modifier) {
        when (format) {
            StickerFormat.Webp -> {
                // Static WEBP is the simplest path — Coil decodes it directly. We skip the
                // explicit progress overlay (the surrounding sticker box is small and a
                // full progress dial would be visual noise; the thumb already conveys
                // "loading" via the gradual fade-up of detail).
                TdMediaImage(
                    media = media,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
            StickerFormat.Tgs -> LottieStickerView(
                fileId = media.fileId,
                thumb = thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                priority = priority,
                iterate = iterate,
                repaintColor = repaintColor,
            )
            StickerFormat.Webm -> WebmStickerPlayer(
                fileId = media.fileId,
                thumb = thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                priority = priority,
            )
        }
    }
}
