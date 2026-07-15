package dev.lyo.hortay.ui.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Per-group spoiler-reveal state shared by every spoiler-bearing text renderer — the flat
 * [dev.lyo.hortay.data.FormattedText] path in [rememberRenderableText] and the rich-message
 * tree in `ui.rich`. A tap on any glyph (or inline-emoji stub) inside a group scatters that
 * group's whole dot cloud once via a single [Animatable], then leaves it revealed.
 *
 * [revealedGroups] is the current snapshot value — hand it straight to a `remember(...)` key
 * so the backing [androidx.compose.ui.text.AnnotatedString] rebuilds (Transparent → onSurface)
 * exactly when a group reveals. [reveal] / [dispersion] have stable identity across the
 * recompositions that don't change the reveal set.
 */
internal class SpoilerReveal(
    val revealedGroups: Set<Int>,
    val reveal: (Int) -> Unit,
    val dispersion: (Int) -> Float?,
)

internal const val SPOILER_REVEAL_MS = 1100
internal val SpoilerEaseInQuad: Easing = Easing { t -> t * t }

/**
 * [contentKey] must be a content-stable identity (see [spoilerContentKey] for the
 * [dev.lyo.hortay.data.FormattedText] path): reveal state resets only when the underlying text
 * genuinely changes, not on every reaction / view-count / scroll recomposition that hands the
 * renderer a fresh instance. Reduced motion snaps the dispersion to fully-revealed with no
 * sweep, matching the media-spoiler behaviour.
 */
@Composable
internal fun rememberSpoilerReveal(contentKey: Any): SpoilerReveal {
    val revealedGroups = remember(contentKey) { mutableStateOf(emptySet<Int>()) }
    val dispersing = remember(contentKey) {
        mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>()
    }
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()

    val reveal: (Int) -> Unit = remember(contentKey, reducedMotion) {
        { groupId ->
            if (groupId !in revealedGroups.value) {
                revealedGroups.value = revealedGroups.value + groupId
                val anim = Animatable(0f)
                dispersing[groupId] = anim
                scope.launch {
                    if (reducedMotion) {
                        anim.snapTo(1f)
                    } else {
                        anim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = SPOILER_REVEAL_MS,
                                easing = SpoilerEaseInQuad,
                            ),
                        )
                    }
                    dispersing.remove(groupId)
                }
            }
        }
    }

    val dispersion: (Int) -> Float? = remember(contentKey) {
        { groupId ->
            val anim = dispersing[groupId]
            when {
                anim != null -> anim.value           // scattering
                groupId in revealedGroups.value -> null   // fully revealed
                else -> 0f                           // covered, idle shimmer
            }
        }
    }

    return SpoilerReveal(revealedGroups.value, reveal, dispersion)
}
