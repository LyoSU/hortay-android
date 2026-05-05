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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Production anonymous-mode timeline. Reads merged feed from
 * [WebFeedSource.posts] (DB-backed, instant cold-start), pull-to-refresh wired
 * to [WebFeedSource.refresh], add-channel surface, empty state with curated
 * suggestions.
 *
 * Why a separate screen from the TDLib-mode `TimelineScreen`: the data shape,
 * the channel-list semantics (DataStore-backed intent vs. TDLib chat list),
 * and the interaction limitations (no replies, no reactions tap, no comments
 * — those need auth) all differ. Trying to make one screen serve both modes
 * would either branch every Composable on the mode flag or produce a
 * lowest-common-denominator UI for both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebTimelineScreen(
    feedSource: WebFeedSource,
    emojiResolver: WebCustomEmojiResolver,
    contentPadding: PaddingValues,
    onAddChannel: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val posts by feedSource.posts.collectAsStateWithLifecycle()
    val channels by feedSource.channels.collectAsStateWithLifecycle()
    val refreshState by feedSource.refreshState.collectAsStateWithLifecycle()

    val isRefreshing = refreshState is WebFeedSource.RefreshState.Refreshing
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current

    // Trigger a non-forced refresh on first composition. The staleness window
    // inside WebFeedSource means a recent successful sweep won't re-fan-out.
    LaunchedEffect(Unit) { feedSource.refresh(force = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Стрічка") },
                actions = {
                    IconButton(onClick = onAddChannel) {
                        Symbol(name = "add", contentDescription = "Додати канал")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val combinedPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() +
                contentPadding.calculateBottomPadding(),
        )

        if (channels.isEmpty()) {
            EmptyChannelsState(
                onAddChannel = onAddChannel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(combinedPadding),
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { feedSource.refresh(force = true) } },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = combinedPadding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = combinedPadding.calculateStartPadding(layoutDirection),
                    end = combinedPadding.calculateEndPadding(layoutDirection),
                    top = 0.dp,
                    bottom = combinedPadding.calculateBottomPadding() + 16.dp,
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
                                text = if (isRefreshing) "Завантажуємо…"
                                    else "Поки немає постів. Перевірте підписки.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(posts, key = { it.post.id }) { entry ->
                        // No horizontal padding here — WebPostCard mirrors TDLib
                        // PostCard's edge-to-edge row with internal 16dp padding,
                        // and the bottom HorizontalDivider has to span the full
                        // viewport width like in the authenticated feed.
                        WebPostCard(
                            post = entry.post,
                            emojiResolver = emojiResolver,
                            channelTitle = entry.channel.title,
                            channelAvatarUrl = entry.channel.avatarUrl,
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
                text = "Без входу",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Додайте кілька публічних каналів — і отримуйте їхні пости без авторизації в Telegram.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAddChannel) {
                Text("Додати канал")
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
            text = "Помилка оновлення: $message",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
