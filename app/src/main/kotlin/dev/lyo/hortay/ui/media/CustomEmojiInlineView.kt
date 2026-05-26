package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.graphicsLayer
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
    /**
     * Optional pre-resolved sticker. When supplied (typical FormattedTextRenderer
     * call site: one collector + one map lookup at the parent, sticker pushed down
     * to every inline slot), this composable skips its own
     * [androidx.compose.runtime.collectAsState] + [androidx.compose.runtime.derivedStateOf]
     * pair. A 30-inline-emoji post becomes 1 Flow collector instead of 30 — the dominant
     * source of UI-thread overhead under scroll, measured via thread sampling.
     *
     * Callers that don't have a parent-level resolver (reaction chips on a PostCard,
     * one-off uses elsewhere) leave this null and pay the per-call collector cost; at
     * 1–3 instances per surface that's fine.
     */
    preResolvedSticker: CustomEmojiSticker? = null,
) {
    val repo = LocalCustomEmoji.current

    // Self-resolve path: only mount the Flow collector + derived state when the
    // caller hasn't already done it for us. Skipping this for the pre-resolved case
    // is the whole point of [preResolvedSticker] — no fallback "in case the parent
    // forgot", or we'd negate the savings.
    val sticker: CustomEmojiSticker? = if (preResolvedSticker != null) {
        preResolvedSticker
    } else {
        // Hint the repository so the resolver batches us in. Idempotent for already-
        // resolved ids — no TDLib call is made on a hit.
        LaunchedEffect(customEmojiId) { repo.request(listOf(customEmojiId)) }
        val storeState = repo.stickers.collectAsStateWithLifecycle()
        val resolvedState = remember(customEmojiId) {
            derivedStateOf { storeState.value[customEmojiId] }
        }
        resolvedState.value
    }

    // First fileId that actually has to be ready before SOMETHING paints in the
    // sticker box — i.e. the file the user perceives as "the emoji loading":
    //   • Tgs: the static thumb if TDLib gave us one (LottieStickerView underlays
    //     it while .tgs streams), else the .tgs media itself.
    //   • Webp: the WEBP image at media.fileId.
    //   • Webm: the static thumb at inline size; if animateAlways is on (focused
    //     picker context) and there's no thumb, fall back to the .webm media
    //     so the placeholder waits on the actual playback file rather than
    //     hiding before WebmStickerPlayer has a frame to draw.
    // Web mode passes null fileIds and uses the remoteUrl chain instead — there
    // we trust Coil / LottieUrlStore for their own loading visuals and let the
    // metadata-resolution placeholder be the only one we paint.
    // First fileId that actually has to be ready before SOMETHING paints in the
    // sticker box.
    val firstVisibleFileId: Int? = sticker?.let {
        when (it.format) {
            StickerFormat.Tgs -> it.thumb?.fileId ?: it.media.fileId
            StickerFormat.Webp -> it.media.fileId
            StickerFormat.Webm -> {
                val canAnimate = animateAlways && it.media.fileId != null
                if (canAnimate) it.thumb?.fileId ?: it.media.fileId
                else it.thumb?.fileId
            }
        }
    }
    val binding = rememberMediaBinding(fileId = firstVisibleFileId, priority = priority)

    val contentReady = when {
        sticker == null -> false
        sticker.format == StickerFormat.Webm &&
            sticker.thumb == null &&
            !(animateAlways && sticker.media.fileId != null) -> false
        firstVisibleFileId == null -> true
        else -> binding.isReady
    }
    val needsPlaceholder = !contentReady

    Box(modifier = modifier) {
        if (sticker == null) {
            // Pre-resolution placeholder: a translucent grey disc, undersized
            // (66% of the inline placeholder) so adjacent emojis in a run
            // don't merge into a continuous grey strip and centred so the
            // bead reads as a discrete "loading" pip.
            PlaceholderDisc()
            return@Box
        }

        val repaint = if (sticker.needsRepainting) tintColor else null

        when (sticker.format) {
            // TGS routes through [InlineCustomEmojiRenderer] (NOT [LottieStickerView])
            // for inline-emoji sizing. The renderer joins a shared playback session
            // keyed by customEmojiId in [CustomEmojiAnimator], so N repeats of the
            // same emoji in a post share one [com.airbnb.lottie.LottieDrawable] + one
            // progress state + one Choreographer tick. Saves CPU/battery proportionally
            // to the repeat count: a post with 30 copies of the same TGS emoji pays
            // roughly 1/30 of the per-frame raster cost of the naive
            // [LottieStickerView] path. Full-size stickers (StickerView) keep using
            // [LottieStickerView] because there's at most 1–2 of them on screen and
            // they want native composition fps.
            //
            // `remoteUrl` path: web (anonymous) mode where we have a URL but no TDLib
            // fileId. InlineCustomEmojiRenderer routes through [LottieUrlStore] in
            // that case, fetching the .tgs (or pre-decompressed JSON) bytes via the
            // shared OkHttp client. TDLib mode keeps using fileId.
            // TGS animation routes through [InlineCustomEmojiRenderer] — shared
            // [com.airbnb.lottie.LottieDrawable] per (id, fps) in [CustomEmojiAnimator],
            // background-thread rasterisation, double-buffered blits. See animator KDoc
            // for the full rationale.
            StickerFormat.Tgs -> InlineCustomEmojiRenderer(
                customEmojiId = customEmojiId,
                fileId = sticker.media.fileId,
                remoteUrl = sticker.media.takeIf { it.fileId == null }?.remoteUrl,
                thumb = sticker.thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                tintColor = repaint,
                fps = CustomEmojiAnimator.Fps.Inline,
                priority = priority,
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
                // Else: no thumb AND no animation path → there is literally
                // nothing to draw for this sticker. The needsPlaceholder
                // overlay below keeps the loading disc visible so the box
                // doesn't render as transparent dead space.
            }
        }

        // Overlay placeholder: covers the in-flight TdMediaImage / LottieStickerView
        // until the first visible file lands as Ready. Kept on TOP (not as an
        // underlay) so it doesn't bleed through transparent corners of irregular
        // glyphs once content paints — same rationale the original commit used to
        // justify dropping the underlay variant. The composables underneath stay
        // mounted so they keep driving their MediaCache / LottieUrlStore loads.
        // sticker is guaranteed non-null here — the null branch returned above.
        //
        // Fade the disc OUT (1 - alpha) as content becomes ready instead of unmounting it
        // instantly; the linger gate keeps it composed through the fade. Under reduced
        // motion the alpha snaps, collapsing back to the old instant disappear.
        val discAlpha = rememberRevealAlpha(revealed = contentReady)
        val keepDisc = rememberPlaceholderLinger(contentReady, key = firstVisibleFileId)
        if (keepDisc) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - discAlpha }) {
                PlaceholderDisc()
            }
        }
    }
}

@Composable
private fun BoxScope.PlaceholderDisc() {
    Box(
        modifier = Modifier
            .fillMaxSize(0.66f)
            .align(Alignment.Center)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
    )
}
