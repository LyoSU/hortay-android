@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * Custom pull-to-refresh indicator (I1) — a single `primaryContainer` disc whose
 * shape morphs through [HortayExpressive.LoadingPolygons] (Circle → SoftBurst →
 * Cookie9 → Pill → Sunny), the same loading vocabulary the inline spinner and the
 * M3 expressive PTR default both ride.
 *
 *  - **Dragging:** the disc scales + fades in with [PullToRefreshState.distanceFraction]
 *    and rotates / morphs proportionally so the pull feels physically connected to
 *    the gesture. Crossing the threshold (`distanceFraction >= 1`) fires a
 *    [HapticFeedbackType.GestureThresholdActivate] tick ONCE per crossing (J2).
 *  - **Refreshing:** an infinite transition spins the morph + rotation continuously.
 *    Infinite-loop decorations are intentionally NOT gated by reduced motion (they
 *    read as "still loading", per [rememberReducedMotion]'s doctrine); only the
 *    threshold-crossed spin acceleration is.
 *
 * Sized to the standard PTR slot (≈40 dp disc) and positioned by the caller (the
 * `indicator =` slot already aligns + offsets by the header height).
 */
@Composable
fun FeedPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val reduced = rememberReducedMotion()

    // Threshold-crossed haptic: fire when distanceFraction first reaches 1, re-arm
    // when the user relaxes back below it. Skipped while already refreshing.
    var armed by remember { mutableStateOf(true) }
    LaunchedEffect(state.distanceFraction, isRefreshing) {
        val crossed = state.distanceFraction >= 1f
        if (!isRefreshing && crossed && armed) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            armed = false
        } else if (!crossed) {
            armed = true
        }
    }

    // Continuous spin / morph while refreshing.
    val infinite = rememberInfiniteTransition(label = "ptr-infinite")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ptr-spin",
    )
    val morphCycle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = MORPH_STEPS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100 * MORPH_STEPS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ptr-morph",
    )

    val fraction = state.distanceFraction.coerceIn(0f, 1.5f)
    // While refreshing, drive shape from the looping cycle; while dragging, map the
    // pull fraction onto the first morph leg (Circle → SoftBurst) so the disc visibly
    // "wakes up" as the user pulls.
    val morph: Morph = remember(isRefreshing, morphCycle, fraction) {
        if (isRefreshing) {
            val idx = (morphCycle.toInt()) % MORPH_STEPS
            LOADING_MORPHS[idx]
        } else {
            LOADING_MORPHS[0]
        }
    }
    val progress = if (isRefreshing) (morphCycle % 1f) else fraction.coerceIn(0f, 1f)
    val shape = remember(morph, progress) { MorphShape(morph, progress) }

    val visScale = if (isRefreshing) 1f else (0.4f + 0.6f * fraction).coerceIn(0f, 1f)
    val rotation = if (isRefreshing && !reduced) spin else fraction * 120f

    Box(
        modifier = modifier.size(INDICATOR_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 3.dp,
            modifier = Modifier
                .size(DISC_SIZE)
                .scale(visScale)
                .graphicsLayer {
                    rotationZ = rotation
                    alpha = if (isRefreshing) 1f else fraction.coerceIn(0f, 1f)
                },
        ) {
            // Inner accent dot so the morph silhouette reads against the container fill.
            Box(
                modifier = Modifier
                    .size(DISC_SIZE)
                    .graphicsLayer { rotationZ = -rotation }, // counter-rotate so the dot stays put
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer),
                )
            }
        }
    }
}

/** Number of legs in the [HortayExpressive.LoadingPolygons] cycle (wrap back to start). */
private val MORPH_STEPS = HortayExpressive.LoadingPolygons.size

/** Pre-built consecutive morphs around the loading-polygon cycle (last → first wraps). */
private val LOADING_MORPHS: List<Morph> = HortayExpressive.LoadingPolygons.let { polys ->
    List(polys.size) { i -> Morph(polys[i], polys[(i + 1) % polys.size]) }
}

private val INDICATOR_SIZE = 48.dp
private val DISC_SIZE = 36.dp
