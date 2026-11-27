@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.web.OlderPageResult
import dev.lyo.hortay.ui.text.LocalShowFullPost
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.timeline.shouldLoadOlder
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.lyo.hortay.ui.timeline.rememberGuestReactionNudge
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.isUnreadIn
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
import dev.lyo.hortay.ui.timeline.FeedItem
import dev.lyo.hortay.ui.timeline.openForwardSource
import dev.lyo.hortay.ui.timeline.rememberReadAckDwell
import dev.lyo.hortay.ui.timeline.PostChain
import dev.lyo.hortay.ui.timeline.withPostChains
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.data.isUnplayableVideo

/**
 * Guest-mode single-channel view. Mirrors the TDLib-mode
 * [dev.lyo.hortay.ui.timeline.ChannelScreen] architecturally (dedicated screen,
 * not a feed filter) but with a much lighter feature set because the guest data
 * path is intrinsically simpler:
 *
 *   - Older history pages in as the reader nears the older edge, like the TDLib
 *     channel: [dev.lyo.hortay.data.web.WebFeedSource.loadOlder] fetches the t.me/s/
 *     page before the oldest stored post. Sweeps only keep each channel's newest
 *     page, so without it the screen ended at whatever they had stored. See
 *     [OlderHistoryEdge] for the trigger and the edge row.
 *   - No in-channel search bar — the cross-channel [WebSearchScreen] overlay
 *     already serves that role and works equally well over a single-channel
 *     subset (the user types the channel's distinctive vocabulary anyway). It
 *     reads the same `post` table, so paged-in older history is searchable too.
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
    /**
     * Opens another channel (forward source, cross-channel reply quote). Same contract as
     * the guest feed's `onChannelOpen`: [WebModeScaffold] drills into a followed channel
     * and falls back to the public t.me page for one the user doesn't follow.
     */
    onChannelOpen: (chatId: Long, messageId: Long?) -> Unit = { _, _ -> },
    /**
     * "Report" from the post menu — [WebModeScaffold] routes it through the same
     * [dev.lyo.hortay.ui.report.GuestReportDelegator] path the guest feed uses.
     */
    onReportClick: (dev.lyo.hortay.data.TimelinePost) -> Unit = {},
    /**
     * Opens the post screen as a hero from the card's on-screen top Y — the same contract
     * as the TDLib ChannelScreen's `onShowFullPost`. Non-null makes a body tap and "Show
     * more" open the post (and lets clamped cards collapse expandable quotes); null keeps
     * [onPostClick] and in-place expansion.
     */
    onShowFullPost: ((dev.lyo.hortay.data.TimelinePost, Int) -> Unit)? = null,
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
    // Already filtered at the SQL layer to channel_username = :username and sorted
    // newest-first; FeedOrder only flips `reverseLayout`.
    val orderedPosts = perChannelPosts

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
    val onChannelOpenState = rememberUpdatedState(onChannelOpen)
    val onReportClickState = rememberUpdatedState(onReportClick)
    val onShowFullState = rememberUpdatedState(onShowFullPost)
    val context = LocalContext.current

    // Rows wrapped as [FeedItem.Post] so the shared [rememberReadAckDwell] can match
    // LazyColumn keys against them — the LazyColumn below keys on [FeedItem.key] too.
    // Guest posts never carry a media-album id ([WebPostAdapter] sets 0), so the key
    // is the per-post `post_<chatId>_<id>` form and stays unique.
    // Reply chains ([withPostChains]) as in the signed-in channel screen.
    val feedItems = remember(orderedPosts, feedOrder) {
        withPostChains(orderedPosts.map { FeedItem.Post(it) }, feedOrder.reverseLayout)
            .filterIsInstance<FeedItem.Post>()
    }

    // Read-ack dwell, shared with the guest feed and the TDLib channel screen: same
    // visibility gates (60 % on screen, list idle, lifecycle RESUMED) and dwell. A
    // guest read is a local cursor bump — [WebRepository.markChannelRead] MAX-clamps
    // the channel's row to the highest seq seen, which clears the unread strip via
    // [LocalReadCursors]. Every post here belongs to [username], so the batch maps to
    // one cursor write. [WebReadAck] also hands the read posts to the view reporter, the
    // same way the guest feed does.
    val markAsRead: suspend (List<dev.lyo.hortay.data.TimelinePost>) -> Unit =
        remember(graph.webReadAck, username) {
            { batch -> graph.webReadAck.onRead(batch, channel = username) }
        }
    val ackedRead = rememberReadAckDwell(
        listState = listState,
        displayedItems = feedItems,
        ackKey = chatId,
        markAsRead = markAsRead,
        scope = scope,
    )
    // Explicit-tap ack (media, open in Telegram, post body) — same as the feed: an
    // action on a post is an unambiguous "I read this", and it suppresses the next
    // dwell pass for that post through the shared dedup set.
    val markPostReadState = rememberUpdatedState({ post: dev.lyo.hortay.data.TimelinePost ->
        if (ackedRead.add(post.chatId to post.id)) {
            scope.launch { markAsRead(listOf(post)) }
        }
    })

    val guestReactionNudge = rememberGuestReactionNudge()
    val interactions = remember(viewer, bookmarks, chatId, uriHandler, guestReactionNudge, context) {
        PostInteractions(
            onReactionToggle = { _, _ -> guestReactionNudge() },
            onMediaClick = { post, idx ->
                markPostReadState.value(post)
                // Unplayable videos ("Media is too big" posts where t.me drops the
                // <video src>) go straight to Telegram, as in the guest feed — the
                // viewer would otherwise open on an empty URL and spin forever.
                val items = (post.content as? dev.lyo.hortay.data.PostContent.PhotoAlbum)?.items.orEmpty()
                if (items.getOrNull(idx)?.isUnplayableVideo == true) {
                    scope.launch { PostActions.openInTelegram(context, null, post) }
                } else {
                    viewer.openFor(post.content, idx)
                }
            },
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
            onForwardSourceClick = { post ->
                openForwardSource(
                    post,
                    openChannel = { id, at -> onChannelOpenState.value(id, at) },
                    openUri = uriHandler::openUri,
                )
            },
            onQuotedSourceClick = { post ->
                post.reply?.let { r ->
                    if (r.replyToChatId == chatId) {
                        // Same channel: scroll to the replied-to post when it is in the
                        // local slice; otherwise open it on t.me, since the guest slice
                        // only holds what the web preview has served.
                        val target = orderedPostsState.value.indexOfFirst { it.id == r.replyToMessageId }
                        if (target >= 0) {
                            scope.launch { listState.animateScrollToItem(target) }
                        } else {
                            uriHandler.openUri("https://t.me/$username/${r.replyToMessageId}")
                        }
                    } else {
                        onChannelOpenState.value(r.replyToChatId, r.replyToMessageId)
                    }
                }
            },
            onBookmarkClick = { post -> scope.launch { bookmarks.toggle(post) } },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedKeys },
            // Guest mode has no ChannelDirectory: PostActions builds the t.me link by hand.
            onShareClick = { post -> scope.launch { PostActions.share(context, null, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onCopyLinkClick = { post -> scope.launch { PostActions.copyLink(context, null, post) } },
            onOpenClick = { post ->
                markPostReadState.value(post)
                scope.launch { PostActions.openInTelegram(context, null, post) }
            },
            onReportClick = { post -> onReportClickState.value(post) },
            // Guest delegation (Telegram app or t.me) is always available.
            canReport = { true },
            // Wire post-body tap through to the scaffold so it can show the
            // "коментарі недоступні" snackbar. Without this, tapping the body
            // is silently dead (the default no-op).
            onPostClick = { post ->
                markPostReadState.value(post)
                onPostClickState.value(post)
            },
            onShowFull = onShowFullPost?.let {
                { post: dev.lyo.hortay.data.TimelinePost, topY: Int ->
                    markPostReadState.value(post)
                    onShowFullState.value?.invoke(post, topY)
                }
            },
        )
    }

    val olderHistory = rememberOlderHistory(username = username, graph = graph, listState = listState)

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
            // Keyed on [FeedItem.key] so the read-ack dwell above can match visible rows.
            items(items = feedItems, key = { it.key }) { item ->
                WebChannelPostItem(
                    post = item.post,
                    itemKey = item.key,
                    chain = item.chain,
                    interactions = interactions,
                )
            }
            // Data is newest-first, so the last item is the older edge in both feed orders
            // (reverseLayout draws it at the top). Older pages land above this row's index,
            // away from the first visible item LazyColumn anchors on, so nothing jumps.
            if (olderHistory.state == OlderHistoryState.Loading || olderHistory.state == OlderHistoryState.Failed) {
                item(key = OLDER_EDGE_KEY, contentType = OLDER_EDGE_KEY) {
                    OlderHistoryEdge(state = olderHistory.state, onRetry = olderHistory::load)
                }
            }
        }
    }
}

