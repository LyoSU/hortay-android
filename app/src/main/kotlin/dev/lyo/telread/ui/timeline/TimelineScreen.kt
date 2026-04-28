package dev.lyo.telread.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.telread.data.AlbumItem
import dev.lyo.telread.data.BookmarkStore
import dev.lyo.telread.data.PostContent
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.data.bookmarkKey
import dev.lyo.telread.ui.actions.PostActions
import dev.lyo.telread.ui.media.FullScreenMediaViewer

private enum class FeedFilter(val label: String) {
    All("Усе"),
    Text("Текст"),
    Media("Медіа"),
    Today("Сьогодні"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    repo: PostsRepository,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    showOnlyBookmarked: Boolean,
    onOpenComments: (TimelinePost) -> Unit = {},
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
    val channelFilter by vm.channelFilter.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var filter by rememberSaveable { mutableStateOf(FeedFilter.All) }
    var viewerState by remember { mutableStateOf<ViewerState?>(null) }

    val visiblePosts = remember(posts, filter, bookmarkedKeys, showOnlyBookmarked) {
        val base = if (showOnlyBookmarked) posts.filter { it.bookmarkKey() in bookmarkedKeys } else posts
        applyFilter(base, filter)
    }

    val interactions = remember(bookmarkedKeys) {
        PostInteractions(
            onMediaClick = { post, idx ->
                resolveAlbumItems(post)?.let { items -> viewerState = ViewerState(items, idx) }
            },
            onChannelClick = { post -> vm.setChannelFilter(post.chatId) },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> PostActions.share(context, post) },
            onOpenClick = { post -> PostActions.openInTelegram(context, post) },
            onCommentsClick = onOpenComments,
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedKeys },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (showOnlyBookmarked) "Збережене" else "Стрічка",
                            style = MaterialTheme.typography.displaySmall,
                        )
                        if (channelFilter != null) {
                            val title = posts.firstOrNull { it.chatId == channelFilter }?.channelTitle.orEmpty()
                            Text(
                                text = "Канал: $title  ✕",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { vm.setChannelFilter(null) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!showOnlyBookmarked) {
                    FilterChipsRow(selected = filter, onSelected = { filter = it })
                }

                if (visiblePosts.isEmpty() && !refreshing) {
                    EmptyState(showOnlyBookmarked)
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = contentPadding.calculateBottomPadding() + 12.dp,
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
    }

    viewerState?.let { state ->
        FullScreenMediaViewer(
            items = state.items,
            initialIndex = state.index,
            onDismiss = { viewerState = null },
        )
    }
}

private data class ViewerState(val items: List<AlbumItem>, val index: Int)

private fun resolveAlbumItems(post: TimelinePost): List<AlbumItem>? = when (val c = post.content) {
    is PostContent.PhotoAlbum -> c.items.takeIf { it.isNotEmpty() }
    is PostContent.Video -> listOf(AlbumItem.Video(c.media, c.durationSec, c.playbackFileId))
    is PostContent.Animation -> listOf(AlbumItem.Animation(c.media, c.playbackFileId))
    else -> null
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
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (showingSaved) Icons.Outlined.BookmarkBorder else Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (showingSaved) "Нема збережених постів" else "Поки тут порожньо",
            style = MaterialTheme.typography.headlineSmall,
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
