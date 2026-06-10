package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
 * Pure viewport arithmetic: the pixel delta to feed [LazyListState.scrollBy] so the row
 * described by [rowOffset]/[rowSize] lands with its TOP edge on the viewport's **visible
 * top** — just inside the top content-padding strip — in both forward and reverseLayout
 * modes.
 *
 * **No Compose-internal measure/place math is reverse-engineered here.** The previous
 * `topAnchoredScrollOffset` derived a `scrollToItem` offset by stepping through
 * `LazyListMeasure`'s private `minOffset` shift + backward-composition loop + `place()`
 * transform; that interaction doesn't compose obviously and produced a string of
 * wrong-on-device landings. The robust alternative the caller uses instead:
 * `scrollToItem` to bring the row on-screen, read its ACTUAL laid-out position from
 * [LazyListLayoutInfo], and close the remaining gap with one [scrollBy]. This function
 * is just that gap. "Measure reality, nudge by the difference" can't drift from
 * undocumented internals because it reads the real geometry every time.
 *
 * Coordinate model (confirmed by on-device measurement of the reverseLayout feed —
 * `before`/`after` swap under reverseLayout, so the visible top is `viewportEndOffset -
 * afterContentPadding`, not `+ beforeContentPadding`):
 *   - [androidx.compose.foundation.lazy.LazyListItemInfo.offset] grows in the
 *     index-increasing direction: toward the visual BOTTOM in forward layout, toward the
 *     visual TOP in reverseLayout.
 *   - The visible content area excludes the padding strips. Its visual-top edge is
 *     `viewportStartOffset + beforeContentPadding` (forward) or
 *     `viewportEndOffset - afterContentPadding` (reverse).
 *   - A positive [scrollBy] scrolls toward the END (higher indices), which decreases
 *     item offsets in BOTH modes. So the delta that drives the row's top edge onto the
 *     visible top is simply `currentTopEdge - desiredTopEdge`.
 *
 * Works uniformly whether the row is shorter or taller than the viewport: a tall post
 * gets its TOP aligned and its tail overflows off the bottom (the canonical "show me the
 * start of this message" landing), with no special-case branch.
 *
 * The returned delta is what [LazyListState.scrollBy] needs to *close the gap*; at the
 * very edge of the content (e.g. the oldest row in reverseLayout) the list may not have
 * that many pixels left to scroll, in which case `scrollBy` consumes only what's
 * available and the row lands as close to the top as the content allows — the same
 * graceful degradation any scroll API gives at a boundary.
 */
internal fun topAlignDelta(
    rowOffset: Int,
    rowSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    beforeContentPadding: Int,
    afterContentPadding: Int,
    reverseLayout: Boolean,
): Float = if (reverseLayout) {
    ((rowOffset + rowSize) - (viewportEndOffset - afterContentPadding)).toFloat()
} else {
    (rowOffset - (viewportStartOffset + beforeContentPadding)).toFloat()
}

/**
 * Land the read→unread boundary row's TOP at the viewport's TOP. The single jump API
 * used by every "next unread" affordance and by the cold-entry [rememberBoundaryReveal].
 * "Boundary row" is the row that sits at the read/unread frontier for the caller:
 *   - cold-entry reveal passes the frozen [FeedItem.Boundary] divider's row index;
 *   - the UnreadCounterPill / home-tap pass the LIVE oldest-unread post's row index
 *     ([dev.lyo.hortay.ui.timeline.TimelineScreen]'s `homeScrollIndex`), so the jump
 *     advances post-by-post as the live cursors mark posts read.
 *
 * Animates inside [BOUNDARY_SCROLL_THRESHOLD_ROWS] rows of the current first-visible
 * index, instantly jumps further. Delegates to [scrollToTopOfRow], which reads the row's
 * ACTUAL measured position and nudges by [topAlignDelta] — no Compose-internal offset
 * math. Suspends until the scroll completes.
 *
 * @param rowIndex Row index to land at the visible top (boundary divider or oldest-unread post).
 * @param animated When false, always uses an instant scroll regardless of distance.
 *   Cold-entry reveal passes false; jump-pills pass true.
 */
internal suspend fun LazyListState.scrollToBoundary(
    rowIndex: Int,
    animated: Boolean = true,
) {
    val current = firstVisibleItemIndex
    val instant = !animated ||
        abs(rowIndex - current) > BOUNDARY_SCROLL_THRESHOLD_ROWS
    scrollToTopOfRow(rowIndex, instant)
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
 * Shared implementation for [scrollToBoundary] and [scrollToTopAligned]. Lands the row
 * at [itemIndex] with its TOP edge on the viewport's visible top in both forward and
 * reverseLayout modes, via the "measure reality, nudge by the difference" pattern: bring
 * the row on-screen (if it isn't already), read its ACTUAL laid-out position, then close
 * the gap returned by [topAlignDelta] with one [scrollBy]. No Compose-internal offset
 * math — see [topAlignDelta] for why the prior derivation approach was abandoned.
 */
private suspend fun LazyListState.scrollToTopOfRow(itemIndex: Int, instant: Boolean) {
    // Ensure the row is measured. If already visible we align in one scroll; otherwise
    // bring it on-screen first (instant — it's off-screen, there's nothing to animate
    // through), then wait for the layout pass to publish its measured position. The
    // snapshotFlow wait is required: reading layoutInfo synchronously after scrollToItem
    // can still see the OLD visibleItemsInfo (scroll applied, new layout not yet flushed).
    var row = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
    if (row == null) {
        scrollToItem(itemIndex)
        row = withTimeoutOrNull(SCROLL_TOP_ALIGN_MEASURE_TIMEOUT_MS) {
            snapshotFlow {
                layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            }.filterNotNull().first()
        } ?: return
    }
    val info = layoutInfo
    val delta = topAlignDelta(
        rowOffset = row.offset,
        rowSize = row.size,
        viewportStartOffset = info.viewportStartOffset,
        viewportEndOffset = info.viewportEndOffset,
        beforeContentPadding = info.beforeContentPadding,
        afterContentPadding = info.afterContentPadding,
        reverseLayout = info.reverseLayout,
    )
    if (delta != 0f) {
        if (instant) scrollBy(delta) else animateScrollBy(delta)
    }
}

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
 * needs the divider's ACTUAL measured offset/size ([topAlignDelta] reads it from
 * [layoutInfo]), which doesn't exist until the first layout pass.
 * Repositioning AFTER the first paint shows the wrong, bottom-glued frame for
 * ~16 ms — the exact flash the cold-start mount was built to avoid. So instead:
 * the caller mounts the list and keeps its skeleton on top while this returns
 * `false`; the list composes + measures underneath, we issue one instant
 * [scrollToBoundary] (animated = false), and the cover lifts on the frame the
 * corrected position paints.
 *
 * **Fires once per genuine cold entry.** The reveal flag is a [rememberSaveable]
 * keyed on [routeKey], so a drill-out/drill-in restore (where the VM scroll
 * anchor — `TimelineViewModel.feedScrollAnchor` — brings back the user's real
 * scroll) sees it already `true` and skips repositioning. When [enabled] is false or [boundaryIndex] is
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