/**
 * One post row: captures the card's on-screen top Y so a tap or "Show more" opens the
 * post screen as a hero from where the card sits — the same wiring as the TDLib
 * ChannelScreen / TimelineFeedColumn rows. With no hero handler the row keeps
 * [PostInteractions.onPostClick] and in-place expansion.
 */
@Composable
private fun WebChannelPostItem(
    post: dev.lyo.hortay.data.TimelinePost,
    itemKey: String,
    chain: PostChain,
    interactions: PostInteractions,
) {
    // FloatArray, not State: per-frame position writes must not recompose the card.
    val topY = remember(itemKey) { floatArrayOf(0f) }
    val showFull = remember(post, interactions, topY) {
        interactions.onShowFull?.let { hero -> { hero(post, topY[0].toInt()) } }
    }
    val itemInteractions = remember(post, interactions, topY) {
        val hero = interactions.onShowFull
        if (hero != null) interactions.copy(onPostClick = { hero(post, topY[0].toInt()) }) else interactions
    }
    CompositionLocalProvider(LocalShowFullPost provides showFull) {
        Box(modifier = Modifier.onGloballyPositioned { topY[0] = it.positionInWindow().y }) {
            PostCard(post = post, interactions = itemInteractions, chain = chain)
        }
    }
}

/** Older-edge state of a guest channel screen. */
internal enum class OlderHistoryState { Idle, Loading, Failed, End }

