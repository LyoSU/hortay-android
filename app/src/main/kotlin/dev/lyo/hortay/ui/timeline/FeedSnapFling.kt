package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Snap-fling tuned for a Twitter-style mixed-height feed.
 *
 * The stock [rememberSnapFlingBehavior] with `SnapPosition.Start` is great for
 * fixed-size cards (Reels, Stories, full-page Pager) but actively user-hostile
 * for a feed where post heights vary — including posts taller than the viewport.
 * Three reported failures all share the well-known anti-pattern of CSS
 * `scroll-snap-type: y mandatory` for variable-height content (MDN, CSS-Tricks):
 *
 *   1. **Tall posts become unreadable.** Any micro-fling started from inside a
 *      post bigger than the viewport snaps either back to the post's top or
 *      forward to the next post's top — there's no way to settle the viewport
 *      on the middle of a long post to read a paragraph.
 *   2. **"Snap-back" on gentle flings.** With the default approach + nearest-
 *      target snap, a forward fling whose decay undershoots the next item
 *      gets yanked *back* to the current item. The user reads this as "the
 *      scroll resisted me — I had to flick harder".
 *   3. **Multi-item overshoots.** A hard fling can skip several posts in one
 *      gesture. Acceptable for Reels (every item is the same height); jarring
 *      for a chat-style feed where each post is a deliberate stopping point.
 *
 * This helper applies the discrete-swipe / paginator model — the same idiom
 * Telegram-Android and Twitter use when they want "each fling advances one
 * post":
 *
 *   • Tall anchor (post height ≥ [tallPostThreshold] × viewport): no snap.
 *     The user is reading, not paging. Default decay-fling settles wherever
 *     physics lands; snap resumes on the next fling that starts inside a
 *     shorter post.
 *   • Below [minSnapVelocityDp]: no snap. Treats very gentle drag-releases
 *     as position nudges, not advance-intent. Default decay-fling handles them.
 *   • Otherwise: directional one-item commit. Forward velocity always
 *     advances exactly one item, backward velocity retreats exactly one.
 *     No snap-back, no multi-item skipping.
 *
 * Drag scrolling stays free regardless; only the fling animation is affected.
 *
 * `★ Why a delegating FlingBehavior over a single custom SnapLayoutInfoProvider`
 * The tall-post and low-velocity gates need to fall through to a *decay-fling*
 * (no snap animation at all), not a snap-fling with zero offset. A snap
 * fling with calculateSnapOffset = 0 still animates "to current position"
 * (eats the inertia immediately), which feels grabbed. A real decay fling
 * carries the gesture's energy through to a natural settle. Picking the
 * whole fling-behavior at gesture start is one branch, no shared state.
 */
@Composable
fun rememberFeedSnapFlingBehavior(
    lazyListState: LazyListState,
    minSnapVelocityDp: Float = MIN_SNAP_VELOCITY_DP,
    tallPostThreshold: Float = TALL_POST_VIEWPORT_RATIO,
): FlingBehavior {
    val provider = remember(lazyListState) { DirectionalSnapInfoProvider(lazyListState) }
    val snapFling = rememberSnapFlingBehavior(snapLayoutInfoProvider = provider)
    val defaultFling = ScrollableDefaults.flingBehavior()
    val minVelocityPx = with(LocalDensity.current) { minSnapVelocityDp.dp.toPx() }

    return remember(snapFling, defaultFling, lazyListState, minVelocityPx, tallPostThreshold) {
        FeedSnapFlingBehavior(
            snapFling = snapFling,
            defaultFling = defaultFling,
            lazyListState = lazyListState,
            minVelocityPx = minVelocityPx,
            tallPostThreshold = tallPostThreshold,
        )
    }
}

