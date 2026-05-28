package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.isUnreadIn
import kotlinx.collections.immutable.PersistentList

/**
 * Channel data state — single source of truth for what the channel screen
 * renders. Sealed union, exposed by [ChannelViewModel.data] as a SINGLE
 * StateFlow so Compose can only ever observe a consistent snapshot. The
 * previous design exposed two flows (`posts` and `historyLoading`) and a
 * Choreographer frame could land between their updates, painting Ready
 * with a stale single-cold-harvest slice for one frame before the deep
 * slice propagated. With a sealed union the inconsistent state is not
 * representable: `Loaded` carries its own posts list, and you can't be
 * `Loading` while also having data.
 *
 * Transitions:
 *   - [Loading] → [Loaded] once
 *     [dev.lyo.hortay.data.posts.PostsRepository.loadChannelHistory]
 *     completes (cold first entry). The transition is a single atomic
 *     write to the underlying MutableStateFlow, sliced from
 *     [dev.lyo.hortay.data.posts.PostsRepository.posts] at the same instant.
 *   - [Loaded] → [Loaded] on every live ingest (UpdateNewMessage,
 *     interaction updates) — same state-class, new `posts` list.
 *   - No [Loaded] → [Loading]. PTR / refresh failures keep the stale
 *     content visible; a separate `refreshing` flag drives the PTR ring.
 */
@Immutable
sealed interface ChannelData {
    @Immutable data object Loading : ChannelData

    @Immutable
    data class Loaded(val posts: PersistentList<TimelinePost>) : ChannelData
}

/**
 * State for a single-channel screen. Discriminated union:
 *
 *   • [Resolving] — first-paint gate. Either history is still loading, or
 *     a deep-link scrollToMessageId hasn't been found / fetched yet. UI
 *     renders a SkeletonFeed so the channel's head post never flashes
 *     before the target one (the bug reported as "another post for half a
 *     second").
 *   • [Ready]     — items non-empty, initial index resolved. The
 *     [highlightedMessageId] is non-null on deep-link landings so the
 *     PostCard can pulse a brief highlight after the scroll.
 *   • [Missing]   — deep-link target couldn't be located after the
 *     around-load attempt. UI shows a snackbar and falls back to the
 *     channel's normal newest-first view at index 0.
 *
 * Search-mode override: when the user activates in-channel search, the
 * deep-link initial anchor is suppressed — search results are their own
 * context and landing in them would be confusing. Builder returns
 * Ready(initialIndex = 0, highlightedMessageId = null) in that case.
 */
@Immutable
sealed interface ChannelUiState {
    @Immutable data object Resolving : ChannelUiState

    @Immutable
    data class Ready(
        val items: PersistentList<FeedItem>,
        val initialIndex: Int,
        val highlightedMessageId: Long?,
    ) : ChannelUiState

    @Immutable data object Missing : ChannelUiState
}

/**
 * Pure builder. The around-load attempt is signaled via [attemptedAround]:
 * the VM flips this true after `loadHistoryAround` has been issued and its
 * result has reached the posts flow (or timed out). Until then we stay in
 * Resolving — never fall through to Ready with a wrong index.
 *
 * Resolving while [data] is [ChannelData.Loading] is the cold-entry gate.
 * The cold-start
 * [dev.lyo.hortay.data.posts.PostsRepository.refreshLocked] harvest
 * populates exactly ONE post per channel (from `Chat.lastMessage`, for the
 * home feed). Without this gate, opening that channel from a feed post
 * mounted the LazyColumn with that single post, and ~300-800 ms later the
 * head-load result merged in above (asc-by-date sort). The LazyColumn's
 * key-anchor preserved the visible row while a wall of older content
 * popped in above it — visible to the user as a jarring jump right after
 * open. Routing through the [ChannelData] sealed union means the channel
 * stays in Resolving until the slice has landed, so the LazyColumn mounts
 * ONCE with the full items at the correct index.
 *
 * Two mechanisms keep this from flashing a skeleton on warm/fast paths:
 *   - [dev.lyo.hortay.ui.timeline.ChannelViewModel] seeds [data]
 *     synchronously from
 *     [dev.lyo.hortay.data.posts.PostsRepository.hasWarmChannelHistory],
 *     so warm re-entries (cooldown active = full slice already in memory)
 *     start as [ChannelData.Loaded] and land Ready on frame one.
 *   - The screen-side [dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS] (120 ms)
 *     anti-flicker grace in [ChannelScreen] suppresses the SkeletonFeed
 *     for sub-120 ms Resolving→Ready transitions, so even cold paths only
 *     paint a skeleton on genuinely slow loads.
 *
 * Deep-link paths still gate correctly: the `scrollToMessageId` branch
 * below returns Resolving when the target hasn't landed in `items` yet
 * regardless of [data], so we never paint a wrong-index Ready.
 *
 * [searchActive] suppresses deep-link landing — search results need their
 * own context, not a deep-link anchor.
 *
 * [chatId] is the owning channel's id, used by [resolveTargetIndex] to
 * match posts in [items]. Pass the VM's [ChannelViewModel.chatId] —
 * required, no default, so the call site can't accidentally fall through
 * to `0L` when [items] is empty (which would silently miss every
 * deep-link target).
 *
 * [feedOrder] + [cursors] drive the cold-entry boundary for
 * [FeedOrder.OldestUnreadFirst]: in that mode, with no deep-link target
 * and outside search, [Ready.initialIndex] lands at the read→unread
 * boundary via [continueReadingIndex]. Post data is always DESCENDING
 * (newest = index 0), so that boundary is the OLDEST unread = the
 * highest-index unread (`indexOfLast`); reverseLayout=true then renders it
 * as the chat-app "resume here, newer below" anchor. Falls back to index 0
 * (newest; sits at the bottom under reverseLayout) when nothing is unread —
 * "caught up, here's the latest". Default [cursors] = [EmptyReadCursors]
 * keeps the Newest-mode call sites unchanged.
 */
