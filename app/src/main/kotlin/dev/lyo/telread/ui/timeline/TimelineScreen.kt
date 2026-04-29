package dev.lyo.telread.ui.timeline

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.telread.data.BookmarkStore
import dev.lyo.telread.data.ChannelActionsRepository
import dev.lyo.telread.data.ChatFoldersRepository
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.TranslationsStore
import dev.lyo.telread.data.PostContent
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.data.bookmarkKey
import dev.lyo.telread.ui.actions.PostActions
import dev.lyo.telread.ui.channels.ChannelInfoSheet
import dev.lyo.telread.ui.icons.Symbol
import dev.lyo.telread.ui.main.BrandRow
import dev.lyo.telread.ui.media.LocalMediaViewer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    val pendingChannels by vm.pendingChannels.collectAsStateWithLifecycle()
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
    LaunchedEffect(scope_filter) {
        val folderScope = scope_filter as? FilterScope.Folder
        if (folderScope == null) {
            folderMemberIds = null
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
            return@LaunchedEffect
        }
        val full = folders.fullFolder(folderScope.id)
        if (full == null) {
            folderMemberIds = emptySet()
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
        } else {
            folderMemberIds = (full.pinnedChatIds?.toSet().orEmpty() + full.includedChatIds?.toSet().orEmpty())
            folderIncludesAllChannels = full.includeChannels
            folderExcludedIds = full.excludedChatIds?.toSet().orEmpty()
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

    // While the user is at the top, fold pending live updates straight into the visible
    // feed. Keyed on BOTH atTop AND pendingChannels so trickling UpdateNewMessage events
    // keep the seen-set current — without the pendingChannels key the effect would only
    // fire once on becoming-at-top and pending would silently accumulate.
    LaunchedEffect(atTop, pendingChannels) {
        if (atTop && pendingChannels.isNotEmpty()) vm.acceptPending()
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

    val visiblePosts = remember(
        posts, scope_filter, bookmarkedKeys, channelFilter, showOnlyBookmarked,
        archivedChatIds, folderMemberIds, folderIncludesAllChannels, folderExcludedIds,
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

                // Scope: All hides archived chats; Archive hides everything BUT archived;
                // Folder respects the precomputed include/exclude set.
                val isArchived = p.chatId in archivedChatIds
                val passesScope = when (scope_filter) {
                    FilterScope.All -> !isArchived
                    FilterScope.Archive -> isArchived
                    is FilterScope.Folder -> {
                        val included = folderMemberIds?.contains(p.chatId) == true ||
                            folderIncludesAllChannels
                        included && p.chatId !in folderExcludedIds
                    }
                }
                if (!passesScope) return@forEach
                add(p)
            }
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

    val interactions = remember(bookmarkedKeys, onChannelFilterChange, onOpenComments, translationsMap, posts) {
        // Album members share the same translation — TDLib stores translations against the
        // caption-carrying message id, but for the UI any post in the album should look
        // translated. Fall back to scanning album members when the lookup misses.
        fun lookup(post: TimelinePost): dev.lyo.telread.data.FormattedText? {
            translationsMap[dev.lyo.telread.data.TranslationsStore.Key(post.chatId, post.id)]?.let { return it }
            post.albumMessageIds.forEach { id ->
                translationsMap[dev.lyo.telread.data.TranslationsStore.Key(post.chatId, id)]?.let { return it }
            }
            return null
        }
        PostInteractions(
            onMediaClick = { post, idx ->
                viewer.openFor(post.content, idx)
            },
            onChannelClick = { post -> onChannelFilterChange(post.chatId) },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is dev.lyo.telread.data.ForwardOrigin.Channel -> origin.sourceChatId
                    is dev.lyo.telread.data.ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is dev.lyo.telread.data.ForwardOrigin.Channel -> origin.sourceHandle
                    is dev.lyo.telread.data.ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                // Already in our subscribed feed → switch the filter so the user lands on
                // that channel's posts. Otherwise hand off to the Telegram client.
                val subscribed = posts.any { it.chatId == sourceId }
                when {
                    sourceId != null && subscribed -> onChannelFilterChange(sourceId)
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
            onReactionToggle = { post, emoji ->
                scope.launch {
                    val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                    val mine = post.reactions.items.firstOrNull { it.emoji == emoji && it.isChosen }
                    if (mine != null) channelActions.removeReaction(post.chatId, target, emoji)
                    else channelActions.addReaction(post.chatId, target, emoji)
                }
            },
            onPostClick = onOpenComments,
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedKeys },
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

            // Floating "X нових постів" pill. Hidden in the Saved tab, inside a single-
            // channel filter (frozen views), while a refresh is in flight (transient
            // delta), and while the user is already at the top of the feed (we auto-
            // accept pending in that case).
            val pillVisible = !showOnlyBookmarked && channelFilter == null &&
                !refreshing && !atTop && pendingChannels.isNotEmpty()
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
                    channels = pendingChannels,
                    pendingCount = pendingNew.size,
                    onClick = {
                        vm.acceptPending()
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
                                    "Пошук у каналі",
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
                            text = "${formatSubscribers(it)} підписників",
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
                    text = "Збережене",
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
            text = "Нічого не знайдено",
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
            text = if (showingSaved) "Нема збережених постів" else "Поки тут порожньо",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (showingSaved)
                "Натискайте на закладку поруч із постом, щоб зберегти на потім."
            else
                "Підпишіться на канали в Telegram — і вони з'являться у стрічці.",
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

