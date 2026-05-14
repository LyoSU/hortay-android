package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.continueReadingIndex
import kotlinx.collections.immutable.PersistentList

/**
 * Source-of-truth for what TimelineScreen renders. Built from the same
 * `List<FeedItem>` the LazyColumn renders — scope/bookmark/service filters,
 * `orderedFor`, and `groupReplies` have already been applied by the caller.
 *
 * The scroll anchor index lives in row-space (FeedItem indices), not
 * post-space, so first paint of the LazyColumn lands at the correct row
 * in one frame.
 *
 * Three states:
 *   • [Loading] — refresh in flight with empty items, OR reverse-feed items
 *     present but cursors not yet landed. UI renders a [SkeletonFeed].
 *   • [Ready]   — items non-empty, [initialIndex] computed against
 *     [frozenCursors]. The LazyColumn mounts with this initialIndex. Subsequent
 *     emissions update [items] but the caller treats [initialIndex] as
 *     one-shot — it's only consumed at LazyListState construction time.
 *   • [Empty]   — refresh finished, zero items. Render an empty-state hero.
 */
@Immutable
sealed interface TimelineUiState {
    @Immutable data object Loading : TimelineUiState
    @Immutable data object Empty : TimelineUiState

    @Immutable
    data class Ready(
        val items: PersistentList<FeedItem>,
        val initialIndex: Int,
        val frozenCursors: ReadCursors,
    ) : TimelineUiState
}

/**
 * Pure function: derive [TimelineUiState] from already-filtered, already-ordered,
 * already-grouped [items]. Stateless — recomputes [Ready.initialIndex] on
 * every call. The Composable caller is responsible for one-shot latching of
 * the index (only consumed at LazyListState construction) and for capturing
 * [frozenCursors] at the moment of the first Ready transition.
 *
 * Reverse-feed cursors-landed gate: in [FeedOrder.OldestUnreadFirst] mode,
 * rendering items at index 0 (= oldest in asc-by-date sort) before cursors
 * arrive was the source of the "starts on a random ancient post" symptom.
 * Holding Loading until cursorsLanded is the type-level fix.
 *
 * [continueReadingIndex] returns -1 when caught up. We map that to lastIndex
 * for reverse feed (= newest at the bottom, the "you're caught up" landing)
 * and to 0 for Newest (= top of feed, the canonical "newest at top" fallback).
 */
fun buildTimelineUiState(
    items: PersistentList<FeedItem>,
    cursorsLanded: Boolean,
    frozenCursors: ReadCursors,
    feedOrder: FeedOrder,
    refreshing: Boolean,
): TimelineUiState {
    if (items.isEmpty()) {
        return if (refreshing) TimelineUiState.Loading else TimelineUiState.Empty
    }
    if (feedOrder == FeedOrder.OldestUnreadFirst && !cursorsLanded) {
        return TimelineUiState.Loading
    }
    // continueReadingIndex operates on TimelinePost; for albums the anchor
    // post drives the unread check. Flatten items → first post per item, then
    // compute the boundary in row-space.
    val anchorPosts = items.map { it.posts().first() }
    val boundary = continueReadingIndex(feedOrder, anchorPosts, frozenCursors)
    val initialIndex = when (feedOrder) {
        FeedOrder.Newest -> if (boundary >= 0) boundary else 0
        FeedOrder.OldestUnreadFirst -> if (boundary >= 0) boundary else items.lastIndex.coerceAtLeast(0)
    }
    return TimelineUiState.Ready(
        items = items,
        initialIndex = initialIndex,
        frozenCursors = frozenCursors,
    )
}
