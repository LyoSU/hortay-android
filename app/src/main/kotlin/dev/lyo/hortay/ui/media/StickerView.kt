package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
 * Visual ladder while content is in flight:
 *
 *   1. **Vector outline silhouette** — fetched via [StickerOutlineStore] from TDLib's
 *      offline `GetStickerOutlineSvgPath`. Paints in microseconds, matches the sticker's
 *      actual shape, and reads as "this specific sticker is about to load" instead of
 *      an empty box. Doesn't fire in guest/web mode (no TDLib fileId).
 *   2. **Rounded-square fallback** — when TDLib has no outline (`404` / empty SVG /
 *      parse failure) or in guest/web mode, paint a soft `surfaceContainerHigh` square
 *      clipped to `shapes.small`. The user always sees *something* immediately.
 *   3. **TDLib thumbnail** — the static WEBP/PNG preview that ships in the sticker
 *      descriptor. [LottieStickerView] / [WebmStickerPlayer] underlay this themselves;
 *      the static [StickerFormat.Webp] path doesn't need a separate thumb step (the
 *      WEBP itself is the first paint).
 *   4. **Full sticker** — animated Lottie / WebM / static WEBP.
 *
 * The skeleton (outline OR fallback square) lives in an overlay layer on TOP of the
 * content. As soon as the relevant "first paint" media file flips to
 * [dev.lyo.hortay.data.MediaState.Ready] the overlay fades via
 * `MotionScheme.defaultEffectsSpec` and unmounts once alpha approaches zero — Telegram-
 * Android-style hand-off rather than a permanent translucent chip behind transparent
 * sticker edges (an underlay would bleed through transparent zones of the loaded
 * sticker; see [CustomEmojiInlineView] for the same overlay-vs-underlay rationale).
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

        StickerSkeletonOverlay(
            media = media,
            thumb = thumb,
            format = format,
            priority = priority,
        )
    }
}

/**
 * In-flight skeleton (outline silhouette or rounded-square fallback) painted on TOP of
 * the sticker stack until the first user-visible file is [dev.lyo.hortay.data.MediaState.Ready].
 *
 * **Why the binding here duplicates the inner renderer's binding.** Each sticker
 * format's renderer ([TdMediaImage] / [LottieStickerView] / [WebmStickerPlayer])
 * already calls [rememberMediaBinding] internally for the same file. Adding a second
 * observer here is idempotent at the [dev.lyo.hortay.data.MediaCache] layer: `ensure`
 * compares priority before re-issuing, and `cancelDeferred` is observer-count-gated
 * so the cancel only fires once both observers have unmounted. This costs one
 * StateFlow subscription per visible sticker — negligible — and lets the skeleton
 * gate be observe-only at the API level without threading a new "isReady" prop down
 * through every format-specific Composable. Same pattern [CustomEmojiInlineView]
 * uses for its placeholder disc.
 */
@Composable
private fun StickerSkeletonOverlay(
    media: TdMedia,
    thumb: TdMedia?,
    format: StickerFormat,
    priority: DownloadPriority,
) {
    // First-paint file decides when the skeleton can hide:
    //   • Webp: the playback file IS the first paint (Coil decodes the static WEBP
    //     directly), so wait for media.fileId.
    //   • Tgs / Webm: the static thumb paints first (LottieStickerView /
    //     WebmStickerPlayer underlay it while the .tgs/.webm download), so wait for
    //     thumb.fileId. When the thumb is missing (rare — TDLib usually ships one)
    //     fall back to the playback file id so the skeleton can still hide once the
    //     animation lands.
    val firstPaintFileId: Int? = when (format) {
        StickerFormat.Webp -> media.fileId
        StickerFormat.Tgs, StickerFormat.Webm -> thumb?.fileId ?: media.fileId
    }
    val paintBinding = rememberMediaBinding(fileId = firstPaintFileId, priority = priority)
    val skeletonAlpha by animateFloatAsState(
        targetValue = if (paintBinding.isReady) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sticker-skeleton-alpha",
    )

    // Vector outline silhouette: TDLib-side, offline, JNI-microseconds. Only attempt
    // a fetch when we have a sticker fileId — guest/web mode (fileId == null) skips
    // the lookup entirely and falls back to the rounded-square branch below.
    val outlineStore = LocalStickerOutline.current
    var outline by remember(media.fileId) { mutableStateOf<Path?>(null) }
    LaunchedEffect(media.fileId) {
        val id = media.fileId ?: return@LaunchedEffect
        outline = outlineStore.load(id)
    }

    if (skeletonAlpha <= 0.005f) return

    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val fallbackShape = MaterialTheme.shapes.small
    val currentOutline = outline
    val srcWidth = media.width.coerceAtLeast(1)
    val srcHeight = media.height.coerceAtLeast(1)

    if (currentOutline != null) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = skeletonAlpha },
        ) {
            // TDLib outline coords live in the sticker's native pixel space (e.g.
            // 512×384 for a 512×384 sticker). Scale anisotropically so the silhouette
            // fills the bounded box `stickerBoxModifier` allocates — that modifier
            // already preserves aspect, so sx == sy in practice, but we still keep
            // the two factors independent in case TDLib ever normalises the path to
            // a square 512×512 viewport (then the per-axis scale picks up the
            // letterbox automatically without us second-guessing the spec).
            val sx = size.width / srcWidth
            val sy = size.height / srcHeight
            scale(sx, sy, pivot = Offset.Zero) {
                drawPath(currentOutline, placeholderColor)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = skeletonAlpha }
                .clip(fallbackShape)
                .background(placeholderColor),
        )
    }
}
