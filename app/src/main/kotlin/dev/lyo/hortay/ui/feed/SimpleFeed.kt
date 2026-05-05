package dev.lyo.hortay.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import kotlinx.collections.immutable.PersistentList

/**
 * Shared minimal feed screen. One Scaffold + LargeTopAppBar + PullToRefreshBox +
 * LazyColumn-of-PostCard, exactly the same composables the TDLib-mode
 * [dev.lyo.hortay.ui.timeline.TimelineScreen] uses for its body. Used by guest
 * mode for both the main feed and the bookmarked-posts tab — same shape, same
 * dividers, same Avatar / HeaderRow / PostBody / ActionRow / ReactionChip.
 *
 * Why this is its own composable rather than calling TimelineScreen directly:
 * TimelineScreen carries many TDLib-only features (folders bar, channel filter,
 * search, channel-info sheet, comments tap) that don't apply in guest mode.
 * Threading nullables through every one of those would be more code than the
 * shared body itself. SimpleFeed extracts ONLY the parts that web actually
 * needs, all built from the same shared primitives.
 *
 * Empty / loading states match the language used in TDLib-mode equivalents
 * (refresh while empty → "Loading…", subscribed but empty → "No posts yet").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleFeed(
    title: String,
    posts: PersistentList<TimelinePost>,
    isRefreshing: Boolean,
    isInitialEmpty: Boolean,
    emptyText: String,
    loadingText: String,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    actionIconSymbol: String? = null,
    actionIconContentDescription: String? = null,
    onActionClick: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    LaunchedEffect(Unit) { /* outer triggers refresh */ }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displaySmall,
                    )
                },
                actions = {
                    if (actionIconSymbol != null) {
                        IconButton(onClick = onActionClick) {
                            Symbol(
                                name = actionIconSymbol,
                                contentDescription = actionIconContentDescription,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (isInitialEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isRefreshing) loadingText else emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                    top = 0.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (posts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isRefreshing) loadingText else emptyText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            interactions = PostInteractions.Noop,
                            clickable = false,
                        )
                    }
                }
            }
        }
    }
}
