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
 * re-latching of [TimelineUiState.Ready.frozenCursors] at PTR completion.
 *
 * Why [initialIndex] is ALWAYS preserved across Ready→Ready transitions: the
 * LazyColumn's scroll position is owned by the user the moment first paint
 * lands. Re-keying [rememberSaveable] on a fresh [initialIndex] would yank the
 * user — explicitly forbidden by the "pull-to-refresh while scrolled preserves
 * position" promise. [refreshJustCompleted] only re-latches [frozenCursors] so
 * the unread-boundary divider can update; the user's scroll anchor is theirs
 * to move via [smartScrollTo] from an explicit user intent (home tap, pill).
 *
 * Live cursor advances (dwell-acks) and post arrivals must not trigger
 * auto-scroll — they only update [items].
 *
 * [preserveReady] freezes the latched Ready in its entirety — including its
 * [items] — while a nav overlay (Comments / ChannelScreen) covers the feed.
 * Rationale: opening a channel from the feed mounts a [ChannelViewModel] whose
 * `init` block issues `loadChannelHistory(chatId)` against [PostsRepository],
 * which writes the deepened slice into the SAME global posts flow that
 * [TimelineScreen] is subscribed to. Without the freeze, the candidate built
 * downstream arrives as a new Ready with a regrouped [items] list — a reply
 * parent surfaced by the deep-dive can promote a `Single` row into a `Thread`
 * member, and new arrivals in Newest mode shift indices. The feed's LazyColumn
 * under the overlay would reconcile that keyed dataset, and when the
 * previously-first-visible key vanished through regrouping its scroll lost the
 * anchor and reset off-position — what the user saw on overlay pop as "back
 * from the channel lands at the start of the feed". Freezing under overlay
 * keeps `firstVisibleItemKey` stable; on dismissal [preserveReady] flips false
 * and the next live emission flows through the normal Ready→Ready branch below
 * — [items] adopt the candidate's, scroll anchor stays at the user's actual
 * position via the [initialIndex] preservation rule. Matches the parallel
 * "read-acks pause under overlay" contract (`markAsRead = null` in
 * [TimelineScreen]) — same "feed is frozen while user is elsewhere" principle.
 */
internal fun reduceTimelineUiState(
    previous: TimelineUiState?,
    candidate: TimelineUiState,
    refreshJustCompleted: Boolean,
    preserveReady: Boolean = false,
): TimelineUiState {
    if (preserveReady && previous is TimelineUiState.Ready) {
        return previous
    }
    if (previous is TimelineUiState.Ready && candidate is TimelineUiState.Ready) {
        return candidate.copy(
            initialIndex = previous.initialIndex,
            frozenCursors = if (refreshJustCompleted) candidate.frozenCursors else previous.frozenCursors,
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
    preserveReady: Boolean = false,
): TimelineUiState {
    val effective = remember(routeKey) {
        mutableStateOf<TimelineUiState>(TimelineUiState.Loading)
    }
    val previousRefreshing = remember(routeKey) {
        mutableStateOf(false)
    }
    LaunchedEffect(candidate, refreshing, routeKey, preserveReady) {
        val refreshJustCompleted = previousRefreshing.value && !refreshing
        previousRefreshing.value = refreshing
        effective.value = reduceTimelineUiState(
            previous = effective.value,
            candidate = candidate,
            refreshJustCompleted = refreshJustCompleted,
            preserveReady = preserveReady,
        )
    }
    return effective.value
}
