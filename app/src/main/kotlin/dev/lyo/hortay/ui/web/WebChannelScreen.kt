@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.isUnreadIn
import dev.lyo.hortay.data.orderedFor
import dev.lyo.hortay.ui.timeline.alignedScrollOffset
import dev.lyo.hortay.ui.timeline.reverseLayout
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.timeline.ChannelHeaderAvatar
import dev.lyo.hortay.ui.timeline.ChannelHeaderBar
import dev.lyo.hortay.ui.timeline.ChannelTopBarColumn
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions

/**
 * Guest-mode single-channel view. Mirrors the TDLib-mode
 * [dev.lyo.hortay.ui.timeline.ChannelScreen] architecturally (dedicated screen,
 * not a feed filter) but with a much lighter feature set because the guest data
 * path is intrinsically simpler:
 *
 *   - No per-channel pagination RPC — [WebFeedSource] polls each subscribed
 *     channel's full visible head; the user already sees everything we have
 *     on disk.
 *   - No in-channel search bar — the cross-channel [WebSearchScreen] overlay
 *     already serves that role and works equally well over a single-channel
 *     subset (the user types the channel's distinctive vocabulary anyway).
 *   - No info sheet for v1 — guest mode has no join/mute/notifications
 *     affordances that would populate it; the unsubscribe action lives on
 *     [WebChannelsScreen]. Title-tap opens the public t.me page so the
 *     channel handle reads as a regular external link, mirroring every
 *     other channel-handle target across the app.
 *
 * Header chrome and avatar rendering are shared with the TDLib mode via
 * [ChannelHeaderBar] + [ChannelHeaderAvatar.WebUrl] — visual identity stays
 * in lockstep across modes so the user sees one consistent vocabulary.
 *
 * Data source: reads posts via [dev.lyo.hortay.data.web.WebRepository.observeFeedByChannel]
 * (per-channel DAO) rather than filtering the global feed StateFlow. The global
 * feed is `LIMIT 1000` across every subscribed channel; for a low-volume channel
 * sitting under several high-volume ones the slice would tail off well before
 * the channel's actual local history ended, and the user would silently see a
 * truncated view of their own subscribed channel. Per-channel queries hit the
 * `(channel_username, published_at_ms DESC)` composite index and return the
 * full per-channel slice up to the same 1000-row cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebChannelScreen(
    username: String,
    graph: AppGraph,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /**
     * Called when the user taps a post body in guest mode. Comments aren't
     * available without a TDLib session (no [dev.lyo.hortay.data.CommentsRepository]),
     * so the host scaffold surfaces a snackbar instead of opening a dead screen.
     */
    onPostClick: (dev.lyo.hortay.data.TimelinePost) -> Unit = {},
    /**
     * Per-user feed ordering, from [dev.lyo.hortay.data.SettingsStore.feedOrder].
     * Mirrors the contract of the TDLib-mode ChannelScreen — OldestUnreadFirst
     * flips the channel into the reverse-feed layout (asc-by-date, read above
     * unread) and the cold-entry effect below lands the user at the read→unread
     * boundary so the channel opens "where you left off".
     */
    feedOrder: FeedOrder = FeedOrder.OldestUnreadFirst,
) {
    val bookmarks = graph.bookmarkStore
    val perChannelPosts by remember(username) {
        graph.webRepository.observeFeedByChannel(username)
    }.collectAsStateWithLifecycle(initialValue = persistentListOf())
    val channels by graph.webFeedSource.channels.collectAsStateWithLifecycle()
    val bookmarkedKeys by bookmarks.bookmarks.collectAsStateWithLifecycle(initialValue = emptySet())
    val viewer = LocalMediaViewer.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val chatId = remember(username) { WebPostAdapter.stableChatId(username) }
    val channelInfo = remember(channels, username) {
        channels.firstOrNull { it.info.username.equals(username, ignoreCase = true) }?.info
    }
    // Already filtered at the SQL layer to channel_username = :username; just
    // apply the user's feed order on top.
    val orderedPosts = remember(perChannelPosts, feedOrder) {
        perChannelPosts.orderedFor(feedOrder)
    }

    val listState = rememberLazyListState()

    // Cold-entry scroll for OldestUnreadFirst (chat-app idiom). Same contract
    // and same descending+reverseLayout model as ChannelScreen: data is newest-
    // first (index 0), the LazyColumn flips to reverseLayout so newest sits at
    // the bottom. Fires once per WebChannelScreen instance, only when the
    // LazyListState is at its default 0/0 (respect any saveable restore from a
    // drill-out/drill-in). Boundary picker: the OLDEST unread per
    // [LocalReadCursors] = the highest-index unread = indexOfLast (the resume
    // boundary); fallback to index 0 (newest, at the bottom under reverseLayout)
    // when caught up. Guest mode has no per-channel async load step, so we wait
    // only for the list to become non-empty.
    val cursorHolder = LocalReadCursors.current
    val orderedPostsState = rememberUpdatedState(orderedPosts)
    LaunchedEffect(chatId, feedOrder) {
        if (feedOrder != FeedOrder.OldestUnreadFirst) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            return@LaunchedEffect
        }
        snapshotFlow { orderedPostsState.value.size }.first { it > 0 }
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            return@LaunchedEffect
        }
        val items = orderedPostsState.value
        val cursors = cursorHolder.snapshot()
        val boundary = items.indexOfLast { it.isUnreadIn(cursors) }
        val target = if (boundary >= 0) boundary else 0
        if (target > 0) {
            // Bring the boundary on-screen, then align it. Under reverseLayout a
            // plain scrollToItem leaves the boundary glued to the viewport's
            // BOTTOM edge with the unread queue stranded off-screen below it;
            // re-reading layoutInfo after the first scroll gives the measured
            // height alignedScrollOffset needs to centre it (or top-align a post
            // taller than the viewport).
            listState.scrollToItem(target)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                listState.scrollToItem(
                    target,
                    alignedScrollOffset(
                        viewport = info.viewportEndOffset - info.viewportStartOffset,
                        itemSize = item.size,
                        reverseLayout = info.reverseLayout,
                    ),
                )
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val onPostClickState = rememberUpdatedState(onPostClick)

    val interactions = remember(viewer, bookmarks, chatId, uriHandler) {
        PostInteractions(
            onMediaClick = { post, idx -> viewer.openFor(post.content, idx) },
            // Same-channel header tap inside a post is a no-op — we're already
            // here. Cross-channel jumps in guest mode go through the URI handler
            // (no in-app channel-screen drill for arbitrary handles yet).
            onChannelClick = { post ->
                if (post.chatId != chatId) {
                    val handle = post.senderHandle?.removePrefix("@")
                    if (!handle.isNullOrBlank()) {
                        uriHandler.openUri("https://t.me/${handle}")
                    }
                }
            },
            onBookmarkClick = { post -> scope.launch { bookmarks.toggle(post) } },
            isBookmarked = { post -> bookmarkedKeys.contains("${post.chatId}:${post.id}") },
            // Wire post-body tap through to the scaffold so it can show the
            // "коментарі недоступні" snackbar. Without this, tapping the body
            // is silently dead (the default no-op).
            onPostClick = { post -> onPostClickState.value(post) },
        )
    }

    val title = channelInfo?.title?.takeIf { it.isNotBlank() } ?: "@$username"
    val subtitle = channelInfo?.subscribers
        ?.let { stringResource(R.string.web_subscribers, it) }
        ?: "@$username"

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Wrap ChannelHeaderBar in [ChannelTopBarColumn] the same way TDLib-mode
            // ChannelScreen does. ChannelHeaderBar itself passes
            // windowInsets = WindowInsets(0,0,0,0) so it can be hosted inside
            // surfaces that own the system-bar inset themselves; the wrapper owns
            // the inset (without it the title row overlaps the status-bar clock)
            // AND the scrolled tint across the status-bar band (guest parity).
            ChannelTopBarColumn(scrollBehavior = scrollBehavior) {
                ChannelHeaderBar(
                    titleText = title,
                    subtitleText = subtitle,
                    avatar = ChannelHeaderAvatar.WebUrl(
                        url = channelInfo?.avatarUrl,
                        name = title,
                    ),
                    onBack = onBack,
                    onTitleTap = { uriHandler.openUri("https://t.me/$username") },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val mergedPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + contentPadding.calculateBottomPadding(),
        )
        LazyColumn(
            state = listState,
            reverseLayout = feedOrder.reverseLayout,
            contentPadding = mergedPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = orderedPosts, key = { it.id }) { post ->
                PostCard(post = post, interactions = interactions)
            }
        }
    }
}
