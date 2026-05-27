package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Shared anti-flicker reveal primitive for every TDLib media renderer.
 *
 * Generalises the photo path's old "keep the minithumb composed through the crossfade,
 * drop it after a linger" pattern into reusable pieces, so stickers, custom emoji, GIFs
 * and avatars stop hard-cutting placeholder→content. Three composition shapes exist and
 * each wires these helpers differently (see the per-renderer call sites):
 *
 *   • OPAQUE content (photo, avatar tier): the placeholder holds full alpha and drops
 *     after the linger; content cross-dissolves in on top. A pure crossfade is WRONG
 *     here — two mid-alpha opaque layers let the grey background bleed through (the
 *     original "блимок"). Use [MediaReveal].
 *   • TRANSPARENT content (Lottie / WebM stickers): the placeholder thumb fades OUT
 *     (`alpha = 1 - revealAlpha`) as content fades in; it must NOT stay at full alpha or
 *     a moving animation frame over a static thumb shows a doubled silhouette. Use
 *     [rememberRevealAlpha] + [rememberPlaceholderLinger] directly.
 *   • PLACEHOLDER-ON-TOP (inline custom emoji): the grey disc sits ABOVE content on
 *     purpose (so it doesn't bleed through transparent glyph corners); fade it OUT when
 *     ready. Use [rememberRevealAlpha] directly.
 *
 * The `revealed` signal each renderer supplies is "the FIRST REAL PIXEL of content has
 * painted" — Coil `onSuccess`, Lottie `composition != null`, ExoPlayer
 * `onRenderedFirstFrame`, animator `bitmap != null` — NOT "the file finished downloading"
 * ([dev.lyo.hortay.data.MediaState.Ready]). Keying on bytes-on-disk left a window where
 * the file was local but undecoded and the placeholder had already been yanked.
 */

/**
 * Default placeholder linger. Must exceed the effects-spec fade duration so an opaque
 * placeholder covers the whole content fade-in (same invariant as the old
 * `MINITHUMB_LINGER_MS 280 > CROSSFADE_MS 220` it replaced).
 */
const val MEDIA_REVEAL_LINGER_MS: Long = 280L

/**
 * Pure decision behind [rememberPlaceholderLinger], extracted for unit testing: the
 * placeholder is visible while content is not [revealed], and for [lingerMs] after it is.
 */
internal fun placeholderLingerVisible(
    revealed: Boolean,
    elapsedSinceRevealedMs: Long,
    lingerMs: Long,
): Boolean = !revealed || elapsedSinceRevealedMs < lingerMs

/**
 * Animated reveal fraction: 1f when [revealed], 0f otherwise, eased via the M3 effects
 * spec. Snaps instantly when the system animator-duration-scale is 0 ("Remove animations"),
 * via the shared [dev.lyo.hortay.data.animatorDurationScale] accessor (same source as
 * [dev.lyo.hortay.data.effectiveSkeletonGrace] elsewhere in the app).
 */
@Composable
fun rememberRevealAlpha(revealed: Boolean): Float {
    val reducedMotion = remember { dev.lyo.hortay.data.animatorDurationScale() == 0f }
    val target = if (revealed) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mediaReveal",
    )
    return if (reducedMotion) target else animated
}

/**
 * Composition-lifetime gate for a placeholder layer: true while content is NOT [revealed],
 * and for [lingerMs] after it flips revealed, then false. Re-armed when [key] changes
 * (in-place file/content swap, album slot reuse) so the new content's placeholder shows
 * again — without the key a previous "revealed → hide" decision would carry over and the
 * new content would land on bare background. Mirrors the decision in
 * [placeholderLingerVisible].
 */
@Composable
fun rememberPlaceholderLinger(
    revealed: Boolean,
    key: Any?,
    lingerMs: Long = MEDIA_REVEAL_LINGER_MS,
): Boolean {
    var visible by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key, revealed) {
        if (revealed) {
            delay(lingerMs)
            visible = false
        } else {
            visible = true
        }
    }
    return visible
}

/**
 * Convenience reveal for OPAQUE content (photo, avatar tier). [placeholder] holds full
 * alpha and drops [lingerMs] after [revealed]; [content] cross-dissolves in on top via
 * `graphicsLayer`. Both slots fill the box. Overlays (loading / failed) should be layered
 * by the caller as siblings on top of this — not passed in — so they stay at full alpha.
 */
@Composable
fun MediaReveal(
    revealed: Boolean,
    key: Any?,
    placeholder: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lingerMs: Long = MEDIA_REVEAL_LINGER_MS,
    content: @Composable () -> Unit,
) {
    val alpha = rememberRevealAlpha(revealed)
    val showPlaceholder = rememberPlaceholderLinger(revealed, key, lingerMs)
    Box(modifier = modifier) {
        if (showPlaceholder) {
            Box(Modifier.fillMaxSize()) { placeholder() }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) { content() }
    }
}
