package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.isUnreadIn
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
 * [continueReadingIndex] returns -1 when caught up. Both orders map that to
 * index 0: post data is always descending (newest = index 0), and the
 * LazyColumn uses reverseLayout=true so index 0 appears at the bottom of
 * the viewport — the "you're caught up, here's the latest" landing.
 */
fun buildTimelineUiState(
    items: PersistentList<FeedItem>,
    cursorsLanded: Boolean,
    frozenCursors: ReadCursors,
    feedOrder: FeedOrder,
    refreshing: Boolean,
    minUnreadDate: Long = 0L,
): TimelineUiState {
    if (items.isEmpty()) {
        return if (refreshing) TimelineUiState.Loading else TimelineUiState.Empty
    }
    if (feedOrder == FeedOrder.OldestUnreadFirst && !cursorsLanded) {
        return TimelineUiState.Loading
    }
    // Row-space scan over [items] (which may include a [FeedItem.Boundary]
    // divider inserted by [withBoundary]). The boundary row carries no post
    // and is treated as "not unread"; only [FeedItem.Post] rows contribute
    // to the scan. Album anchor semantics ([isUnreadIn] reads the highest
    // member id) flow through unchanged. [minUnreadDate] is the recency
    // floor — older unread posts don't qualify as landing targets; see
    // [continueReadingIndex]'s KDoc for the dormant-channel rationale.
    //
    // Both FeedOrder values use [indexOfLast] — same semantics as the original
    // [continueReadingIndex] on TimelinePost. Data is descending (newest = idx 0),
    // so the boundary = oldest unread = the LAST qualifying row.
    val qualifies: (FeedItem) -> Boolean = { row ->
        val post = (row as? FeedItem.Post)?.post
        post != null &&
            post.isUnreadIn(frozenCursors) &&
            (minUnreadDate <= 0L || post.date >= minUnreadDate)
    }
    val boundary = items.indexOfLast(qualifies)
    val initialIndex = if (boundary >= 0) boundary else 0
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
 * auto-scroll — they only update [items]. LazyColumn's keyed-scroll
 * preservation handles the visual anchor: every feed row is keyed through
 * [FeedItem.key] which is stable across every ingest path
 * ([PostsRepository.loadChannelHistory] backfills, [TdApi.UpdateNewMessage]
 * arrivals, album coalesce upgrades), so the previous-emission's
 * `firstVisibleItemKey` reliably resolves in the new emission and the user's
 * scroll position stays anchored without any overlay-window freeze.
 */
internal fun reduceTimelineUiState(
    previous: TimelineUiState?,
    candidate: TimelineUiState,
    refreshJustCompleted: Boolean,
): TimelineUiState {
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
): TimelineUiState {
    // Initialise with the current [candidate], NOT a hardcoded Loading. The
    // hardcoded variant flashed a one-frame Skeleton on every scope swap
    // (Archive ↔ All, folder ↔ folder) because `scopeKey` is part of
    // [routeKey]: a swap resets this `remember`, the composition paints
    // Loading immediately, and the LaunchedEffect-driven reducer only
    // writes the real Ready value on the NEXT frame. Seeding the state
    // with `candidate` lets the first frame paint Ready directly when
    // the builder already produced one — which it does as soon as
    // `cursorsHaveLanded` is true for the route (and on a scope swap
    // that gate is already long-stable).
    val effective = remember(routeKey) {
        mutableStateOf(candidate)
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