private class FeedSnapFlingBehavior(
    private val snapFling: FlingBehavior,
    private val defaultFling: FlingBehavior,
    private val lazyListState: LazyListState,
    private val minVelocityPx: Float,
    private val tallPostThreshold: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val behavior = if (shouldSnap(initialVelocity)) snapFling else defaultFling
        return with(behavior) { performFling(initialVelocity) }
    }

    private fun shouldSnap(initialVelocity: Float): Boolean {
        if (abs(initialVelocity) < minVelocityPx) return false
        val layout = lazyListState.layoutInfo
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        if (viewport <= 0) return true
        val viewportTop = layout.viewportStartOffset
        val anchor = layout.visibleItemsInfo.firstOrNull { item ->
            item.offset <= viewportTop && item.offset + item.size > viewportTop
        } ?: layout.visibleItemsInfo.firstOrNull() ?: return true
        return anchor.size < viewport * tallPostThreshold
    }
}

/**
 * Custom snap provider that commits exactly one post per fling in the
 * direction the user flung. Replaces Compose's default
 * `SnapLayoutInfoProvider(state, SnapPosition.Start)`, which picks the
 * *nearest* item to the fling-decayed landing position — that's what
 * produces the "I flung forward but it snapped back to current" feel for
 * undershot flings, and the "I flicked hard and skipped 4 posts" feel for
 * overshot ones. Setting `calculateApproachOffset = 0` collapses the
 * algorithm into a single decisive snap animation from gesture lift to the
 * chosen target — no decay phase to deceive the target picker.
 */
private class DirectionalSnapInfoProvider(
    private val state: LazyListState,
) : SnapLayoutInfoProvider {

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

    override fun calculateSnapOffset(velocity: Float): Float {
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo
        if (visible.isEmpty()) return 0f
        val viewportTop = layout.viewportStartOffset

        // Anchor: the post the user is currently reading — the one whose top
        // edge sits at or above viewport top and whose bottom edge is past it.
        // Falls back to the first visible row if the resolver finds none
        // (very-tall items overshooting both edges, or empty-state transients).
        val anchor = visible.firstOrNull { item ->
            item.offset <= viewportTop && item.offset + item.size > viewportTop
        } ?: visible.first()

        // Snap offset is "how far to scroll" relative to current position so
        // the target item's top lands at viewport top. `item.offset` is in
        // the LazyList's own coordinate space (`viewportStartOffset` is where
        // the visible area starts, accounting for contentPadding.start).
        return when {
            velocity > 0f -> {
                // Forward intent: snap to anchor + 1. If the next index isn't
                // in `visibleItemsInfo` (anchor occupies the whole viewport),
                // fall back to scrolling anchor.bottom to viewport.top so the
                // next fling has a fresh anchor to advance from.
                val next = visible.firstOrNull { it.index == anchor.index + 1 }
                if (next != null) {
                    (next.offset - viewportTop).toFloat()
                } else {
                    (anchor.offset + anchor.size - viewportTop).toFloat()
                }
            }
            velocity < 0f -> {
                // Backward intent: if we've scrolled past anchor's top, the
                // gesture's first job is to restore it — that's one logical
                // "back step". Only jump to the previous item if the anchor's
                // top is already at viewport top.
                if (anchor.offset < viewportTop) {
                    (anchor.offset - viewportTop).toFloat()
                } else {
                    val prev = visible.firstOrNull { it.index == anchor.index - 1 }
                    if (prev != null) (prev.offset - viewportTop).toFloat() else 0f
                }
            }
            else -> 0f
        }
    }
}

/**
 * Velocity floor (dp/s) below which a fling is treated as a position nudge,
 * not a swipe-to-advance. Picked just above typical lift-finger residual
 * velocity (~200-400 dp/s) and well below an intentional swipe (~1000+ dp/s).
 * A deliberate one-finger advance comfortably clears 500 dp/s on most
 * hardware, so 400 strikes the right balance between "easy to trigger" and
 * "no phantom snaps on accidental nudges".
 */
private const val MIN_SNAP_VELOCITY_DP = 400f

/**
 * Anchor-size cutoff (as a fraction of viewport height) above which the post
 * is "too tall to page" and snap is skipped for that fling. 0.9 is deliberately
 * just below 1.0 — at exactly 1× viewport the post fits but with zero room
 * for the user to scroll within it, which still reads as "snap stole my
 * scroll". Snap resumes on the very next fling once the viewport has crossed
 * into a shorter post.
 */
private const val TALL_POST_VIEWPORT_RATIO = 0.9f
