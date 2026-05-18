package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.continueReadingIndex
import kotlinx.collections.immutable.PersistentList

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
 * Resolving while [historyLoading] is true is the cold-entry gate. The
 * cold-start [dev.lyo.hortay.data.posts.PostsRepository.refreshLocked]
 * harvest populates exactly ONE post per channel (from `Chat.lastMessage`,
 * for the home feed). Without this gate, opening that channel from a feed
 * post mounted the LazyColumn with that single post, and ~300-800 ms later
 * the head-load result merged in above (asc-by-date sort). The LazyColumn's
 * key-anchor preserved the visible row while a wall of older content popped
 * in above it — visible to the user as a jarring jump right after open.
 * Gating on `historyLoading` keeps the channel in Resolving until the deep
 * load has landed, so the LazyColumn mounts ONCE with the full slice at
 * the correct index.
 *
 * Two mechanisms keep this from flashing a skeleton on warm/fast paths:
 *   - [dev.lyo.hortay.ui.timeline.ChannelViewModel] seeds `_historyLoading`
 *     synchronously from
 *     [dev.lyo.hortay.data.posts.PostsRepository.hasWarmChannelHistory],
 *     so warm re-entries (cooldown active = full slice already in memory)
 *     start with `historyLoading = false` and land Ready on frame one.
 *   - The screen-side [dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS] (120 ms)
 *     anti-flicker grace in [ChannelScreen] suppresses the SkeletonFeed for
 *     sub-120 ms Resolving→Ready transitions, so even cold paths only paint
 *     a skeleton on genuinely slow loads.
 *
 * Deep-link paths still gate correctly: the `scrollToMessageId` branch
 * below returns Resolving when the target hasn't landed in `items` yet
 * regardless of `historyLoading`, so we never paint a wrong-index Ready.
 *
 * [searchActive] suppresses deep-link landing — search results need their
 * own context, not a deep-link anchor.
 *
 * [chatId] is the owning channel's id, used by [resolveTargetIndex] to match
 * posts in [items]. Pass the VM's [ChannelViewModel.chatId] — required, no default,
 * so the call site can't accidentally fall through to `0L` when [items] is empty
 * (which would silently miss every deep-link target).
 *
 * [feedOrder] + [cursors] drive the cold-entry boundary for
 * [FeedOrder.OldestUnreadFirst]: in that mode, with no deep-link target and
 * outside search, [Ready.initialIndex] lands at the first FeedItem containing
 * an unread post (asc-by-date sort = oldest unread at the top of the unread
 * block, chat-app idiom). Falls back to `lastIndex` ("caught up, here's the
 * latest") when nothing is unread. Default [cursors] = [EmptyReadCursors]
 * keeps the Newest-mode call sites unchanged.
 */
internal fun buildChannelUiState(
    items: PersistentList<FeedItem>,
    historyLoading: Boolean,
    scrollToMessageId: Long?,
    attemptedAround: Boolean,
    searchActive: Boolean,
    chatId: Long,
    feedOrder: FeedOrder = FeedOrder.Newest,
    cursors: ReadCursors = EmptyReadCursors,
): ChannelUiState {
    if (historyLoading) return ChannelUiState.Resolving
    if (scrollToMessageId == null || searchActive) {
        val initialIndex = if (feedOrder == FeedOrder.OldestUnreadFirst && items.isNotEmpty()) {
            val anchorPosts = items.map { it.posts().first() }
            val boundary = continueReadingIndex(feedOrder, anchorPosts, cursors)
            if (boundary >= 0) boundary else items.lastIndex.coerceAtLeast(0)
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
 * Composable wrapper for [ChannelUiState] latching. Reset on [routeKey] change
 * (e.g. user navigates to a different channel).
 *
 * Initial value is seeded from the first [candidate] the composable receives
 * — NOT a static [ChannelUiState.Resolving] sentinel. The previous shape
 * unconditionally initialised the latch as Resolving and relied on
 * [LaunchedEffect] to overwrite it with the real candidate after the commit
 * phase — but [LaunchedEffect] runs on the NEXT frame, so the first
 * composition always rendered as Resolving regardless of what the caller
 * passed in. With the wait-for-content preload populating the per-channel
 * slice before [ChannelScreen] mounts, that one-frame stall surfaced as a
 * white card "карточка пост появляється з затримкою, спочатку біле" — the
 * Scaffold painted its surface background under a blank body for one frame,
 * then the latch caught up and the LazyColumn paints over it.
 *
 * Seeding from the first candidate makes Ready a frame-one state when the
 * upstream pipeline ([primeChannelForOpen] + the [ChannelViewModel.posts]
 * StateFlow's synchronous initial value) already has a warm slice. The
 * [LaunchedEffect] keeps doing its job on subsequent emissions — passing
 * `reduceChannelUiState(previous, candidate)` preserves the latched
 * initialIndex through Ready→Ready transitions exactly as before.
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
