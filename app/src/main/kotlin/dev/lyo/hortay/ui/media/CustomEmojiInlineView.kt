package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    // Per-id slice via derivedStateOf: the underlying StateFlow churns whenever ANY
    // emoji resolves, but `derivedStateOf { store[id] }` only propagates when THIS
    // id's entry actually changes — equality check skips identical re-emits, so a
    // 30-emoji viewport recomposes 30× total during a burst, not 30 × 30.
    val storeState = repo.stickers.collectAsStateWithLifecycle()
    val resolvedState = remember(customEmojiId) {
        derivedStateOf { storeState.value[customEmojiId] }
    }
    val sticker: CustomEmojiSticker? = resolvedState.value

    Box(modifier = modifier) {
        if (sticker == null) {
            // Pre-resolution placeholder: a translucent grey disc, undersized
            // (66% of the inline placeholder) so adjacent emojis in a run
            // don't merge into a continuous grey strip and centred so the
            // bead reads as a discrete "loading" pip. The moment the resolver
            // lands the sticker we drop into the format-specific branch
            // below — the circle disappears completely instead of bleeding
            // through the transparent corners of irregular emoji glyphs
            // (which is what made it look like the placeholder lingered).
            Box(
                modifier = Modifier
                    .fillMaxSize(0.66f)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
            )
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
                // WebM custom emojis ALWAYS render as the static WEBP thumb at
                // inline size. Tested approaches and why each was rejected:
                //
                //   1. Animate via raw `WebmStickerPlayer` — t.me/i/emoji serves
                //      WebMs as pre-rendered `yuv420p` (no alpha channel) baked
                //      against `srgb(0,0,0)`. The original sticker carries alpha
                //      as a sidecar VP9 stream via Matroska BlockAdditional which
                //      standard Android `MediaCodec` ignores, so the post card
                //      gets a solid black square wherever the sticker was supposed
                //      to be transparent.
                //
                //   2. Animate + apply a luma-key fragment shader to recover
                //      transparency (`alpha = max(r, g, b)`). Mathematically
                //      equivalent to Telegram Web's `mix-blend-mode: lighten`.
                //      Works visually for stickers made of bright colours but
                //      collapses for any sticker with intentionally dark/black
                //      glyph regions (a black pupil, a navy outline) — those
                //      pixels read as "background" to the shader and disappear
                //      with the actual background.
                //
                //   3. Add `media3-decoder-vp9` to decode the alpha sidecar via
                //      libvpx. Correct visual but ~10 MB of native libs across
                //      arm64 + x86_64 just to animate inline emojis at 24 dp.
                //      Disproportionate cost vs. the WEBP thumb that already
                //      gives the right glyph with proper alpha.
                //
                // The thumb is a single Coil-cached lookup so it appears
                // instantly — no animation, but no artifacts and no delay
                // either. Same visual contract as TDLib mode.
                //
                // `animateAlways = true` keeps the TDLib-mode escape hatch for a
                // focused picker UI where the file genuinely carries alpha (HW
                // VP9-alpha or a libvpx ext build). Guest mode never opts into
                // it because the URL-only sticker has no alpha to recover.
                val canAnimateTdlib = animateAlways && sticker.media.fileId != null
                if (canAnimateTdlib) {
                    WebmStickerPlayer(
                        fileId = sticker.media.fileId,
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
