package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Scroll strategy choice. `Instant` for far targets (cheap, no animation
 * through unloaded rows), `Animated` for near targets (smooth, preserves
 * sense of place). Matches Telegram-Android's `scrollByTouch` vs hard-jump
 * split — distance threshold is the canonical chat-UI pattern for jump
 * pills (Telegram, Slack, Discord all do this).
 */
internal enum class ScrollKind { Instant, Animated }

/**
 * Pure helper: pick scroll strategy from distance. Extracted for testing —
 * LazyListState's internal state isn't exercisable from JUnit.
 */
internal fun scrollKindFor(currentIndex: Int, target: Int, threshold: Int): ScrollKind =
    if (abs(target - currentIndex) > threshold) ScrollKind.Instant else ScrollKind.Animated

/**
 * Default distance threshold (rows). ~3 viewports at typical PostCard height.
 * Lifts the canonical chat-UI pattern: animate near, jump far. Anything
 * beyond ~8 rows animates through layout passes the user doesn't care
 * about — instant jump + brief highlight on the destination card is the
 * established pattern (Telegram Android SCROLL_MAX_*, jhakim.com chat
 * scroll playbook).
 */
internal const val SMART_SCROLL_THRESHOLD_ROWS = 8

/**
 * Jump or animate to [target] based on distance from current first-visible
 * index. Used by all three "jump" pills: NewPostsPill, UnreadCounterPill,
 * home-tap. Suspends until scroll completes.
 *
 * [centerTarget]: land [target] nicely (the jump-to-post idiom). A post that
 * FITS the viewport sits centred — you see the whole card; a post TALLER than
 * the viewport top-aligns so you read from the start instead of having both
 * ends clipped. The offset math and the forward/reverse sign handling live in
 * the pure, unit-tested [alignedScrollOffset]. Posts at the very edge of the
 * list (nothing on one side to fill the gap) clamp naturally.
 *
 * Landing needs the target's measured height, so when the target isn't already
 * laid out we first bring it on-screen with an INSTANT [scrollToItem] and then
 * position it — both passes instant in that case, so the user never sees a
 * snap-then-animate jerk. When the target is already visible we animate straight
 * to the resolved offset in one smooth motion.
 */
internal suspend fun LazyListState.smartScrollTo(
    target: Int,
    threshold: Int = SMART_SCROLL_THRESHOLD_ROWS,
    centerTarget: Boolean = false,
) {
    val current = firstVisibleItemIndex
    val kind = scrollKindFor(current, target, threshold)
    if (!centerTarget) {
        when (kind) {
            ScrollKind.Instant -> scrollToItem(target)
            ScrollKind.Animated -> animateScrollToItem(target)
        }
        return
    }
    val alreadyVisible = layoutInfo.visibleItemsInfo.any { it.index == target }
    if (!alreadyVisible) scrollToItem(target)
    val offset = centeredOffsetFor(target)
    if (alreadyVisible && kind == ScrollKind.Animated) {
        animateScrollToItem(target, offset)
    } else {
        // Target was just snapped in instantly (or it's a far jump) — keep this
        // pass instant too so the centred position appears in a single frame
        // instead of snap-then-animate.
        scrollToItem(target, offset)
    }
}

/**
 * Scroll offset that lands [index] nicely in the viewport, for use as the
 * `scrollOffset` of [scrollToItem]/[animateScrollToItem]. Delegates the math to
 * the pure, unit-tested [alignedScrollOffset]; this wrapper only supplies the
 * measured geometry (and the live [LazyListLayoutInfo.reverseLayout]). Returns 0
 * when the item isn't laid out, so the caller falls back to a plain top-anchor
 * scroll.
 */
private fun LazyListState.centeredOffsetFor(index: Int): Int {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    return alignedScrollOffset(viewport = viewport, itemSize = item.size, reverseLayout = info.reverseLayout)
}

/**
 * Pure offset math for "land this post nicely", separated from [LazyListState]
 * so it's unit-testable (the layout internals aren't reachable from JUnit).
 *
 * Both args are px on the vertical scroll axis. The result is a
 * `firstVisibleItemScrollOffset` value: negative = an empty gap before the item,
 * positive = the item scrolled past the layout's start edge.
 *
 * Two regimes, matching the chat-app "scroll to message" idiom:
 *
 *   • Post FITS (`itemSize <= viewport`): centre it. The leftover space splits
 *     evenly, so the offset is `-(gap / 2)` — symmetric, identical in forward
 *     AND reverseLayout (this is the long-confirmed small-post behaviour).
 *   • Post TALLER than the viewport: align its TOP to the viewport's top edge so
 *     the reader starts at the beginning. Centring a tall post clips BOTH ends
 *     (the reported bug — the top runs off the screen); top-aligning shows the
 *     start and lets the tail overflow off the bottom. The offset that
 *     top-aligns depends on layout direction:
 *       – forward → 0                   (item top == top == layout start)
 *       – reverse → `itemSize - viewport`  (the layout start is the BOTTOM, so
 *                    push the item down by its overflow to bring the top edge up
 *                    to the viewport top)
 */
internal fun alignedScrollOffset(viewport: Int, itemSize: Int, reverseLayout: Boolean): Int {
    val gap = viewport - itemSize
    return when {
        gap >= 0 -> -(gap / 2)
        reverseLayout -> -gap
        else -> 0
    }
}

/**
 * Suspend until LazyColumn's laid-out item count exceeds [previousTotal] (i.e.
 * a freshly-staged ingest has committed all the way through the latched
 * UiState → composition → measure pipeline), then return. Times out silently
 * after [timeoutMs] so a refresh storm can't deadlock the caller.
 *
 * Necessary before `scrollToItem(N)` whenever the caller just mutated state
 * that grows the row list: Compose dispatches the latched UiState through a
 * `LaunchedEffect`, so [androidx.compose.foundation.lazy.LazyListLayoutInfo.totalItemsCount]
 * lags the source-of-truth `items` list by a frame. `scrollToItem` CLAMPS to
 * the current totalItemsCount — without this wait, target row N gets clamped
 * down to OLD lastIndex and lands the user one row above the new arrivals.
 */
internal suspend fun LazyListState.awaitItemsCommitted(
    previousTotal: Int,
    timeoutMs: Long = 800L,
) {
    withTimeoutOrNull(timeoutMs) {
        snapshotFlow { layoutInfo.totalItemsCount }.first { it > previousTotal }
    }
}
