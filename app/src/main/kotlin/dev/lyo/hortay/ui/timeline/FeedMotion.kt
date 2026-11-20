package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * A live counter (reaction count, views, comments, forwards) that rolls to its new value:
 * the new text slides in vertically — up when the number grew, down when it shrank — while
 * the old one slides out and fades, Telegram's counter idiom.
 *
 * Keyed on the formatted [text], not on [value]: "34.3K" stays put through the hundred
 * ticks that do not change what the reader sees. [value] only picks the direction. The
 * first composition never animates, so a card scrolling into view shows its counts at rest.
 *
 * The caller passes tabular figures in [style] so a roll never changes the width and
 * shifts neighbours. `clipToBounds` confines the sliding text to the counter's own box —
 * the spatial spring overshoots by design, and without the clip the outgoing digits
 * peeked past the edge for a frame. Under reduced motion the roll is a plain crossfade.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RollingCount(
    value: Int,
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    // Specs are read in @Composable scope and captured by the transitionSpec lambda, which
    // is not composable and cannot read MaterialTheme itself.
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    // Direction of the roll in flight. Plain holder, not state: it is written right before
    // the text changes and read by the transition that change starts.
    val direction = remember { RollDirection(value) }
    direction.update(value)
    AnimatedContent(
        targetState = text,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
            } else {
                val up = direction.up
                (slideInVertically(spatialSpec) { h -> if (up) h else -h } + fadeIn(fadeSpec))
                    .togetherWith(slideOutVertically(spatialSpec) { h -> if (up) -h else h } + fadeOut(fadeSpec))
            }
        },
        label = "rolling-count",
    ) { shown ->
        Text(text = shown, style = style, color = color, maxLines = 1)
    }
}

private class RollDirection(private var last: Int) {
    var up: Boolean = true
        private set

    fun update(value: Int) {
        if (value == last) return
        up = value > last
        last = value
    }
}

/**
 * Follows the content's height with a short animation instead of jumping to it, WITHOUT
 * clipping — which is why this is not `animateContentSize`: that modifier clips to the
 * animated bounds, and the reaction row draws its choose effect past its own box.
 *
 * Used for the reaction rows of a post: when the first reaction lands on a post, or a new
 * one wraps onto a second line, the text the reader is on glides by one row instead of
 * teleporting. The first measure is taken as is, so a card entering the viewport never
 * animates in. Snaps under reduced motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.smoothHeight(): Modifier {
    if (rememberReducedMotion()) return this
    val scope = rememberCoroutineScope()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Int>()
    val holder = remember { arrayOfNulls<Animatable<Int, *>>(1) }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val current = holder[0]
        val height = if (current == null) {
            holder[0] = Animatable(placeable.height, Int.VectorConverter)
            placeable.height
        } else {
            if (current.targetValue != placeable.height) {
                scope.launch { current.animateTo(placeable.height, spec) }
            }
            current.value.coerceAtLeast(0)
        }
        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
    }
}

/**
 * Scales and fades the content in once, when [animate] is true at its first composition.
 * For a reaction chip that appears on a post already on screen; chips that are there when
 * the card composes pass false and are simply drawn.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.popIn(animate: Boolean): Modifier {
    val enabled = remember { animate }
    if (!enabled || rememberReducedMotion()) return this
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) { progress.animateTo(1f, spec) }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val scale = POP_IN_START_SCALE + (1f - POP_IN_START_SCALE) * p
        scaleX = scale
        scaleY = scale
    }
}

private const val POP_IN_START_SCALE = 0.6f
