@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.main

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.lyo.hortay.data.TimelinePost

/**
 * Shared-element container transform for the feed-card ↔ open-post morph — the Apple / Telegram
 * "the card flows into the full screen, and back" motion.
 *
 * [MainScaffold] wraps its NavDisplay in a `SharedTransitionLayout` and publishes that scope here;
 * it is `null` everywhere else (guest mode, Compose previews, any surface outside the morph-enabled
 * NavDisplay), so [postMorph] degrades to a plain no-op modifier — the feed and post-detail still
 * render, just without the morph.
 */
val LocalPostMorphScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Tags a post surface as a shared-bounds morph target keyed by `(chatId, id)`. The SAME key on the
 * feed/channel card and on the open-post pinned anchor is what lets `NavDisplay` morph one into the
 * other as the Comments scene enters (forward) or leaves (predictive back) — the card's bounds grow
 * into the full-post anchor and its content cross-fades.
 *
 * `sharedBounds` (not `sharedElement`) because the two ends hold *different* content (a feed card vs
 * the pinned anchor inside the detail), and [SharedTransitionScope.ResizeMode.RemeasureToBounds] so
 * the text re-lays-out at each frame instead of scaling (no blur on the morphing copy). The bounds
 * ride `MotionScheme.defaultSpatialSpec` so the morph matches the app's motion language.
 *
 * No-op when [LocalPostMorphScope] is absent. When present, we are inside a `NavDisplay` entry, so
 * [LocalNavAnimatedContentScope] is provided too.
 */
@Composable
fun Modifier.postMorph(post: TimelinePost): Modifier {
    val shared = LocalPostMorphScope.current ?: return this
    val anim = LocalNavAnimatedContentScope.current
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val boundsTransform = remember(boundsSpec) { BoundsTransform { _, _ -> boundsSpec } }
    return with(shared) {
        this@postMorph.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "post-morph:${post.chatId}:${post.id}"),
            animatedVisibilityScope = anim,
            enter = fadeIn(),
            exit = fadeOut(),
            boundsTransform = boundsTransform,
            // ScaleToBounds, not RemeasureToBounds: the card is heavy (media, text, reactions) and
            // RemeasureToBounds re-lays-it-out every frame of the morph — the source of the jank.
            // Per the official shared-element guidance, text/heavy content should scale (measure
            // once at the target, then graphical-scale to the animating bounds), which is also what
            // the container-transform sample uses.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}
