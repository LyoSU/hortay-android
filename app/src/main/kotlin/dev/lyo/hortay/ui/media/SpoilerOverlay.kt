package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * Telegram-style spoiler / sensitive-content cover for media. Sits on top of an already-
 * blurred photo / video poster (the caller applies `Modifier.blur` to the underlying
 * media). The visual differs by [SpoilerKind]:
 *
 *  * [SpoilerKind.Spoiler] — pure shimmering dot cloud, no label. The cloud itself is the
 *    affordance: like Telegram, tap to reveal. No icon, no copy: a known-by-author
 *    spoiler doesn't need the user to be told *why* it's hidden.
 *  * [SpoilerKind.Sensitive] — dot cloud PLUS a centred icon-and-label pill, so the
 *    user knows *why* the cover is there and that tapping is a deliberate consent step.
 *
 * Reveal is a Thanos-style dispersion: tap starts an `Animatable` that pushes a
 * `dispersionProgress` 0 → 1 into the shimmer drawer (the dot field disintegrates in a
 * left→right sweep, each particle drifting a fixed distance on an upward wind arc while
 * the whole cloud fades and shrinks); when the animation completes the overlay calls
 * [onReveal] and the parent flips its `revealed` state, removing both the overlay and the
 * underlying blur in one frame.
 */
enum class SpoilerKind { Spoiler, Sensitive }

private const val REVEAL_DURATION_MS = 1100
private val EaseInQuad: Easing = Easing { t -> t * t }

@Composable
fun SpoilerOverlay(
    kind: SpoilerKind,
    seed: Int,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
) {
    val drift by rememberSpoilerDrift()
    val field = remember { SpoilerField() }
    val dispersion = remember { Animatable(0f) }
    var dismissing by remember { mutableStateOf(false) }
    val dispersionProgress = dispersion.value
    // Reduced motion (system "Remove animations"): skip the dispersal sweep and reveal
    // in one frame instead of the ~1.1 s Thanos-style crumble.
    val reducedMotion = rememberReducedMotion()

    LaunchedEffect(dismissing) {
        if (dismissing) {
            if (reducedMotion) {
                dispersion.snapTo(1f)
            } else {
                // easeInQuad — slow start, fast finish. Matches Telegram-Android's spoiler
                // ripple (Easings.easeInQuad in SpoilerEffect.java). It drives the wave front:
                // the left edge starts crumbling gently, then the sweep rips across the field
                // faster through the back half — the "lift off" feel.
                dispersion.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = REVEAL_DURATION_MS,
                        easing = EaseInQuad,
                    ),
                )
            }
            onReveal()
        }
    }

    // No-ripple click so the dispersion is the visible feedback, not a Material highlight.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !dismissing,
                onClick = { dismissing = true },
            ),
    ) {
        // Veil fades out together with the particles so the underlying (still-blurred)
        // media gets brighter as the cloud disperses — gives the reveal a sense of
        // "the haze lifts" before the parent drops the blur entirely.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - dispersionProgress }
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSpoilerShimmer(
                field = field,
                seed = seed,
                drift = drift,
                color = Color.White,
                dispersionProgress = dispersionProgress,
            )
        }
        if (kind == SpoilerKind.Sensitive) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = 1f - dispersionProgress }
                    .padding(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(
                    name = "visibility_off",
                    tint = Color.White,
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.spoiler_sensitive_tap),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
