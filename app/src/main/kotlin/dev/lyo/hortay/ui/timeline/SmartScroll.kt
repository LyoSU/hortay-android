package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.lazy.LazyListState
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
