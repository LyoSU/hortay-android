package dev.lyo.hortay.ui.media

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
 * Both animated formats play inline at thumbnail size, each over a shared decode +
 * shared clock so N copies of an emoji cost N cheap draws off one source, not N
 * players: TGS via [InlineCustomEmojiRenderer] (one [com.airbnb.lottie.LottieDrawable]
 * per id in [CustomEmojiAnimator]); WebM via [WebmAlphaImage] (one ffmpeg VP9+alpha
 * decode per id in [dev.lyo.hortay.data.media.WebmFrameCache], one
 * [WebmAnimationClock] tick). WebM used to be pinned to its static thumb because the
 * only animator then available was a per-instance ExoPlayer — prohibitive at inline
 * size; the shared frame cache removed that cost, so WebM now animates inline like
 * TGS. Static WEBP stays static (no animation to run). Reduced motion (system animator
 * scale 0) collapses both animated paths to their first frame / static thumb.
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
    //   • Webm: the static thumb if TDLib gave us one (WebmAlphaImage underlays it
    //     while the .webm decodes), else the .webm media itself so the placeholder
    //     waits on the actual playback file rather than hiding before there's a
    //     frame to draw. Same shape as Tgs now that WebM animates inline too.
    // Web mode passes null fileIds and uses the remoteUrl chain instead — there
    // we trust Coil / LottieUrlStore for their own loading visuals and let the
    // metadata-resolution placeholder be the only one we paint.
    val firstVisibleFileId: Int? = sticker?.let {
        when (it.format) {
            StickerFormat.Tgs -> it.thumb?.fileId ?: it.media.fileId
            StickerFormat.Webp -> it.media.fileId
            StickerFormat.Webm -> it.thumb?.fileId ?: it.media.fileId
        }
    }
    val binding = rememberMediaBinding(fileId = firstVisibleFileId, priority = priority)

    val contentReady = when {
        sticker == null -> false
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
                // WebM custom emoji animate INLINE via [WebmAlphaImage], mirroring how
                // the Tgs branch animates inline via [InlineCustomEmojiRenderer]. Both
                // share ONE decode + ONE clock across every instance of the same id
                // (TGS: CustomEmojiAnimator; WebM: WebmFrameCache + WebmAnimationClock),
                // so N copies of an emoji in a post cost N cheap draws off one source —
                // not N players. That shared-cost pipeline is what removed the original
                // reason WebM was pinned to a static thumb: the old path span up one
                // per-instance ExoPlayer per emoji, which was prohibitive at inline size.
                //
                // Gate matches Tgs: animate whenever there's a TDLib fileId to decode.
                // Guest mode (URL-only, fileId == null) keeps the static thumb because
                // [WebmAlphaImage] needs a local file path.
                // Per-instance/off-screen pausing is handled by the shared clock, which
                // only advances while the app produces frames — same as the TGS animator.
                //
                // reducedMotion: off-switch mirroring effectiveSkeletonGrace's test —
                // animator scale at 0 means the user disabled animations; we then draw
                // frame 0 instead of running the clock.
                val canAnimate = sticker.media.fileId != null
                val reducedMotion = ValueAnimator.getDurationScale() == 0f

                // Separate binding for the .webm playback file: the `firstVisibleFileId`
                // binding above targets the thumb (placeholder gating); we need the webm
                // readyPath independently so the thumb shows while the larger playback
                // file downloads in parallel.
                val webmBinding = if (canAnimate) {
                    rememberMediaBinding(fileId = sticker.media.fileId, priority = priority)
                } else null

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val sizePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    var firstFrameRendered by remember(sticker.customEmojiId) { mutableStateOf(false) }

                    // Static thumb underlay — shown until the first decoded WebM frame
                    // lands (or permanently when not animating).
                    if (sticker.thumb != null && (!canAnimate || !firstFrameRendered)) {
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
                    // Animated overlay — mounted whenever there's a decodable fileId.
                    if (canAnimate) {
                        // Inline emoji boxes are square (100x100 glyphs) — same w/h.
                        WebmAlphaImage(
                            key = "emoji_${sticker.customEmojiId}",
                            path = webmBinding?.readyPath,
                            widthPx = sizePx,
                            heightPx = sizePx,
                            modifier = Modifier.fillMaxSize(),
                            animate = !reducedMotion,
                            onFirstFrame = { firstFrameRendered = true },
                        )
                    }
                }
                // Else: no thumb AND no fileId → nothing to draw. The needsPlaceholder
                // overlay below keeps the loading disc visible so the box doesn't render
                // as transparent dead space.
            }
        }

        // Overlay placeholder: covers the in-flight TdMediaImage / LottieStickerView
        // until the first visible file lands as Ready. Kept on TOP (not as an
        // underlay) so it doesn't bleed through transparent corners of irregular
        // glyphs once content paints — same rationale the original commit used to
        // justify dropping the underlay variant. The composables underneath stay
        // mounted so they keep driving their MediaCache / LottieUrlStore loads.
        // sticker is guaranteed non-null here — the null branch returned above.
        if (needsPlaceholder) {
            PlaceholderDisc()
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
