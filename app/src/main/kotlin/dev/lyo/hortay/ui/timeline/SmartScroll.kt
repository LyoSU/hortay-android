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
