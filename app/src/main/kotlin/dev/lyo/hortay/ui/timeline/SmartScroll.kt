package dev.lyo.hortay.ui.timeline

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
 * Cold-entry "land on the resume boundary" reveal gate for a `reverseLayout`
 * feed. Returns `false` until the boundary post has been positioned, then
 * `true`; the caller keeps its existing skeleton/cover painted on top while
 * this is `false`.
 *
 * **Why it's needed.** `LazyListState(boundary, 0)` makes `boundary` the
 * `firstVisibleItem`, which under `reverseLayout` is the BOTTOM of the
 * viewport — so the oldest-unread boundary glues to the bottom edge with
 * already-read history filling the screen above and the unread queue stranded
 * off the bottom. Before the reverseLayout migration the same seed (forward
 * layout) put the boundary at the TOP with unread below; the migration
 * silently flipped the anchor edge. The documented intent is "read on top,
 * unread queue below, lands at boundary".
 *
 * **Why a one-frame seed can't fix it.** Pixel-accurate alignment needs the
 * boundary's measured height (`alignedScrollOffset` → `itemSize - viewport` in
 * reverse), which doesn't exist until the first layout pass. Repositioning
 * AFTER the first paint shows the wrong, bottom-glued frame for ~16 ms — the
 * exact flash the cold-start mount was built to avoid. So instead: the caller
 * mounts the list and keeps its skeleton on top while this returns `false`;
 * the list composes + measures underneath, we issue ONE instant
 * [scrollToItem] with the measured [alignedScrollOffset], and the cover lifts
 * on the frame the corrected position paints.
 *
 * **Fires once per genuine cold entry.** The reveal flag is a [rememberSaveable]
 * keyed on [routeKey], so a drill-out/drill-in restore (where
 * `LazyListState.Saver` brings back the user's real scroll) sees it already
 * `true` and skips repositioning. When [enabled] is false or [targetIndex] is
 * `<= 0` (Newest mode, caught-up feeds, deep-link landings) it initialises
 * `true` immediately so those paths keep their untouched one-frame mount with
 * no skeleton beat.
 */
@Composable
internal fun rememberBoundaryReveal(
    listState: LazyListState,
    targetIndex: Int,
    enabled: Boolean,
    routeKey: Any,
): Boolean {
    val active = enabled && targetIndex > 0
    var revealed by rememberSaveable(routeKey) { mutableStateOf(!active) }
    LaunchedEffect(routeKey) {
        if (revealed) return@LaunchedEffect
        // `targetIndex` is the seeded anchor, so it's laid out on frame one; the
        // timeout is a safety net in case it never measures (clamped / empty).
        withTimeoutOrNull(BOUNDARY_REVEAL_TIMEOUT_MS) {
            val offset = snapshotFlow {
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                item?.let {
                    alignedScrollOffset(
                        viewport = info.viewportEndOffset - info.viewportStartOffset,
                        itemSize = it.size,
                        reverseLayout = info.reverseLayout,
                    )
                }
            }.filterNotNull().first()
            listState.scrollToItem(targetIndex, offset)
        }
        revealed = true
    }
    return revealed
}

/** Safety-net cap on how long [rememberBoundaryReveal] holds the cover. */
private const val BOUNDARY_REVEAL_TIMEOUT_MS = 700L

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
 * dwell-ack). Items entirely outside the viewport return 0f. Items fully
 * contained return 1f.
 */
internal fun visibleFraction(itemStart: Int, itemEnd: Int, vStart: Int, vEnd: Int): Float {
    val itemSize = itemEnd - itemStart
    if (itemSize <= 0) return 0f
    val clippedStart = maxOf(itemStart, vStart)
    val clippedEnd = minOf(itemEnd, vEnd)
    val visibleSpan = (clippedEnd - clippedStart).coerceAtLeast(0)
    return visibleSpan.toFloat() / itemSize
}

/**
 * `scrollOffset` to pass to [LazyListState.scrollToItem]/[animateScrollToItem] so the
 * boundary divider row lands with its TOP at the viewport's TOP.
 *
 * Forward layout: `scrollOffset = 0` because the layout start IS the viewport top —
 * the item's top aligns with it for free.
 *
 * `reverseLayout = true`: the layout start is the viewport BOTTOM and `scrollOffset`
 * is measured FROM that bottom (positive = pushed deeper "past" the start). To bring
 * the divider's TOP up to the viewport top, we shift the item by
 * `(dividerSize - viewport)` — a negative value that pulls the divider's bottom up
 * to `viewport - dividerSize` above the layout start, equivalent to dividerSize
 * below the viewport top.
 *
 * Clamps to 0 when dividerSize > viewport (pathological — divider is ~84 px against
 * a >1000 px viewport in practice — but a clamp keeps the result well-defined).
 */
internal fun scrollOffsetForBoundary(viewport: Int, dividerSize: Int, reverseLayout: Boolean): Int {
    if (!reverseLayout) return 0
    val offset = dividerSize - viewport
    return offset.coerceAtMost(0)
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
 * Land the boundary divider's TOP at the viewport's TOP. The single jump API used
 * by every "next unread" pill (NewPostsPill, UnreadCounterPill, home-tap) and by
 * the cold-entry [rememberBoundaryReveal].
 *
 * Animates inside [BOUNDARY_SCROLL_THRESHOLD_ROWS] rows of the current first-visible
 * index, instantly jumps further — the canonical chat-app idiom (no one wants to
 * watch a 200-row animation). Reads the divider's measured size from [layoutInfo]
 * for the reverseLayout offset; if the boundary hasn't been measured yet (cold
 * mount before first layout), falls back to one instant `scrollToItem(boundaryIndex, 0)`
 * to bring it into view, then on the next layout pass with a measured size,
 * repositions instantly to the correct offset. The user sees one continuous
 * scroll — no perceptible two-step.
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

    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val reverseLayout = layoutInfo.reverseLayout
    val measured = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundaryIndex }

    if (measured != null) {
        val offset = scrollOffsetForBoundary(viewport, measured.size, reverseLayout)
        if (instant) scrollToItem(boundaryIndex, offset)
        else animateScrollToItem(boundaryIndex, offset)
        return
    }

    // Boundary not yet measured. Bring it into view at offset 0 first, then reposition
    // once a measured size is available. The two scrolls happen on consecutive layout
    // passes — visually one continuous motion.
    scrollToItem(boundaryIndex, 0)
    val measuredAfter = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundaryIndex }
    if (measuredAfter != null) {
        val offset = scrollOffsetForBoundary(viewport, measuredAfter.size, reverseLayout)
        if (offset != 0) scrollToItem(boundaryIndex, offset)
    }
}
