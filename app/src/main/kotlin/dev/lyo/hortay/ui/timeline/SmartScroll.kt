package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
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
 * index. Used by callers that want a plain top-anchored landing (NewPostsPill
 * arrival, caught-up fallback for home-tap and UnreadCounterPill). Divider-
 * anchored landings (the canonical "next unread") go through [scrollToBoundary]
 * instead. Suspends until scroll completes.
 */
internal suspend fun LazyListState.smartScrollTo(
    target: Int,
    threshold: Int = SMART_SCROLL_THRESHOLD_ROWS,
) {
    val current = firstVisibleItemIndex
    when (scrollKindFor(current, target, threshold)) {
        ScrollKind.Instant -> scrollToItem(target)
        ScrollKind.Animated -> animateScrollToItem(target)
    }
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

/**
 * Fraction of an item's pixel span that lies inside the viewport, in `[0f, 1f]`.
 *
 * Pure function so it can be unit-tested without [LazyListState]. The caller
 * supplies item edges and viewport edges in the same scroll-axis coordinate
 * space [LazyListLayoutInfo] uses (item.offset + item.size for the bottom;
 * viewportStartOffset/viewportEndOffset, optionally tightened by
 * before/afterContentPadding).
 *
 * Zero-size items return 0f (can't be "read" — collapse states shouldn't
 * dwell-ack). Items entirely outside the viewport return 0f.
 *
 * **Denominator clamp.** For items SHORTER than the viewport the divisor is
 * `itemSize`, so 1.0 means "fully visible" and the 60% threshold means "60% of
 * the card on screen" — the canonical dwell-ack rule (CHANGELOG: "60% of the
 * card is on screen for half a second"). For items TALLER than the viewport
 * that rule is mathematically unreachable: a 3000 px post in an 1800 px viewport
 * tops out at 60% of its own span, and the 28 dp [UnreadBoundaryRow] sitting
 * above the boundary post in OldestUnreadFirst eats 28 px more — pushing
 * tall-post visibility to ~59%, JUST under the threshold. The post then never
 * dwell-acks, the unread counter never decrements, and the next-unread pill
 * lands on the same row forever. Clamping the divisor to `min(itemSize, viewport)`
 * degrades the rule to "60% of the viewport occupied by this post" for tall
 * posts and leaves short-post behaviour unchanged.
 */
internal fun visibleFraction(itemStart: Int, itemEnd: Int, vStart: Int, vEnd: Int): Float {
    val itemSize = itemEnd - itemStart
    if (itemSize <= 0) return 0f
    val viewport = vEnd - vStart
    if (viewport <= 0) return 0f
    val clippedStart = maxOf(itemStart, vStart)
    val clippedEnd = minOf(itemEnd, vEnd)
    val visibleSpan = (clippedEnd - clippedStart).coerceAtLeast(0)
    val divisor = minOf(itemSize, viewport)
    return (visibleSpan.toFloat() / divisor).coerceAtMost(1f)
}


/**
 * Threshold (rows) above which `scrollToBoundary` jumps instantly. Bumped from the
 * legacy [SMART_SCROLL_THRESHOLD_ROWS] (8) because the divider is the canonical
 * landing — animating up to ~2 viewports of feed is still smooth on modern devices
 * and the user reads the in-between rows as part of the "I'm being taken back to
 * where I left off" affordance.
 */
internal const val BOUNDARY_SCROLL_THRESHOLD_ROWS = 16

/**
 * Pulse duration after a successful boundary landing. Matches the existing
 * [dev.lyo.hortay.ui.timeline.TimelineScreen] `HIGHLIGHT_DURATION_MS` for the
 * deep-link path so all jump landings (deep-link, quote, jump-pill, home-tap)
 * share one timing.
 */
internal const val BOUNDARY_LANDING_PULSE_MS = 2200L

/**
 * Pure offset math: the `scrollOffset` to pass to [LazyListState.scrollToItem] so
 * the row at the target index lands with its TOP at the viewport's **visible top**
 * — i.e. just below the [afterContentPadding] strip — in both forward and
 * reverseLayout modes.
 *
 * **Forward layout**: returns 0. `scrollToItem(idx, 0)` already places the item's
 * top at the content area's top edge (which sits below the top contentPadding).
 *
 * **ReverseLayout**: returns `itemSize - mainAxisAvailableSize - beforeContentPadding`.
 * Derived from a step-through of `LazyListMeasure.measureLazyList`
 * (androidx-main/.../lazy/LazyListMeasure.kt):
 *
 *   1. `scrollToItem(idx, X)` stores `firstVisibleItemScrollOffset = X`.
 *   2. Line 164 temporarily adds `minOffset = -beforeContentPadding` to the running
 *      offset (so items in the start-padding zone can be composed).
 *   3. Lines 177-184: backward-composition loop fires while the running offset is
 *      negative, composing items at lower indices and adding their sizes until the
 *      offset turns non-negative. This is what makes the divider land "at the top"
 *      in reverseLayout: lower-indexed items stack BELOW it visually.
 *   4. Line 195 removes the temporary `minOffset` shift.
 *   5. Line 199 sets `currentMainAxisOffset = -currentFirstItemScrollOffset`. The
 *      requested target row therefore lands at scroll-axis position `-X` (the
 *      backward-composition's `+= minOffset / -= minOffset` cancel out).
 *   6. `place()` applies the reverseLayout transform
 *      `visualY = layoutSize - item.offset - itemSize`, where `layoutSize` is the
 *      FULL layout container size, equal to
 *      `mainAxisAvailableSize + beforeContentPadding + afterContentPadding`.
 *
 * Substituting (with `item.offset = -X` for the requested row, in the typical case
 * where backward composition consumed exactly the right amount) and solving for
 * `visualY = afterContentPadding` (= visible-area top in reverseLayout, since the
 * `afterContentPadding` strip occupies layout y in `[0, afterContentPadding]`):
 *
 *     afterContentPadding = layoutSize - (-X) - itemSize
 *     X = itemSize - layoutSize + afterContentPadding
 *     X = itemSize - (mainAxisAvailableSize + beforeContentPadding + afterContentPadding) + afterContentPadding
 *     X = itemSize - mainAxisAvailableSize - beforeContentPadding
 *
 * The previous formula `itemSize - (viewportEndOffset - viewportStartOffset)`
 * (commits c64b4c4 / ba2c762) used the FULL layout span — including both
 * paddings — as the divisor. That placed the divider's visual top at y=0 of the
 * layout container, NOT y=afterContentPadding of the visible area. For our
 * LazyColumn (top contentPadding = 8 dp, bottom = NavBar reservation ≈ 80 dp),
 * the divider landed ~80 dp below the visible top in reverseLayout, with the
 * boundary post offset proportionally — exactly the "throws to wrong post / shows
 * bottom of post" user report.
 *
 *   - **Short items** (itemSize < mainAxisAvailableSize): formula is negative.
 *     Backward composition kicks in.
 *   - **Tall items** (itemSize > mainAxisAvailableSize): formula is positive.
 *     The item's visual top lands at `afterContentPadding`; bottom overflows
 *     off-screen below.
 *   - **Exact fit + no padding**: returns 0.
 */
internal fun topAnchoredScrollOffset(
    mainAxisAvailableSize: Int,
    beforeContentPadding: Int,
    itemSize: Int,
    reverseLayout: Boolean,
): Int {
    if (!reverseLayout) return 0
    return itemSize - mainAxisAvailableSize - beforeContentPadding
}

/**
 * Land the boundary divider's TOP at the viewport's TOP. The single jump API used
 * by every "next unread" pill (NewPostsPill, UnreadCounterPill, home-tap) and by
 * the cold-entry [rememberBoundaryReveal].
 *
 * Animates inside [BOUNDARY_SCROLL_THRESHOLD_ROWS] rows of the current first-visible
 * index, instantly jumps further. Reads the divider's measured size from
 * [layoutInfo] to compute the reverseLayout offset; if the boundary isn't measured
 * yet (cold mount before first layout, or jump-pill from a faraway scroll position),
 * brings it on-screen with one `scrollToItem(boundaryIndex, 0)`, waits for the
 * layout pass, then re-scrolls to the correct offset. Visually one continuous motion.
 *
 * Suspends until the scroll completes.
 *
 * @param boundaryIndex Row index of the `FeedItem.Boundary` divider in the LazyColumn.
 * @param animated When false, always uses an instant scroll regardless of distance.
 *   Cold-entry reveal passes false; jump-pills pass true.
 */
internal suspend fun LazyListState.scrollToBoundary(
    boundaryIndex: Int,
    animated: Boolean = true,
) {
    val current = firstVisibleItemIndex
    val instant = !animated ||
        abs(boundaryIndex - current) > BOUNDARY_SCROLL_THRESHOLD_ROWS
    scrollToTopOfRow(boundaryIndex, instant)
}

/**
 * Land the row at [itemIndex] with its TOP at the viewport's TOP — in both forward
 * and reverseLayout modes. Used by deep-link, quote-tap, and reply-source landings,
 * where the canonical "show me the start of this message" intent applies regardless
 * of post height.
 *
 * Suspends until the scroll completes.
 */
internal suspend fun LazyListState.scrollToTopAligned(itemIndex: Int) {
    scrollToTopOfRow(itemIndex, instant = false)
}

/**
 * Shared implementation for [scrollToBoundary] and [scrollToTopAligned]. Lands the
 * row at [itemIndex] with its TOP at the viewport's TOP using
 * [topAnchoredScrollOffset]'s formula. When the row is already measured, does it
 * in one scroll call; otherwise brings the row into view first via
 * `scrollToItem(idx, 0)`, waits for the layout pass to publish the row's measured
 * size, then re-scrolls with the correct offset.
 */
private suspend fun LazyListState.scrollToTopOfRow(itemIndex: Int, instant: Boolean) {
    val measured = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
    if (measured != null) {
        val available = layoutInfo.mainAxisAvailableSize()
        val offset = topAnchoredScrollOffset(
            mainAxisAvailableSize = available,
            beforeContentPadding = layoutInfo.beforeContentPadding,
            itemSize = measured.size,
            reverseLayout = layoutInfo.reverseLayout,
        )
        if (instant) scrollToItem(itemIndex, offset)
        else animateScrollToItem(itemIndex, offset)
        return
    }
    // Row not yet laid out — typical for cold-entry or a jump-pill targeting a row
    // far from the current viewport. Bring it in with offset=0, wait for the layout
    // pass to publish its measured size, then re-scroll with the correct top-anchored
    // offset. The snapshotFlow wait is required: reading layoutInfo synchronously
    // after scrollToItem can see the OLD visibleItemsInfo (scroll position updated
    // but new layout not yet flushed).
    scrollToItem(itemIndex, 0)
    val measuredAfter = withTimeoutOrNull(SCROLL_TOP_ALIGN_MEASURE_TIMEOUT_MS) {
        snapshotFlow {
            layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        }.filterNotNull().first()
    } ?: return
    val available = layoutInfo.mainAxisAvailableSize()
    val offset = topAnchoredScrollOffset(
        mainAxisAvailableSize = available,
        beforeContentPadding = layoutInfo.beforeContentPadding,
        itemSize = measuredAfter.size,
        reverseLayout = layoutInfo.reverseLayout,
    )
    if (offset == 0) return
    if (instant) scrollToItem(itemIndex, offset)
    else animateScrollToItem(itemIndex, offset)
}

/**
 * The space available for items, excluding before/after content padding. Equivalent
 * to Compose's internal `mainAxisAvailableSize` — the value the layout actually
 * arranges items into. `viewportSize` reports the full layout container including
 * padding zones, which is the wrong divisor for top-anchoring landings.
 */
private fun LazyListLayoutInfo.mainAxisAvailableSize(): Int =
    (viewportEndOffset - afterContentPadding) - (viewportStartOffset + beforeContentPadding)

/**
 * Safety-net cap on how long [scrollToBoundary] / [scrollToTopAligned] wait for
 * the target row to be measured after the initial bring-into-view scroll. 500 ms
 * is well past the normal one-layout-pass turnaround (~16 ms at 60 Hz); the
 * timeout exists so the caller's coroutine doesn't hang if the row never measures
 * (filtered out between scrollToItem and the next layout pass, etc.).
 */
private const val SCROLL_TOP_ALIGN_MEASURE_TIMEOUT_MS = 500L

/**
 * Cold-entry "land on the resume boundary" reveal gate for the feed. Returns
 * `false` until the boundary has been positioned, then `true`; the caller keeps
 * its existing skeleton/cover painted on top while this is `false`.
 *
 * **Why a gate is needed.** Pixel-accurate alignment for the boundary's landing
 * needs its measured height (`scrollOffsetForBoundary` → `dividerSize - viewport`
 * in reverseLayout), which doesn't exist until the first layout pass.
 * Repositioning AFTER the first paint shows the wrong, bottom-glued frame for
 * ~16 ms — the exact flash the cold-start mount was built to avoid. So instead:
 * the caller mounts the list and keeps its skeleton on top while this returns
 * `false`; the list composes + measures underneath, we issue one instant
 * [scrollToBoundary] (animated = false), and the cover lifts on the frame the
 * corrected position paints.
 *
 * **Fires once per genuine cold entry.** The reveal flag is a [rememberSaveable]
 * keyed on [routeKey], so a drill-out/drill-in restore (where
 * `LazyListState.Saver` brings back the user's real scroll) sees it already
 * `true` and skips repositioning. When [enabled] is false or [boundaryIndex] is
 * `<= 0` (Newest mode caught-up feeds, deep-link landings) it initialises
 * `true` immediately so those paths keep their untouched one-frame mount with
 * no skeleton beat.
 */
@Composable
internal fun rememberBoundaryReveal(
    listState: LazyListState,
    boundaryIndex: Int,
    enabled: Boolean,
    routeKey: Any,
): Boolean {
    val active = enabled && boundaryIndex > 0
    var revealed by rememberSaveable(routeKey) { mutableStateOf(!active) }
    LaunchedEffect(routeKey) {
        if (revealed) return@LaunchedEffect
        // `boundaryIndex` is the seeded anchor, so it's laid out on frame one; the
        // timeout is a safety net in case it never measures (clamped / empty).
        withTimeoutOrNull(BOUNDARY_REVEAL_TIMEOUT_MS) {
            // Wait until the boundary row has been measured (laid out).
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundaryIndex }
            }.filterNotNull().first()
            listState.scrollToBoundary(boundaryIndex, animated = false)
        }
        revealed = true
    }
    return revealed
}

/** Safety-net cap on how long [rememberBoundaryReveal] holds the cover. */
private const val BOUNDARY_REVEAL_TIMEOUT_MS = 700L
