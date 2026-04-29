package dev.lyo.telread.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.telread.data.BookmarkStore
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.PostContent
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.data.bookmarkKey
import dev.lyo.telread.ui.actions.PostActions
import dev.lyo.telread.ui.main.BrandRow
import dev.lyo.telread.ui.media.LocalMediaViewer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class FeedFilter(val label: String) {
    All("Усе"),
    Text("Текст"),
    Media("Медіа"),
    Today("Сьогодні"),
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TimelineScreen(
    repo: PostsRepository,
    commentsRepo: CommentsRepository,
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

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var filter by rememberSaveable { mutableStateOf(FeedFilter.All) }
    val viewer = LocalMediaViewer.current

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
    LaunchedEffect(channelFilter, filter) {
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

    val visiblePosts = remember(posts, filter, bookmarkedKeys, channelFilter, showOnlyBookmarked) {
        val base = buildList {
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
                add(p)
            }
        }
        applyFilter(base, filter)
    }

    val activeChannelTitle = remember(channelFilter, posts) {
        channelFilter?.let { id -> posts.firstOrNull { it.chatId == id }?.senderName }
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

    val interactions = remember(bookmarkedKeys, onChannelFilterChange, onOpenComments) {
        PostInteractions(
            onMediaClick = { post, idx ->
                viewer.openFor(post.content, idx)
            },
            onChannelClick = { post -> onChannelFilterChange(post.chatId) },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> PostActions.share(context, post) },
            onOpenClick = { post -> PostActions.openInTelegram(context, post) },
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
                hasFilter = channelFilter != null,
                onClearFilter = { onChannelFilterChange(null) },
                onBrandTap = onBrandTap,
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
                        FilterChipsRow(selected = filter, onSelected = { filter = it })
                    }

                    if (visiblePosts.isEmpty() && !refreshing) {
                        EmptyState(showOnlyBookmarked)
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = visiblePosts, key = { "${it.chatId}_${it.id}" }) { post ->
                                PostCard(post = post, interactions = interactions)
                            }
                        }
                    }
                }
            }

            // Floating "X нових постів" pill. Hidden in the Saved tab and inside a single-
            // channel filter — both are intentionally frozen views, not live feeds.
            val pillVisible = !showOnlyBookmarked && channelFilter == null && pendingChannels.isNotEmpty()
            AnimatedVisibility(
                visible = pillVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
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

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    showOnlyBookmarked: Boolean,
    channelTitle: String?,
    hasFilter: Boolean,
    onClearFilter: () -> Unit,
    onBrandTap: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    when {
        hasFilter -> TopAppBar(
            title = {
                Text(
                    text = channelTitle.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearFilter) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "back")
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
private fun FilterChipsRow(selected: FeedFilter, onSelected: (FeedFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedFilter.entries.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelected(f) },
                label = { Text(f.label, style = MaterialTheme.typography.labelLarge) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null,
            )
        }
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
        Icon(
            imageVector = if (showingSaved) Icons.Rounded.BookmarkBorder else Icons.Rounded.Forum,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
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

private fun applyFilter(
    posts: List<TimelinePost>,
    filter: FeedFilter,
): List<TimelinePost> = when (filter) {
    FeedFilter.All -> posts
    FeedFilter.Text -> posts.filter { it.content is PostContent.Text }
    FeedFilter.Media -> posts.filter {
        it.content is PostContent.PhotoAlbum ||
            it.content is PostContent.Video ||
            it.content is PostContent.Animation
    }
    FeedFilter.Today -> {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        posts.filter { it.date >= cutoff }
    }
}
