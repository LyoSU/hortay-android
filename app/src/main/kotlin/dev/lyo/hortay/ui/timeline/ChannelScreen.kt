@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lyo.hortay.R
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.isUnplayableVideo
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.channels.ChannelInfoSheet
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.rememberFloatingTopBarBehavior
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1 even though Flow.debounce(Duration) graduated.
// Remove only when the Long overload is stabilised upstream.

/**
 * Single-channel post feed. Replaces the `channelFilter != null` branch that used to
 * live inside [TimelineScreen] — each channel now gets a proper dedicated Composable
 * with its own [ChannelViewModel] instance (keyed on [chatId]) and its own list state,
 * so navigating between channels or back to the all-feed never shares stale scroll /
 * search / loading state.
 *
 * Top bar follows the same two-zone status-bar pattern as [TimelineScreen] and
 * [CommentsScreen]: a persistent background strip for the system status bar (zone 1),
 * then the floating-bar layout-shrinker (zone 2). [rememberFloatingTopBarBehavior]
 * with `enabled = { !searchActive }` keeps the bar pinned while the BasicTextField is
 * visible — identical semantics to the old in-channel search pin in [TimelineScreen].
 *
 * When [searchActive] is true, a Compact bar with a BasicTextField replaces the normal
 * Medium bar via a `when { }` switch — mirrors the pattern from [TimelineScreen]'s
 * [TimelineTopBar].
 *
 * Scroll-to-message, highlight, read-ack, pagination, and prefetch all follow the
 * corresponding [TimelineScreen] patterns verbatim; differences are called out in
 * their inline comments.
 *
 * @param onChannelOpen Called when the user taps a channel header or forward source
 *   that refers to a DIFFERENT channel than the one currently displayed. The back-stack
 *   router in [MainScaffold] pushes the new chatId and creates a fresh [ChannelScreen].
 *   Same-channel taps are no-ops (already here).
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ChannelScreen(
    chatId: Long,
    repo: PostsRepository,
    commentsRepo: CommentsRepository,
    translations: TranslationsStore,
    channelActions: ChannelActionsRepository,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenComments: (TimelinePost) -> Unit,
    onChannelOpen: (Long) -> Unit,
    scrollToMessage: Pair<Long, Long>? = null,
    onScrollHandled: () -> Unit = {},
    onScrollMissed: () -> Unit = {},
) {
    // Per-channel VM. viewModel() keys the instance by (class, key), so each chatId
    // gets its own VM rather than sharing the all-feed TimelineViewModel. The factory is
    // consulted only on first creation — once the VM is live, subsequent compositions
    // with the same key reuse the existing instance regardless of the factory parameter.
    val vm: ChannelViewModel = viewModel(
        key = "channel:$chatId",
        factory = remember(repo, bookmarks, chatId) {
            viewModelFactory { initializer { ChannelViewModel(repo, bookmarks, chatId) } }
        },
    )

    val posts by vm.posts.collectAsStateWithLifecycle()
    val historyLoading by vm.historyLoading.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val channelTitle by vm.channelTitle.collectAsStateWithLifecycle()
    val channelSubscribers by vm.channelSubscribers.collectAsStateWithLifecycle()
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()
    val searchActive by vm.searchActive.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val translationsMap = translations.translations.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val viewer = LocalMediaViewer.current

    // Info sheet: hoisted local state, dismissed by setting false.
    var infoSheetVisible by remember { mutableStateOf(false) }

    // Scroll state. Each ChannelScreen instance gets its own rememberLazyListState()
    // scoped by the parent SaveableStateProvider(key = "feed-channel:<chatId>") in
    // MainScaffold — reopening a channel restores the exact scroll position.
    val listState = rememberLazyListState()

    // One-shot scroll-to-message request from deep-link or quote-tap.
    var pendingScrollToMessage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // (chatId, messageId) of the post we just scrolled to — drives a brief highlight
    // on that PostCard so the user's eye finds it after the jump.
    var highlightedPostKey by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    LaunchedEffect(scrollToMessage) {
        if (scrollToMessage != null) {
            pendingScrollToMessage = scrollToMessage
            onScrollHandled()
        }
    }

    // Pinned color-only scroll behaviour; height is owned by the floating-bar
    // layout shrinker below. Mirrors CommentsScreen and TimelineScreen.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // Twitter / Instagram floating-bar pattern — scroll delta directly drives
    // the bar's vertical offset. Disabled while search is active (the search
    // TextField is a tool stage that must stay pinned). Same helper used by
    // TimelineScreen and CommentsScreen for consistent motion vocabulary.
    val floatingBar = rememberFloatingTopBarBehavior(enabled = { !searchActive })
    val topBarFullHeightPx = floatingBar.fullHeightPx
    val topBarOffsetPx = floatingBar.offsetPx
    val topBarNestedScroll = floatingBar.nestedScroll

    // Reset the bar to fully visible when the channel context changes.
    LaunchedEffect(chatId) {
        topBarOffsetPx.floatValue = 0f
    }

    // Source-of-truth for what the LazyColumn renders. While search is active
    // we render the raw results list (no threading). Outside search we apply the
    // Threads-style grouping from groupReplies, same as the all-feed path.
    val displayedItems: List<FeedItem> = remember(posts, searchActive, searchResults) {
        if (searchActive) searchResults.map(FeedItem::Single)
        else groupReplies(posts)
    }
    val displayedItemsState = rememberUpdatedState(displayedItems)

    // Resolve the queued scroll-to-message once the target row appears.
    // Mirrors the LaunchedEffect in TimelineScreen verbatim — same TDLib best-
    // practice contract for deep links to older messages.
    LaunchedEffect(pendingScrollToMessage) {
        val (tChatId, messageId) = pendingScrollToMessage ?: return@LaunchedEffect
        var requestedAroundLoad = false
        androidx.compose.runtime.snapshotFlow { displayedItemsState.value }
            .collect { items ->
                val idx = items.indexOfFirst { item ->
                    item.posts().any { p ->
                        p.chatId == tChatId && (p.id == messageId || messageId in p.albumMessageIds)
                    }
                }
                if (idx >= 0) {
                    highlightedPostKey = tChatId to messageId
                    listState.scrollToItem(idx)
                    pendingScrollToMessage = null
                    return@collect
                }
                if (!requestedAroundLoad) {
                    requestedAroundLoad = true
                    val landed = repo.loadHistoryAround(tChatId, messageId)
                    if (!landed) {
                        pendingScrollToMessage = null
                        onScrollMissed()
                        return@collect
                    }
                }
            }
    }

    // Auto-clear the highlight after CHANNEL_HIGHLIGHT_DURATION_MS.
    LaunchedEffect(highlightedPostKey) {
        if (highlightedPostKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(CHANNEL_HIGHLIGHT_DURATION_MS)
        highlightedPostKey = null
    }

    // Pagination: near-bottom snapshotFlow → VM.loadOlderIfPossible(). The VM
    // guards against concurrent calls; the threshold (6 items from the end)
    // matches PAGINATION_PREFETCH_THRESHOLD in TimelineScreen.
    LaunchedEffect(listState, chatId) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to lastVisible
        }
            .distinctUntilChanged()
            .collect { (total, last) ->
                if (total == 0 || last < 0) return@collect
                if (last >= total - CHANNEL_PAGINATION_THRESHOLD) {
                    vm.loadOlderIfPossible()
                }
            }
    }

    // Read-state acks: viewport-stable dwell → viewMessages. Mirrors the feed's
    // READ_DWELL_MS logic, scoped to chatId. The acked set prevents re-issuing for
    // the same posts on every scroll event.
    val ackedRead = remember(chatId) { HashSet<Pair<Long, Long>>() }
    LaunchedEffect(listState, chatId) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collectLatest { indices ->
                if (indices.isEmpty()) return@collectLatest
                kotlinx.coroutines.delay(CHANNEL_READ_DWELL_MS)
                val snapshot = displayedItemsState.value
                if (snapshot.isEmpty()) return@collectLatest
                val visible = indices.flatMap { idx -> snapshot.getOrNull(idx)?.posts().orEmpty() }
                val fresh = visible.filter { (it.chatId to it.id) !in ackedRead }
                if (fresh.isEmpty()) return@collectLatest
                fresh.forEach { ackedRead.add(it.chatId to it.id) }
                val grouped = fresh.groupBy { it.chatId }
                scope.launch {
                    grouped.forEach { (cid, group) ->
                        vm.viewMessages(cid, group.map { it.id })
                    }
                }
            }
    }

    // Comments-thread prefetch: same cap (1) and debounce (1200 ms) as TimelineScreen.
    LaunchedEffect(listState, commentsRepo) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .debounce(CHANNEL_PREFETCH_DEBOUNCE_MS)
            .collect { indices ->
                val snapshot = displayedItemsState.value
                indices.flatMap { snapshot.getOrNull(it)?.posts().orEmpty() }
                    .asSequence()
                    .filter { (it.commentCount ?: 0) > 0 }
                    .take(CHANNEL_COMMENTS_PREFETCH_LIMIT)
                    .forEach { post ->
                        val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                        commentsRepo.prefetchThread(post.chatId, ids)
                    }
            }
    }

    // --- State wrappers for lambdas (same rememberUpdatedState pattern as TimelineScreen) ---
    val bookmarkedState = rememberUpdatedState(bookmarkedKeys)
    val translationsState = rememberUpdatedState(translationsMap)
    val onChannelOpenState = rememberUpdatedState(onChannelOpen)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)
    val markPostReadState = rememberUpdatedState({ post: TimelinePost ->
        val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
        val unacked = ids.filter { (post.chatId to it) !in ackedRead }
        if (unacked.isNotEmpty()) {
            unacked.forEach { ackedRead.add(post.chatId to it) }
            scope.launch { vm.viewMessages(post.chatId, unacked) }
        }
    })

    // Translation lookup helper — same album-scan fallback as TimelineScreen.
    fun lookupTranslation(post: TimelinePost): dev.lyo.hortay.data.FormattedText? {
        val map = translationsState.value
        val lang = translations.currentTargetLanguage()
        map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, post.id, lang)]?.let { return it }
        post.albumMessageIds.forEach { id ->
            map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, id, lang)]?.let { return it }
        }
        return null
    }

    // PostInteractions — keyed on the long-lived dependencies to avoid stale captures
    // across logout/login, mirrors TimelineScreen's keying rationale.
    val interactions = remember(vm, viewer, translations, channelActions, repo, bookmarks) {
        PostInteractions(
            onMediaClick = { post, idx ->
                markPostReadState.value(post)
                val items = (post.content as? dev.lyo.hortay.data.PostContent.PhotoAlbum)?.items.orEmpty()
                if (items.getOrNull(idx)?.isUnplayableVideo == true) {
                    scope.launch { PostActions.openInTelegram(context, repo, post) }
                } else {
                    viewer.openFor(post.content, idx)
                }
            },
            onChannelClick = { post ->
                // Same-channel tap: already here, no-op. Different-channel: drill in.
                if (post.chatId != chatId) onChannelOpenState.value(post.chatId)
            },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceChatId
                    is ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceHandle
                    is ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId)
                    !sourceHandle.isNullOrBlank() -> {
                        // Username-only: route through HortayUriHandler so the public
                        // channel handle resolves via SearchPublicChat.
                        uriHandler.openUri("https://t.me/${sourceHandle.removePrefix("@")}")
                    }
                }
            },
            onQuotedSourceClick = { post ->
                post.reply?.let { r ->
                    if (r.replyToChatId == chatId) {
                        // Same channel: queue a scroll to the target message.
                        pendingScrollToMessage = r.replyToChatId to r.replyToMessageId
                    } else {
                        // Different channel: drill in. MainScaffold owns pendingScrollTarget
                        // routing; the cross-channel scroll will be wired there.
                        onChannelOpenState.value(r.replyToChatId)
                    }
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> scope.launch { PostActions.share(context, repo, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post ->
                markPostReadState.value(post)
                scope.launch { PostActions.openInTelegram(context, repo, post) }
            },
            onTranslateClick = { post ->
                scope.launch {
                    val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                    translations.translate(post.chatId, ids.first())
                }
            },
            onClearTranslationClick = { post ->
                val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                ids.forEach { translations.clear(post.chatId, it) }
            },
            isTranslated = { post -> lookupTranslation(post) != null },
            translationFor = ::lookupTranslation,
            translateEnabled = true,
            onReactionToggle = { post, item ->
                scope.launch {
                    val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                    channelActions.toggleReaction(
                        chatId = post.chatId,
                        messageId = target,
                        kind = item.kind,
                        isChosen = item.isChosen,
                    )
                }
            },
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
            },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(topBarNestedScroll),
        topBar = {
            // Two-zone pattern: zone 1 = persistent status-bar background (never moves),
            // zone 2 = layout-shrunk floating bar (slides on scroll). Same pattern as
            // TimelineScreen and CommentsScreen — see TimelineScreen's topBar comment
            // for the full engineering rationale.
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars),
                )
                Box(
                    modifier = Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            if (topBarFullHeightPx.floatValue == 0f && placeable.height > 0) {
                                topBarFullHeightPx.floatValue = placeable.height.toFloat()
                            }
                            val offset = topBarOffsetPx.floatValue.toInt()
                            val height = (placeable.height + offset).coerceAtLeast(0)
                            layout(placeable.width, height) {
                                placeable.placeRelative(0, offset)
                            }
                        },
                ) {
                    ChannelTopBar(
                        channelTitle = channelTitle,
                        channelSubscribers = channelSubscribers,
                        searchActive = searchActive,
                        searchQuery = searchQuery,
                        onBack = onBack,
                        onSearchToggle = { vm.setSearchActive(!searchActive) },
                        onSearchQueryChange = { vm.setSearchQuery(it) },
                        onInfoClick = { infoSheetVisible = true },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                val displayedList = displayedItems

                when {
                    displayedList.isEmpty() && !refreshing -> {
                        when {
                            searchActive && searchQuery.isNotBlank() -> ChannelSearchEmpty()
                            historyLoading -> ChannelPreviewSkeleton()
                            else -> ChannelEmptyState()
                        }
                    }
                    else -> {
                        // Scroll gate: defer media ensure() while the list is scrolling.
                        // See TimelineScreen for reasoning; identical gate, same intent.
                        val scrollGate = remember(listState) {
                            derivedStateOf { !listState.isScrollInProgress }
                        }

                        // Viewport-centre priority key for the dominant card — same
                        // VisibleCenter promotion as TimelineScreen.
                        val centeredItemKey by remember(listState) {
                            derivedStateOf {
                                val info = listState.layoutInfo
                                val visible = info.visibleItemsInfo
                                if (visible.isEmpty()) return@derivedStateOf null
                                val viewportCenter =
                                    (info.viewportStartOffset + info.viewportEndOffset) / 2
                                visible.minByOrNull { item ->
                                    val itemCenter = item.offset + item.size / 2
                                    kotlin.math.abs(itemCenter - viewportCenter)
                                }?.key
                            }
                        }

                        // Eager prefetch: warm [CHANNEL_PREFETCH_AHEAD] posts ahead of
                        // the viewport at [DownloadPriority.Prefetch] (lane 8). Visible
                        // posts self-ensure at VisibleMedia (16) via rememberMediaBinding,
                        // so the priority gap prevents LIFO contention on the TDLib pool —
                        // same rationale as TimelineScreen's prefetch block.
                        val cache = LocalMediaCache.current
                        val prefetchAnchor by remember(listState) {
                            derivedStateOf {
                                if (listState.isScrollInProgress) null
                                else listState.firstVisibleItemIndex
                            }
                        }
                        LaunchedEffect(prefetchAnchor, displayedList) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= displayedList.size) return@LaunchedEffect
                            val end = (firstVisible + CHANNEL_PREFETCH_AHEAD)
                                .coerceAtMost(displayedList.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val item = displayedList.getOrNull(idx) ?: continue
                                for (post in item.posts()) {
                                    for (fileId in post.content.posterFileIds()) {
                                        cache.ensure(fileId, DownloadPriority.Prefetch)
                                    }
                                    if (idx == firstVisible + 1) {
                                        for (fileId in post.content.playbackFileIds()) {
                                            cache.ensure(fileId, DownloadPriority.Prefetch)
                                        }
                                    }
                                }
                            }
                        }

                        CompositionLocalProvider(LocalScrollGate provides scrollGate) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(items = displayedList, key = { it.key }) { item ->
                                    val isCentered = remember { mutableStateOf(false) }
                                    isCentered.value = item.key == centeredItemKey
                                    val highlighted = highlightedPostKey?.let { (cid, mid) ->
                                        item.posts().any { p ->
                                            p.chatId == cid && (p.id == mid || mid in p.albumMessageIds)
                                        }
                                    } == true
                                    CompositionLocalProvider(
                                        LocalIsCenteredItem provides isCentered,
                                        LocalIsHighlightedItem provides highlighted,
                                    ) {
                                        when (item) {
                                            is FeedItem.Single -> PostCard(
                                                post = item.post,
                                                interactions = interactions,
                                            )
                                            is FeedItem.Thread -> ThreadedPostPair(
                                                parent = item.parent,
                                                reply = item.reply,
                                                interactions = interactions,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (infoSheetVisible) {
        ChannelInfoSheet(
            chatId = chatId,
            actions = channelActions,
            onDismiss = { infoSheetVisible = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * Top bar for the channel screen. Two modes driven by [searchActive]:
 *
 *   - Normal ([searchActive] = false): Medium-size bar with avatar + title row
 *     in the title slot and subscriber count as subtitle. Navigation icon = back.
 *     Actions: search toggle, info.
 *
 *   - Search ([searchActive] = true): Compact bar with a BasicTextField in the title
 *     slot (auto-focuses on mount) and a clear icon when the query is non-empty.
 *     Navigation icon = back (also closes search). Mirrors the in-channel search
 *     bar that previously lived in TimelineScreen's TimelineTopBar.
 *
 * Window insets are [WindowInsets.Zero] here because the caller ([ChannelScreen]) already
 * owns a persistent zone-1 status-bar strip above this composable — passing zero
 * prevents double-padding (same contract as TimelineScreen's TimelineTopBar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelTopBar(
    channelTitle: String?,
    channelSubscribers: Int?,
    searchActive: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onInfoClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val barInsets = WindowInsets(0)
    if (searchActive) {
        HortayTopBar(
            size = HortayTopBarSize.Compact,
            title = {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    stringResource(R.string.timeline_search_in_channel),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Symbol(
                        name = "arrow_back",
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Symbol(
                            name = "close",
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
        )
    } else {
        // Subscriber-count subtitle. The exact formatting (K/M compact vs full number,
        // "підписників" vs "members") is locale-dependent copy that the product owner
        // will finalize — see the TODO. For now we reuse the existing
        // `timeline_subscribers` string + `formatSubscribers` compact formatter which
        // already ships the K/M shorthand. Both live in this package and were designed
        // for the channel top bar.
        //
        // TODO(user): finalize subscriber-count plural/format — decide between:
        //   - "12.3K підписників" (current, compact), or
        //   - "12 345 підписників" (full count for smaller channels), or
        //   - pluralStringResource(R.plurals.channel_subscribers, n, n) for
        //     grammatically correct Ukrainian (1 підписник / 2 підписники / 5 підписників).
        //   Once decided, extract to a plurals resource in values-uk/strings.xml.
        val subtitleText = channelSubscribers?.let {
            stringResource(R.string.timeline_subscribers, formatSubscribers(it))
        }
        HortayTopBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Avatar sourced from the VM's resolved channel title for the
                    // initial-letter. The full 3-tier TdAvatar pyramid (minithumb
                    // → file) would require the chatCache's avatar file id — a
                    // future improvement would fetch that from PostsRepository and
                    // thread it through ChannelViewModel.channelInfo. For now the
                    // initial-letter fallback on a primaryContainer disc is consistent
                    // with how channels without a loaded avatar look in ChannelsScreen.
                    TdAvatar(
                        name = channelTitle ?: "?",
                        thumb = null,
                        fileId = null,
                        size = 32.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = channelTitle.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            subtitleSlot = subtitleText?.let { text ->
                { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            size = HortayTopBarSize.Medium,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Symbol(
                        name = "arrow_back",
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = onSearchToggle) {
                    Symbol(
                        name = "search",
                        contentDescription = stringResource(R.string.action_search),
                    )
                }
                IconButton(onClick = onInfoClick) {
                    Symbol(
                        name = "info",
                        contentDescription = stringResource(R.string.channel_info_action),
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
        )
    }
}

// ---------------------------------------------------------------------------
// Empty-state + skeleton composables
// ---------------------------------------------------------------------------

@Composable
private fun ChannelSearchEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "search_off",
            shape = HortayExpressive.FolderSelected,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.timeline_search_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChannelEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "forum",
            shape = HortayExpressive.EmptyStateMask,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.channel_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.channel_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Loading skeleton for a freshly entered channel — three placeholder cards roughed to the
 * shape of a real [PostCard]. Matches [TimelineScreen]'s ChannelPreviewSkeleton exactly;
 * moved here since it is now only needed in the channel view, not the all-feed.
 *
 * Shimmer is a single infinite Animatable driving the surfaceContainerHighest /
 * surfaceContainerLow alpha sweep — cheap (no allocations per frame, one Animatable for
 * the whole skeleton). MotionScheme isn't used here because the animation is a deliberate
 * slow loop (1.2s linear), not a state-change spring.
 */