/**
 * Drives [dev.lyo.hortay.data.web.WebFeedSource.loadOlder] for one screen. The request
 * itself runs on the feed source's scope, so leaving the screen only drops the wait;
 * [state] lives in composition and starts [OlderHistoryState.Idle] on every open — an
 * already-reached start of history answers End from the database without a request.
 */
@Stable
internal class OlderHistoryController(
    private val username: String,
    private val graph: AppGraph,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var state by mutableStateOf(OlderHistoryState.Idle)
        private set

    /** Starts a page unless one is loading or the start of history was reached. */
    fun load() {
        if (state == OlderHistoryState.Loading || state == OlderHistoryState.End) return
        state = OlderHistoryState.Loading
        scope.launch {
            state = when (graph.webFeedSource.loadOlder(username)) {
                is OlderPageResult.Loaded -> OlderHistoryState.Idle
                OlderPageResult.End -> OlderHistoryState.End
                OlderPageResult.RateLimited, OlderPageResult.Failed -> OlderHistoryState.Failed
            }
        }
    }
}

/**
 * Pages older history in when the reader comes within [OLDER_PAGINATION_THRESHOLD] rows
 * of the older edge — the same `shouldLoadOlder` trigger and threshold as the TDLib
 * ChannelScreen, so both modes start loading at the same point. Only from
 * [OlderHistoryState.Idle]: a failure waits for the edge row's Retry, never an automatic
 * loop, and the trigger re-arms only when the layout changes (a landed page, a scroll).
 */
@Composable
private fun rememberOlderHistory(
    username: String,
    graph: AppGraph,
    listState: LazyListState,
): OlderHistoryController {
    val scope = rememberCoroutineScope()
    val controller = remember(username, graph, scope) { OlderHistoryController(username, graph, scope) }
    LaunchedEffect(listState, controller) {
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                info.totalItemsCount,
                info.visibleItemsInfo.firstOrNull()?.index ?: -1,
                info.visibleItemsInfo.lastOrNull()?.index ?: -1,
            )
        }
            .distinctUntilChanged()
            .collect { (total, first, last) ->
                if (first < 0) return@collect
                if (controller.state == OlderHistoryState.Idle &&
                    shouldLoadOlder(first, last, total, OLDER_PAGINATION_THRESHOLD)
                ) {
                    controller.load()
                }
            }
    }
    return controller
}

/**
 * The older-edge row: a loading indicator while a page is on its way, a Retry button
 * after a failure (rate limit included). Not composed at all when idle or at the start
 * of history, so the edge reads exactly like the TDLib channel's.
 */
@Composable
private fun OlderHistoryEdge(state: OlderHistoryState, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = HortaySpacing.lg, vertical = HortaySpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (state == OlderHistoryState.Failed) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.web_channel_older_failed), textAlign = TextAlign.Center)
            }
        } else {
            LoadingIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

private const val OLDER_EDGE_KEY = "older_edge"

/** Rows from the older edge that start the next page; matches the TDLib ChannelScreen. */
private const val OLDER_PAGINATION_THRESHOLD = 6
