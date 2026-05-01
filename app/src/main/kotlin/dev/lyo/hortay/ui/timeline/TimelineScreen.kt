package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.channels.ChannelInfoSheet
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.BrandRow
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1 even though Flow.debounce(Duration) graduated.
// Remove only when the Long overload is stabilised upstream.
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TimelineScreen(
    repo: PostsRepository,
    commentsRepo: CommentsRepository,
    folders: ChatFoldersRepository,
    translations: TranslationsStore,
    channelActions: ChannelActionsRepository,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    showOnlyBookmarked: Boolean,
    channelFilter: Long?,
    onChannelFilterChange: (Long?) -> Unit,
    onOpenComments: (TimelinePost) -> Unit = {},
    homeTapTrigger: Long = 0L,
    onBrandTap: () -> Unit = {},
) {
    val vm: TimelineViewModel = viewModel(
        factory = remember(repo, bookmarks) {
            viewModelFactory { initializer { TimelineViewModel(repo, bookmarks) } }
        },
    )
    val context = LocalContext.current

    val posts by vm.posts.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()
    val pendingNew by vm.pendingNew.collectAsStateWithLifecycle()
    val foldersList by folders.folders.collectAsStateWithLifecycle()
    val archivedChatIds by repo.archivedChatIds.collectAsStateWithLifecycle()
    val translationsMap by translations.translations.collectAsStateWithLifecycle()
    var infoSheetChatId by remember { mutableStateOf<Long?>(null) }

    // Search state: only meaningful inside a channel filter. searchActive flips the top
    // bar into a TextField; query drives a debounced SearchChatMessages call; results
    // replace the normal feed in the list while the bar is in search mode.
    var searchActive by rememberSaveable(channelFilter) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(channelFilter) { mutableStateOf("") }
    var searchResults by remember(channelFilter) { mutableStateOf<List<TimelinePost>>(emptyList()) }
    if (channelFilter != null) {
        LaunchedEffect(searchActive, searchQuery, channelFilter) {
            if (!searchActive || searchQuery.isBlank()) {
                searchResults = emptyList()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            searchResults = repo.searchInChannel(channelFilter, searchQuery.trim())
        }
    }

    // Pill is suppressed during a refresh: repo.refresh() replaces _posts, briefly making
    // the post-refresh delta look like "everything is new" until acceptPending() lands.
    // Without this guard the pill flashes a misleading huge count for ~1 frame.

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Selected folder/archive scope. Default: "All". Stored as a saveable so the user's
    // tab survives process death; folder tabs get rebuilt against the freshest folders
    // list each composition, so a folder removed in another client falls back gracefully.
    var selectedFolderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var archiveSelected by rememberSaveable { mutableStateOf(false) }
    val scope_filter: FilterScope = remember(selectedFolderId, archiveSelected, foldersList) {
        when {
            archiveSelected -> FilterScope.Archive
            selectedFolderId != null -> {
                val match = foldersList.firstOrNull { it.id == selectedFolderId }
                if (match == null) FilterScope.All
                else FilterScope.Folder(match.id, match.name?.text?.text.orEmpty())
            }
            else -> FilterScope.All
        }
    }
    // Pinned + included member ids for the active folder (null when not a Folder scope).
    var folderMemberIds by remember(scope_filter) { mutableStateOf<Set<Long>?>(null) }
    var folderIncludesAllChannels by remember(scope_filter) { mutableStateOf(false) }
    var folderExcludedIds by remember(scope_filter) { mutableStateOf<Set<Long>>(emptySet()) }
    // Real Telegram folder rules can hide archived chats; mirror that here so a folder
    // with excludeArchived=true doesn't leak archived channels into its tab. The other
    // exclude_* flags (excludeMuted, excludeRead) only meaningfully apply to user/group
    // chats — channels rarely have actionable mute or unread state, and Hortay scopes
    // its feed to channels only.
    var folderExcludeArchived by remember(scope_filter) { mutableStateOf(false) }
    LaunchedEffect(scope_filter) {
        val folderScope = scope_filter as? FilterScope.Folder
        if (folderScope == null) {
            folderMemberIds = null
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
            folderExcludeArchived = false
            return@LaunchedEffect
        }
        val full = folders.fullFolder(folderScope.id)
        if (full == null) {
            folderMemberIds = emptySet()
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
            folderExcludeArchived = false
        } else {
            folderMemberIds = (full.pinnedChatIds?.toSet().orEmpty() + full.includedChatIds?.toSet().orEmpty())
            folderIncludesAllChannels = full.includeChannels
            folderExcludedIds = full.excludedChatIds?.toSet().orEmpty()
            folderExcludeArchived = full.excludeArchived
        }
    }
    val viewer = LocalMediaViewer.current

    // True when the LazyColumn is at the very top — used to auto-collapse pending posts
    // (no pill needed if the user is already looking at the top of the feed).
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
        }
    }

    // Twitter-style "tap home twice": first tap scrolls to top, second one (already at top)
    // refreshes. The trigger is a monotonic timestamp from the parent, so a single bump
    // produces a single reaction.
    LaunchedEffect(homeTapTrigger) {
        if (homeTapTrigger == 0L) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atTop) vm.refresh() else listState.animateScrollToItem(0)
    }

    // Switching the active channel context changes which posts are visible — without this,
    // the previous scroll offset bleeds through and lands the user mid-list.
    LaunchedEffect(channelFilter, scope_filter) {
        listState.scrollToItem(0)
    }

    // Tell TDLib the filtered channel is in focus while the user is here, and eagerly pull
    // a deeper slice of history so the filtered list is not just the few entries the global
    // refresh fetched per channel.
    LaunchedEffect(channelFilter) {
        val id = channelFilter ?: return@LaunchedEffect
        repo.openChat(id)
        repo.loadChannelHistory(id)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { repo.closeChat(id) }
        }
    }

    // Pagination: when the user scrolls near the bottom of a single-channel feed, pull
    // older posts. Only fires inside a channelFilter context — paginating the global
    // mixed feed by oldest-of-each-channel would touch many channels at once and serves
    // no real "I want to read this channel further back" intent.
    if (channelFilter != null) {
        LaunchedEffect(listState, channelFilter) {
            androidx.compose.runtime.snapshotFlow {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total to lastVisible
            }
                .distinctUntilChanged()
                .collect { (total, last) ->
                    if (total == 0 || last < 0) return@collect
                    if (last >= total - PAGINATION_PREFETCH_THRESHOLD) {
                        repo.loadOlder(channelFilter)
                    }
                }
        }
    }

    // Scope predicate shared by the visible feed and the "X нових постів" pill — so the
    // pill can't surface pending posts the user can't actually see (e.g. archived chats
    // while the user is in "Усі", or out-of-folder channels while a folder is active).
    val scopePredicate = remember(
        scope_filter, archivedChatIds, folderMemberIds, folderIncludesAllChannels,
        folderExcludedIds, folderExcludeArchived,
    ) {
        { p: TimelinePost ->
            val isArchived = p.chatId in archivedChatIds
            when (scope_filter) {
                FilterScope.All -> !isArchived
                FilterScope.Archive -> isArchived
                is FilterScope.Folder -> {
                    if (folderExcludeArchived && isArchived) false
                    else {
                        val included = folderMemberIds?.contains(p.chatId) == true ||
                            folderIncludesAllChannels
                        included && p.chatId !in folderExcludedIds
                    }
                }
            }
        }
    }

    val visiblePosts = remember(
        posts, scopePredicate, bookmarkedKeys, channelFilter, showOnlyBookmarked,
    ) {
        buildList {
            posts.forEach { p ->
                if (showOnlyBookmarked && p.bookmarkKey() !in bookmarkedKeys) return@forEach
                if (channelFilter != null && p.chatId != channelFilter) return@forEach

                // Mixed global feed: hide service / expired-media noise (pin / boost /
                // giveaway-created / ttl-expired). They're meaningful only in the context
                // of a single channel, where the per-channel filter view shows them as
                // the actual record of channel events.
                if (channelFilter == null) {
                    if (p.content is PostContent.Service) return@forEach
                    if (p.content is PostContent.ExpiredMedia) return@forEach
                }

                if (!scopePredicate(p)) return@forEach
                add(p)
            }
        }
    }

    // Pill state, scoped to the active tab. Counting pending posts the user can't see
    // would flash a misleading "5 нових" while archive/out-of-folder posts arrive in the
    // background.
    val scopedPendingNew = remember(pendingNew, scopePredicate) {
        pendingNew.filter(scopePredicate)
    }
    val scopedPendingChannels = remember(scopedPendingNew) {
        scopedPendingNew
            .groupBy { it.chatId }
            .map { (chatId, group) ->
                val anchor = group.maxBy { it.date }
                ChannelBadge(
                    chatId = chatId,
                    title = anchor.senderName,
                    thumb = anchor.avatarThumb,
                    fileId = anchor.avatarFileId,
                    latestPostDate = anchor.date,
                )
            }
            .sortedByDescending { it.latestPostDate }
            .take(MAX_PILL_BADGES)
    }

    // While the user is at the top, fold pending live updates straight into the visible
    // feed. Keyed on the scoped pending list so we only ack what's actually visible in
    // the current tab — pending in other scopes (archive, other folders) stays unread
    // until the user navigates there.
    LaunchedEffect(atTop, scopedPendingNew) {
        if (atTop && scopedPendingNew.isNotEmpty()) {
            vm.acceptIds(scopedPendingNew.map { it.chatId to it.id })
        }
    }

    val activeChannelTitle = remember(channelFilter, posts) {
        channelFilter?.let { id -> posts.firstOrNull { it.chatId == id }?.senderName }
    }
    var activeChannelSubscribers by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(channelFilter) {
        activeChannelSubscribers = channelFilter?.let { repo.channelSubscribers(it) }
    }

    // Warm the discussion-thread cache for posts that linger in the viewport. A cold
    // GetMessageThread is a server round-trip (~1.5–2s on first hit per channel); after
    // a single fetch TDLib answers from local cache (~200ms). Triggering this in the
    // background while the user is reading means the comments tap is effectively
    // instant for visible posts, and avoids burning bandwidth on posts the user just
    // scrolls past. CommentsRepository de-duplicates per anchor so this is safe to spam.
    LaunchedEffect(listState, commentsRepo) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .debounce(700)
            .collect { indices ->
                val snapshot = visiblePosts
                indices.mapNotNull { snapshot.getOrNull(it) }
                    .filter { it.commentCount != null }
                    .forEach { post ->
                        val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                        commentsRepo.prefetchThread(post.chatId, ids)
                    }
            }
    }

    // Mark visible posts as viewed (server-side view counter increments). Two safeguards:
    //   • keyed on listState (not visiblePosts) — visiblePosts gets a fresh List on every
    //     update from TDLib (reactions, views, edits), which would otherwise restart this
    //     LaunchedEffect 50×/sec on a busy feed and re-fire viewMessages for every visible
    //     row → the FLOOD_WAIT we saw earlier.
    //   • distinctUntilChanged + debounce(500): a single drag scroll emits dozens of indices
    //     transitions; we only want to ack what stayed visible after the user paused.
    LaunchedEffect(listState, repo) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .debounce(500)
            .collect { indices ->
                val snapshot = visiblePosts
                if (snapshot.isEmpty() || indices.isEmpty()) return@collect
                val grouped = indices.mapNotNull { idx -> snapshot.getOrNull(idx) }
                    .groupBy { it.chatId }
                for ((chatId, group) in grouped) {
                    repo.viewMessages(chatId, group.map { it.id })
                }
            }
    }

    // Frequently-changing state that the interactions lambdas need to read at the time
    // of invocation (not at construction time). Wrapping in rememberUpdatedState gives
    // the lambdas a stable [State] handle whose `.value` is always up-to-date — so we
    // don't have to rebuild [PostInteractions] (and trigger a recomposition cascade
    // through every PostCard in the viewport) on every TDLib update. Before this, an
    // UpdateMessageInteractionInfo for a single post (reaction, view counter) churned
    // `posts`, which churned `interactions`, which invalidated all PostCard skips.
    val postsState = rememberUpdatedState(posts)
    val translationsState = rememberUpdatedState(translationsMap)
    val bookmarkedState = rememberUpdatedState(bookmarkedKeys)
    val onChannelFilterChangeState = rememberUpdatedState(onChannelFilterChange)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)

    val interactions = remember {
        // Album members share the same translation — TDLib stores translations against the
        // caption-carrying message id, but for the UI any post in the album should look
        // translated. Fall back to scanning album members when the lookup misses.
        //
        // The cache is keyed by language as well: the user's system locale can change
        // mid-session and we don't want to serve a stale translation in the wrong target
        // tongue. We resolve the active target on every lookup so a post's render reacts
        // to a locale change as soon as the next recomposition reads translationsState.
        fun lookup(post: TimelinePost): dev.lyo.hortay.data.FormattedText? {
            val map = translationsState.value
            val lang = translations.currentTargetLanguage()
            map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, post.id, lang)]?.let { return it }
            post.albumMessageIds.forEach { id ->
                map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, id, lang)]?.let { return it }
            }
            return null
        }
        PostInteractions(
            onMediaClick = { post, idx ->
                viewer.openFor(post.content, idx)
            },
            onChannelClick = { post -> onChannelFilterChangeState.value(post.chatId) },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is dev.lyo.hortay.data.ForwardOrigin.Channel -> origin.sourceChatId
                    is dev.lyo.hortay.data.ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is dev.lyo.hortay.data.ForwardOrigin.Channel -> origin.sourceHandle
                    is dev.lyo.hortay.data.ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                // Already in our subscribed feed → switch the filter so the user lands on
                // that channel's posts. Otherwise hand off to the Telegram client.
                val subscribed = postsState.value.any { it.chatId == sourceId }
                when {
                    sourceId != null && subscribed -> onChannelFilterChangeState.value(sourceId)
                    !sourceHandle.isNullOrBlank() -> {
                        val handle = sourceHandle.removePrefix("@")
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    "tg://resolve?domain=$handle".toUri(),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> PostActions.share(context, post) },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post -> PostActions.openInTelegram(context, post) },
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
            isTranslated = { post -> lookup(post) != null },
            translationFor = ::lookup,
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
            onPostClick = { post -> onOpenCommentsState.value(post) },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TimelineTopBar(
                showOnlyBookmarked = showOnlyBookmarked,
                channelTitle = activeChannelTitle,
                channelSubscribers = activeChannelSubscribers,
                hasFilter = channelFilter != null,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchToggle = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it },
                onClearFilter = {
                    if (searchActive) {
                        searchActive = false
                        searchQuery = ""
                    } else {
                        onChannelFilterChange(null)
                    }
                },
                onBrandTap = onBrandTap,
                onTitleTap = { channelFilter?.let { infoSheetChatId = it } },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!showOnlyBookmarked && channelFilter == null) {
                        val tabs = remember(foldersList) {
                            foldersList.map { FolderTab(it.id, it.name?.text?.text.orEmpty()) }
                        }
                        FoldersBar(
                            selected = scope_filter,
                            folders = tabs,
                            showArchive = archivedChatIds.isNotEmpty(),
                            onSelected = { sel ->
                                when (sel) {
                                    FilterScope.All -> {
                                        selectedFolderId = null
                                        archiveSelected = false
                                    }
                                    FilterScope.Archive -> {
                                        selectedFolderId = null
                                        archiveSelected = true
                                    }
                                    is FilterScope.Folder -> {
                                        selectedFolderId = sel.id
                                        archiveSelected = false
                                    }
                                }
                            },
                        )
                    }

                    val displayed = if (searchActive && channelFilter != null) searchResults else visiblePosts
                    if (displayed.isEmpty() && !refreshing && !(searchActive && searchQuery.isBlank())) {
                        if (searchActive) SearchEmpty() else EmptyState(showOnlyBookmarked)
                    } else {
                        // Scroll gate: while the LazyColumn is mid-scroll (drag, fling,
                        // animateScrollToItem) media composables defer their ensure() so
                        // we don't saturate TDLib's 4-slot pool with intermediate posts
                        // that are about to scroll past anyway. Flips to "open" the moment
                        // scroll settles, at which point the genuinely-visible posts
                        // burst-ensure in one frame. Telegram-Android's RecyclerView uses
                        // SCROLL_STATE_IDLE for the same purpose.
                        val scrollGate = remember(listState) {
                            derivedStateOf { !listState.isScrollInProgress }
                        }

                        // Eager prefetch: while the user reads what's on screen, warm
                        // the next [PREFETCH_AHEAD] posts' posters at Prefetch priority.
                        // By the time the user scrolls down, those files are already
                        // partially or fully on disk and the loading overlay never paints.
                        // Gated on scroll-settled (prefetchAnchor=null while scrolling)
                        // so we don't fire ensure() while the gate above is closed.
                        val cache = LocalMediaCache.current
                        val prefetchAnchor by remember(listState) {
                            derivedStateOf {
                                if (listState.isScrollInProgress) null
                                else listState.firstVisibleItemIndex
                            }
                        }
                        LaunchedEffect(prefetchAnchor, displayed) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= displayed.size) return@LaunchedEffect
                            val end = (firstVisible + PREFETCH_AHEAD).coerceAtMost(displayed.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val post = displayed.getOrNull(idx) ?: continue
                                for (fileId in post.content.posterFileIds()) {
                                    cache.ensure(fileId, DownloadPriority.Prefetch)
                                }
                                // Inline-playable media (short videos, GIF animations) get the
                                // playback file pre-warmed too, but ONLY for the immediate next
                                // post. Beyond +1 we'd burn megabytes speculating on posts the
                                // user may never reach (a 30 s autoplay video is already ~5 MB).
                                // Posters stay cheap to prefetch farther, since they're tens of
                                // KB; playback is the heavyweight step we cap tightly.
                                if (idx == firstVisible + 1) {
                                    for (fileId in post.content.playbackFileIds()) {
                                        cache.ensure(fileId, DownloadPriority.Prefetch)
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
                                items(items = displayed, key = { "${it.chatId}_${it.id}" }) { post ->
                                    PostCard(post = post, interactions = interactions)
                                }
                            }
                        }
                    }
                }
            }

            // Floating "X нових постів" pill. Hidden in the Saved tab, inside a single-
            // channel filter (frozen views), while a refresh is in flight (transient
            // delta), and while the user is already at the top of the feed (we auto-
            // accept pending in that case).
            val pillVisible = !showOnlyBookmarked && channelFilter == null &&
                !refreshing && !atTop && scopedPendingChannels.isNotEmpty()
            // Filter chips occupy the top ~56dp inside the same Box; offset the pill so
            // it lands just below them instead of overlapping.
            val chipsVisible = !showOnlyBookmarked && channelFilter == null
            val pillTopPadding = if (chipsVisible) 64.dp else 8.dp
            AnimatedVisibility(
                visible = pillVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = pillTopPadding),
            ) {
                NewPostsPill(
                    channels = scopedPendingChannels,
                    pendingCount = scopedPendingNew.size,
                    onClick = {
                        // Ack only scope-visible pending; archive / other-folder pending
                        // stays unread for those tabs.
                        vm.acceptIds(scopedPendingNew.map { it.chatId to it.id })
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                )
            }
        }
    }

    infoSheetChatId?.let { chatId ->
        ChannelInfoSheet(
            chatId = chatId,
            actions = channelActions,
            onDismiss = { infoSheetChatId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    showOnlyBookmarked: Boolean,
    channelTitle: String?,
    channelSubscribers: Int?,
    hasFilter: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearFilter: () -> Unit,
    onBrandTap: () -> Unit,
    onTitleTap: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    when {
        hasFilter && searchActive -> TopAppBar(
            title = {
                val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
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
                IconButton(onClick = onClearFilter) {
                    Symbol(name = "arrow_back", contentDescription = "back")
                }
            },
            actions = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Symbol(name = "close", contentDescription = "clear")
                    }
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        hasFilter -> TopAppBar(
            title = {
                Column(modifier = Modifier.clickable(onClick = onTitleTap)) {
                    Text(
                        text = channelTitle.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    channelSubscribers?.let {
                        Text(
                            text = stringResource(R.string.timeline_subscribers, formatSubscribers(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onClearFilter) {
                    Symbol(name = "arrow_back", contentDescription = "back")
                }
            },
            actions = {
                IconButton(onClick = onSearchToggle) {
                    Symbol(name = "search", contentDescription = "search")
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        showOnlyBookmarked -> TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.timeline_saved_tab),
                    style = MaterialTheme.typography.headlineLarge,
                )
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        else -> TopAppBar(
            title = {
                Box(modifier = Modifier.clickable(onClick = onBrandTap)) {
                    BrandRow()
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}



@Composable
private fun SearchEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "search_off",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 48.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.timeline_search_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(showingSaved: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = if (showingSaved) "bookmark" else "forum",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 56.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(if (showingSaved) R.string.timeline_empty_saved_title else R.string.timeline_empty_default_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (showingSaved) R.string.timeline_empty_saved_helper else R.string.timeline_empty_default_helper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Compact subscriber count formatter — Telegram convention. 12 345 → "12.3K", 1 050 000
 * → "1.1M". Round numbers drop the decimal so the label reads as "12K" rather than "12.0K".
 */
/** How many items from the end of the list trigger an older-history prefetch. */
private const val PAGINATION_PREFETCH_THRESHOLD = 6

/** How long after the last keystroke we issue the search round-trip. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Avatars in the "X нових постів" pill — same cap as the original VM-side limit. */
private const val MAX_PILL_BADGES = 3

/**
 * How many posts ahead of the first visible item to eagerly prefetch posters for. Tuned so
 * a single forward-flick (~3 cards in a 1080p phone viewport) lands on already-warm files.
 * Going much higher costs bandwidth on ramped-back scrolls; lower starts to feel laggy.
 */
private const val PREFETCH_AHEAD = 4

/**
 * Hard cap on inline-autoplay video duration we're willing to *speculatively* prefetch the
 * playback file for. Telegram's own autoplay threshold is 60 s, but at home-DC bitrates a
 * 60 s clip is ~10 MB — too much to gamble on a post the user may never scroll to. 30 s
 * keeps speculative cost ≤ ~5 MB per pre-warmed video, which on healthy Wi-Fi is sub-second
 * and on cellular is still tolerable. Longer autoplay clips fall back to the on-mount
 * download path; the user will see the standard loading overlay if needed.
 */
private const val INLINE_PREFETCH_MAX_DURATION_SEC = 30

/**
 * The fileIds whose **poster / preview** should be eagerly downloaded when this content is
 * about to enter viewport. Intentionally excludes playback files (full videos, audio,
 * documents) — those are too big for speculative download, and the user's tap is the right
 * trigger for them. The poster is what TdMediaImage paints behind the play badge / progress
 * overlay, so warming it is what makes "scrolled into view" feel instant.
 */
private fun PostContent.posterFileIds(): List<Int> = buildList {
    when (val content = this@posterFileIds) {
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Photo -> item.media.fileId?.let(::add)
                is AlbumItem.Video -> item.media.fileId?.let(::add)
                is AlbumItem.Animation -> item.media.fileId?.let(::add)
            }
        }
        is PostContent.Video -> content.media.fileId?.let(::add)
        is PostContent.Animation -> content.media.fileId?.let(::add)
        is PostContent.Document -> content.thumb?.fileId?.let(::add)
        is PostContent.Sticker -> {
            // Stickers are tiny (<100 KB) — pulling both thumb and the playback file
            // up-front means the inline animation starts the moment the post settles,
            // without the placeholder→media swap.
            content.thumb?.fileId?.let(::add)
            content.media.fileId?.let(::add)
        }
        is PostContent.AnimatedEmoji -> {
            content.thumb?.fileId?.let(::add)
            content.sticker?.fileId?.let(::add)
        }
        is PostContent.VideoNote -> content.thumb?.fileId?.let(::add)
        // Text/Audio/VoiceNote/Poll/Location/Contact/Dice/Checklist/Service/Expired/
        // Unsupported — no still preview to warm.
        else -> Unit
    }
}

/**
 * Playback file ids worth pre-warming for inline auto-play (short videos, GIF animations).
 * Honours the same spoiler/secret guards as the renderer — we never prefetch a file the
 * user hasn't explicitly opted into seeing yet, even speculatively. Returns the empty list
 * for content types that are *not* inline-played in the feed (long videos, photos, audio).
 */
private fun PostContent.playbackFileIds(): List<Int> = buildList {
    when (val content = this@playbackFileIds) {
        is PostContent.Video -> {
            if (!content.hasSpoiler && !content.isSecret &&
                content.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
            ) {
                add(content.playbackFileId)
            }
        }
        is PostContent.Animation -> {
            if (!content.hasSpoiler && !content.isSecret) {
                add(content.playbackFileId)
            }
        }
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Video -> {
                    if (!item.hasSpoiler && !item.isSecret &&
                        item.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
                    ) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Animation -> {
                    if (!item.hasSpoiler && !item.isSecret) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Photo -> Unit
            }
        }
        else -> Unit
    }
}

private fun formatSubscribers(count: Int): String {
    fun compact(value: Double, suffix: String): String {
        val rounded = ((value * 10).toLong()) / 10.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}$suffix"
        else "%.1f%s".format(rounded, suffix)
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count / 1_000.0, "K")
        else -> compact(count / 1_000_000.0, "M")
    }
}