@Composable
private fun ChannelPreviewSkeleton() {
    val infinite = rememberInfiniteTransition(label = "preview-skeleton")
    val shimmer by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "preview-skeleton-alpha",
    )
    val shimmerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = shimmer)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) { SkeletonCard(shimmerColor) }
    }
}

@Composable
private fun SkeletonCard(barColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(140.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(barColor),
                )
                Box(
                    modifier = Modifier
                        .height(10.dp)
                        .width(80.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
    }
}

// ---------------------------------------------------------------------------
// Constants local to ChannelScreen
// ---------------------------------------------------------------------------

/** How many items from the end of the channel list trigger an older-history fetch. */
private const val CHANNEL_PAGINATION_THRESHOLD = 6

/** Viewport-stable dwell before marking posts as read. Matches TimelineScreen's READ_DWELL_MS. */
private const val CHANNEL_READ_DWELL_MS = 1000L

/** How long the surface-tint highlight lingers after scroll-to-message. Matches TimelineScreen. */
private const val CHANNEL_HIGHLIGHT_DURATION_MS = 2200L

/** Viewport-stable debounce before triggering comments prefetch. Matches TimelineScreen. */
private const val CHANNEL_PREFETCH_DEBOUNCE_MS = 1200L

/** Cap on prefetchThread fan-out per viewport-stable burst. Matches TimelineScreen. */
private const val CHANNEL_COMMENTS_PREFETCH_LIMIT = 1

/** Posts ahead of first visible to eagerly prefetch. Matches TimelineScreen's PREFETCH_AHEAD. */
private const val CHANNEL_PREFETCH_AHEAD = 2
