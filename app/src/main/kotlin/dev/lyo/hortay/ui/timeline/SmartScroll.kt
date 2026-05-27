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
 * [centerTarget]: land [target] in the VERTICAL CENTRE of the viewport (the
 * jump-to-post idiom — small posts sit centred, you see the whole card). The
 * centring offset `-((viewport - itemSize) / 2)` is symmetric, so it works
 * identically in forward AND reverseLayout — no direction-dependent sign. Posts
 * at the very edge of the list (nothing on one side to fill the gap) clamp
 * naturally. Posts taller than the viewport land showing their start.
 *
 * Centring needs the target's measured height, so when the target isn't already
 * laid out we first bring it on-screen with an INSTANT [scrollToItem] and then
 * position it — both passes instant in that case, so the user never sees a
 * snap-then-animate jerk. When the target is already visible we animate straight
 * to the centred offset in one smooth motion.
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
 * Pixel offset that centres [index] in the viewport, for use as the `scrollOffset`
 * of [scrollToItem]/[animateScrollToItem]. Negative = the item is pushed down from
 * the layout start by half the leftover space. Reads the item's measured size from
 * [layoutInfo]; returns 0 when it isn't laid out (caller falls back to top-anchor).
 */
private fun LazyListState.centeredOffsetFor(index: Int): Int {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    return -((viewport - item.size) / 2)
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
