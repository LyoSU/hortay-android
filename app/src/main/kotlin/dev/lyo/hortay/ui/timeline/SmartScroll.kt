package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
    // Force a known scroll-position-0 landing first (bottom-anchored in reverseLayout,
    // top-anchored in forward), then apply the viewport-size-based correction. This is
    // less dependent on Compose's internal item.offset semantics than reading the row's
    // current offset — for reverseLayout the geometric truth `viewport - item.size`
    // gives us the exact pixel shift needed to put the top at the viewport top.
    forceTopAlign(boundaryIndex, instant)
}

/**
 * Land the row at [itemIndex] with its TOP at the viewport's TOP — in both forward
 * and reverseLayout modes. Used by deep-link, quote-tap, and reply-source landings,
 * where the canonical "show me the start of this message" intent applies regardless
 * of post height.
 *
 * Plain `scrollToItem(index, 0)` in reverseLayout glues the item's BOTTOM to the
 * viewport bottom — for a TALL post the header (top) ends up clipped off-screen
 * above. This helper brings the row into view (any anchor), reads its actual
 * measured y-position, and then scrolls by exactly the delta needed to put its
 * top at the viewport's top. Works uniformly in both layout directions because
 * the delta is computed from the row's measured position, not from a layout-
 * direction-dependent scrollOffset formula.
 *
 * Suspends until the scroll completes.
 */
internal suspend fun LazyListState.scrollToTopAligned(itemIndex: Int) {
    forceTopAlign(itemIndex, instant = false)
}

/**
 * Land the row at [itemIndex] with its TOP at the viewport's TOP in both forward
 * and reverseLayout.
 *
 * Mechanism: always do `scrollToItem(itemIndex, 0)` first to put the row at a
 * KNOWN starting position. In forward layout, scrollOffset=0 lands the row's top
 * at the viewport top — no further work needed. In reverseLayout, scrollOffset=0
 * lands the row BOTTOM-anchored (the row's bottom flush with the viewport bottom,
 * top either in the lower viewport for short rows or above the viewport for tall
 * rows). From that known starting position, a single `scrollBy(viewport -
 * item.size)` shifts the row's top up to the viewport top — positive shift (items
 * move UP visually) for short rows, negative shift (items move DOWN visually) for
 * tall rows.
 *
 * Uses `scroll { scrollBy }` when [instant] is true (cold-entry reveal) and
 * `animateScrollBy` otherwise (runtime jumps — deep-link, quote-tap, jump-pill).
 *
 * Waits for the row to be measured between the two scrolls via snapshotFlow so we
 * read the post-scroll item size, not a stale value from before the bring-into-
 * view scroll landed.
 */
private suspend fun LazyListState.forceTopAlign(itemIndex: Int, instant: Boolean) {
    scrollToItem(itemIndex, 0)
    val item = withTimeoutOrNull(SCROLL_TOP_ALIGN_MEASURE_TIMEOUT_MS) {
        snapshotFlow {
            layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        }.filterNotNull().first()
    } ?: return
    if (!layoutInfo.reverseLayout) return // Forward: scrollToItem(idx, 0) already top-aligned.
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val delta = (viewport - item.size).toFloat()
    if (delta == 0f) return
    if (instant) scroll { scrollBy(delta) } else animateScrollBy(delta)
}

/**
 * Safety-net cap on how long [scrollToTopAligned] / [scrollToBoundary] wait for the
 * target row to be measured after the initial bring-into-view scroll. 500 ms is well
 * past the normal one-layout-pass turnaround (~16 ms at 60 Hz) and clamps the
 * pathological case where the row never measures (it was filtered out between the
 * scrollToItem and the next layout pass, etc.) so the caller's coroutine doesn't
 * hang indefinitely.
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
