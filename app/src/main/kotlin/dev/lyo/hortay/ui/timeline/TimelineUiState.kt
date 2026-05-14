package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * Pure reducer: one-shot latching for [TimelineUiState.Ready.initialIndex] and
 * [TimelineUiState.Ready.frozenCursors]. Why latching exists: the LazyColumn's
 * scroll position is owned by the user the moment first paint lands. Live
 * cursor advances (dwell-acks) and post arrivals must not trigger auto-scroll
 * — they only update the items list.
 *
 * Re-latching on PTR completion (chat-app idiom: pull-to-refresh is an
 * explicit user request for a fresh anchor) is handled by [refreshJustCompleted]
 * — set true on the falling edge of `refreshing` by the caller.
 */
internal fun reduceTimelineUiState(
    previous: TimelineUiState?,
    candidate: TimelineUiState,
    refreshJustCompleted: Boolean,
): TimelineUiState {
    if (refreshJustCompleted) return candidate
    if (previous is TimelineUiState.Ready && candidate is TimelineUiState.Ready) {
        return candidate.copy(
            initialIndex = previous.initialIndex,
            frozenCursors = previous.frozenCursors,
        )
    }
    return candidate
}

/**
 * Composable wrapper: maintain a latched [TimelineUiState] via [remember] +
 * [LaunchedEffect]. Caller passes the live [candidate] from [buildTimelineUiState],
 * the current [refreshing] flag, and a [routeKey] that resets the latch when
 * navigation context changes (e.g. Home ↔ Saved tab switch).
 *
 * Falling-edge detection on `refreshing` produces the [refreshJustCompleted]
 * signal that re-latches at PTR completion.
 */
@Composable
internal fun rememberLatchedTimelineUiState(
    candidate: TimelineUiState,
    refreshing: Boolean,
    routeKey: Any,
): TimelineUiState {
    val effective = remember(routeKey) {
        mutableStateOf<TimelineUiState>(TimelineUiState.Loading)
    }
    val previousRefreshing = remember(routeKey) {
        mutableStateOf(false)
    }
    LaunchedEffect(candidate, refreshing, routeKey) {
        val refreshJustCompleted = previousRefreshing.value && !refreshing
        previousRefreshing.value = refreshing
        effective.value = reduceTimelineUiState(
            previous = effective.value,
            candidate = candidate,
            refreshJustCompleted = refreshJustCompleted,
        )
    }
    return effective.value
}