internal fun buildChannelUiState(
    data: ChannelData,
    items: PersistentList<FeedItem>,
    scrollToMessageId: Long?,
    attemptedAround: Boolean,
    searchActive: Boolean,
    chatId: Long,
    feedOrder: FeedOrder = FeedOrder.Newest,
    cursors: ReadCursors = EmptyReadCursors,
    recencyCutoffMs: Long = 0L,
): ChannelUiState {
    if (data is ChannelData.Loading) return ChannelUiState.Resolving
    if (scrollToMessageId == null || searchActive) {
        val initialIndex = if (feedOrder == FeedOrder.OldestUnreadFirst && items.isNotEmpty()) {
            // Row-space scan over [items], which may include a [FeedItem.Boundary]
            // divider inserted by [withBoundary]. Boundary rows carry no post and
            // never qualify as a landing target; only [FeedItem.Post] rows
            // contribute. Album anchor semantics ([isUnreadIn] reads the highest
            // member id) flow through unchanged. [recencyCutoffMs] is the recency
            // floor — older unread posts don't qualify; mirrors
            // [continueReadingIndex]'s dormant-channel rationale.
            //
            // [indexOfLast] matches the descending-data semantics of the original
            // [continueReadingIndex] call this replaced: data is newest→oldest, so
            // the boundary = oldest unread = the LAST qualifying row.
            val qualifies: (FeedItem) -> Boolean = { row ->
                val post = (row as? FeedItem.Post)?.post
                post != null &&
                    post.isUnreadIn(cursors) &&
                    (recencyCutoffMs <= 0L || post.date >= recencyCutoffMs)
            }
            val boundary = items.indexOfLast(qualifies)
            if (boundary >= 0) boundary else 0
        } else {
            0
        }
        return ChannelUiState.Ready(
            items = items,
            initialIndex = initialIndex,
            highlightedMessageId = null,
        )
    }
    val idx = resolveTargetIndex(items, chatId = chatId, messageId = scrollToMessageId)
    if (idx >= 0) {
        return ChannelUiState.Ready(
            items = items,
            initialIndex = idx,
            highlightedMessageId = scrollToMessageId,
        )
    }
    return if (attemptedAround) ChannelUiState.Missing else ChannelUiState.Resolving
}

/**
 * Pure reducer: one-shot latching for [ChannelUiState.Ready.initialIndex]
 * and [highlightedMessageId]. Mirrors [reduceTimelineUiState]. Channel
 * screens don't have a refresh-relaunch concept like the all-feed PTR —
 * the only re-latch trigger is route-key change (handled by remember key
 * in the caller).
 */
internal fun reduceChannelUiState(
    previous: ChannelUiState?,
    candidate: ChannelUiState,
): ChannelUiState {
    if (previous is ChannelUiState.Ready && candidate is ChannelUiState.Ready) {
        return candidate.copy(
            initialIndex = previous.initialIndex,
            highlightedMessageId = previous.highlightedMessageId,
        )
    }
    return candidate
}

/**
 * Composable wrapper for [ChannelUiState] latching. Reset on [routeKey]
 * change (e.g. user navigates to a different channel).
 *
 * Initial value is seeded from the first [candidate] the composable
 * receives — NOT a static [ChannelUiState.Resolving] sentinel. With
 * [ChannelViewModel.data] starting as [ChannelData.Loaded] on warm
 * re-entry, the first candidate is already Ready and the LazyColumn
 * paints content on frame one. The [LaunchedEffect] keeps doing its job
 * on subsequent emissions — passing `reduceChannelUiState(previous,
 * candidate)` preserves the latched initialIndex through Ready→Ready
 * transitions exactly as before.
 */
@Composable
internal fun rememberLatchedChannelUiState(
    candidate: ChannelUiState,
    routeKey: Any,
): ChannelUiState {
    val effective = remember(routeKey) {
        mutableStateOf<ChannelUiState>(candidate)
    }
    LaunchedEffect(candidate, routeKey) {
        effective.value = reduceChannelUiState(effective.value, candidate)
    }
    return effective.value
}
