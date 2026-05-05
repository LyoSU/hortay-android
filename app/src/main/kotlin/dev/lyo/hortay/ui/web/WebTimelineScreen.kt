package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import kotlinx.coroutines.launch

/**
 * Production anonymous-mode timeline. Mirrors the TDLib mode's
 * [dev.lyo.hortay.ui.timeline.TimelineScreen] scaffold shape: own [Scaffold] +
 * collapsing [LargeTopAppBar] + nestedScroll behaviour. Each web tab (this,
 * [WebChannelsScreen], [WebSavedScreen], [WebSettingsScreen]) carries its
 * own scaffold so status-bar safe-area is handled at the inner Scaffold layer
 * — the outer [WebModeScaffold] sets `contentWindowInsets = WindowInsets(0,0,0,0)`
 * exactly as `MainScaffold` does, deliberately delegating insets downward.
 *
 * Why a separate screen from the TDLib `TimelineScreen`: web posts have a
 * narrower data shape (no replies, no reactions tap, no comments), and threading
 * those branches through one Composable tree would either bloat each row's
 * params or fork on every render — visual-parity is far easier to maintain by
 * keeping two screens with mirrored scaffolds and using shared primitives
 * ([WebPostCard], [TdAvatar], [FloatingNavBar]) below them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebTimelineScreen(
    feedSource: WebFeedSource,
    emojiResolver: WebCustomEmojiResolver,
    contentPadding: PaddingValues,
    onAddChannel: () -> Unit,
) {
    val posts by feedSource.posts.collectAsStateWithLifecycle()
    val channels by feedSource.channels.collectAsStateWithLifecycle()
    val refreshState by feedSource.refreshState.collectAsStateWithLifecycle()

    val isRefreshing = refreshState is WebFeedSource.RefreshState.Refreshing
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    LaunchedEffect(Unit) { feedSource.refresh(force = false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.web_feed_title),
                        style = MaterialTheme.typography.displaySmall,
                    )
                },
                actions = {
                    IconButton(onClick = onAddChannel) {
                        Symbol(
                            name = "add",
                            contentDescription = stringResource(R.string.web_add_channel),
                        )
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
        if (channels.none { it.isSubscribed }) {
            EmptyChannelsState(
                onAddChannel = onAddChannel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { feedSource.refresh(force = true) } },
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
                                text = if (isRefreshing) stringResource(R.string.web_loading)
                                    else stringResource(R.string.web_empty_posts),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(posts, key = { it.id }) { post ->
                        // Reuse the TDLib-mode PostCard verbatim — same Avatar,
                        // HeaderRow, PostBody, ActionRow, ReactionChip helpers.
                        // Web content reaches this Composable as TimelinePost via
                        // [WebPostAdapter] so visual parity is enforced by sharing
                        // the data type, not by mirroring layouts manually.
                        PostCard(
                            post = post,
                            interactions = PostInteractions.Noop,
                            clickable = false,
                        )
                    }
                }

                if (refreshState is WebFeedSource.RefreshState.Error) {
                    item {
                        ErrorBanner(message = (refreshState as WebFeedSource.RefreshState.Error).message)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChannelsState(
    onAddChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.web_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.web_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAddChannel) {
                Text(stringResource(R.string.web_add_channel))
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.web_refresh_error, message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
