@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.lyo.hortay.R
import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.isUnreadIn
import dev.lyo.hortay.data.orderedFor
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.timeline.ChannelHeaderAvatar
import dev.lyo.hortay.ui.timeline.ChannelHeaderBar
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebChannelScreen(
    username: String,
    graph: AppGraph,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /**
     * Per-user feed ordering, from [dev.lyo.hortay.data.SettingsStore.feedOrder].
     * Mirrors the contract of the TDLib-mode ChannelScreen — OldestUnreadFirst
     * flips the channel into the reverse-feed layout (asc-by-date, read above
     * unread) and the cold-entry effect below lands the user at the read→unread
     * boundary so the channel opens "where you left off".
     */
    feedOrder: FeedOrder = FeedOrder.Newest,
) {
    val feedSource = graph.webFeedSource
    val bookmarks = graph.bookmarkStore
    val posts by feedSource.posts.collectAsStateWithLifecycle()
    val channels by feedSource.channels.collectAsStateWithLifecycle()
    val bookmarkedKeys by bookmarks.bookmarks.collectAsStateWithLifecycle(initialValue = emptySet())
    val viewer = LocalMediaViewer.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val chatId = remember(username) { WebPostAdapter.stableChatId(username) }
    val channelInfo = remember(channels, username) {
        channels.firstOrNull { it.info.username.equals(username, ignoreCase = true) }?.info
    }
    // Per-channel slice, sorted by [feedOrder]. orderedFor is a pure function
    // with no cursor dependency (see ReadCursors.kt) — passing EmptyReadCursors
    // keeps the remember key small and avoids re-sorting on every cursor advance.
    val filteredPosts = remember(posts, chatId, feedOrder) {
        posts.filter { it.chatId == chatId }.orderedFor(feedOrder, EmptyReadCursors)
    }

    val listState = rememberLazyListState()

    // Cold-entry scroll for OldestUnreadFirst (chat-app idiom). Same contract
    // as ChannelScreen: fires once per WebChannelScreen instance, only when
    // the LazyListState is at its default 0/0 (respect any saveable restore
    // from a drill-out/drill-in). Boundary picker: first post that's unread
    // per [LocalReadCursors]; fallback to [List.lastIndex] = newest at bottom
    // when caught up. Guest mode has no per-channel async load step, so
    // we wait only for the list to become non-empty.
    val readCursors = LocalReadCursors.current
    val filteredPostsState = rememberUpdatedState(filteredPosts)
    val readCursorsStateForEntry = rememberUpdatedState(readCursors)
    LaunchedEffect(chatId, feedOrder) {
        if (feedOrder != FeedOrder.OldestUnreadFirst) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            return@LaunchedEffect
        }
        snapshotFlow { filteredPostsState.value.size }.first { it > 0 }
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 ||
            listState.firstVisibleItemScrollOffset != 0
        ) {
            return@LaunchedEffect
        }
        val items = filteredPostsState.value
        val cursors = readCursorsStateForEntry.value
        val boundary = items.indexOfFirst { it.isUnreadIn(cursors) }
        val target = if (boundary >= 0) boundary else items.lastIndex
        if (target > 0) listState.scrollToItem(target)
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val mergedPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + contentPadding.calculateBottomPadding(),
        )
        LazyColumn(
            state = listState,
            contentPadding = mergedPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = filteredPosts, key = { it.id }) { post ->
                PostCard(post = post, interactions = interactions)
            }
        }
    }
}
