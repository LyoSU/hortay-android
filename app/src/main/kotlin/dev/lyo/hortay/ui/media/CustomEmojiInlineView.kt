package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.CustomEmojiSticker
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.StickerFormat

/**
 * Compact renderer for a Telegram `custom_emoji_id`. Used in two places:
 *
 *   • Inline inside [androidx.compose.foundation.text.BasicText] via
 *     [androidx.compose.ui.text.InlineTextContent] — sticker-emoji embedded in formatted
 *     post text.
 *   • Inside reaction chips when the bucket is a custom-emoji reaction.
 *
 * Battery-conscious by default: at the small sizes where this view is used (≤ 28dp),
 * driving a 30 fps WebM decoder per emoji is wasteful. So we render the static
 * [CustomEmojiSticker.thumb] for WebM and static-WEBP custom emojis, and only run a
 * full Lottie animation for TGS (Lottie is GPU-cheap even at thumbnail size). Pass
 * `animateAlways = true` in the rare cases where animated playback is wanted (e.g. a
 * focused selection state).
 *
 * `tintFromText`: if the sticker is monochrome (`needsRepainting`), it's tinted with
 * [tintColor] so the glyph reads on top of any surface — same way the official Telegram
 * client renders monochrome emoji-status icons.
 */
@Composable
fun CustomEmojiInlineView(
    customEmojiId: Long,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tintColor: Color? = null,
    animateAlways: Boolean = false,
    priority: DownloadPriority = DownloadPriority.Avatar,
) {
    val repo = LocalCustomEmoji.current

    // Hint the repository so the resolver batches us in. Idempotent for already-resolved
    // ids — no TDLib call is made on a hit.
    LaunchedEffect(customEmojiId) { repo.request(listOf(customEmojiId)) }

    val store by repo.stickers.collectAsStateWithLifecycle()
    val sticker: CustomEmojiSticker? = remember(store, customEmojiId) { store[customEmojiId] }

    Box(modifier = modifier) {
        if (sticker == null) {
            // Pre-resolution: nothing to draw. The InlineTextContent placeholder reserves
            // the line height; once the resolver lands the sticker, the layout doesn't
            // shift because the placeholder size is fixed by the caller.
            return@Box
        }

        val repaint = if (sticker.needsRepainting) tintColor else null

        when (sticker.format) {
            StickerFormat.Tgs -> LottieStickerView(
                fileId = sticker.media.fileId,
                // remoteUrl path: web (anonymous) mode where we have a URL but no
                // TDLib fileId. LottieStickerView routes through LottieUrlStore in
                // that case, fetching the .tgs (or pre-decompressed JSON) bytes
                // via the shared OkHttp client. TDLib mode keeps using fileId.
                remoteUrl = sticker.media.takeIf { it.fileId == null }?.remoteUrl,
                thumb = sticker.thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                priority = priority,
                iterate = true,
                repaintColor = repaint,
            )
            StickerFormat.Webp -> {
                // Static WEBP is the cheap path — render directly. (Most custom emojis
                // ship as WEBP; only the animated set uses TGS/Webm.)
                TdMediaImage(
                    media = sticker.media,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
            StickerFormat.Webm -> {
                // For WebM at inline size we deliberately render the static thumbnail
                // by default — running an ExoPlayer instance per emoji at 24dp hurts
                // battery for negligible visual gain. animateAlways=true is the
                // escape hatch for a focused picker UI. In addition, web (anonymous)
                // mode posts WebM URLs directly: we keep that branch animated since
                // there's no static fallback CDN-side and the user explicitly opted
                // into "show me the actual emoji" by signing in to the guest feed.
                val webRemoteUrl = sticker.media.takeIf { it.fileId == null }?.remoteUrl
                val canAnimateTdlib = animateAlways && sticker.media.fileId != null
                val canAnimateWeb = webRemoteUrl != null
                if (canAnimateTdlib || canAnimateWeb) {
                    WebmStickerPlayer(
                        fileId = sticker.media.fileId,
                        remoteUrl = webRemoteUrl,
                        thumb = sticker.thumb,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        priority = priority,
                    )
                } else if (sticker.thumb != null) {
                    TdMediaImage(
                        media = sticker.thumb,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        placeholderColor = null,
                        showProgress = false,
                        priority = priority,
                    )
                }
            }
        }
    }
}
