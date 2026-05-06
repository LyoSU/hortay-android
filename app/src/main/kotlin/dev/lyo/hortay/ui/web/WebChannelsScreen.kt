package dev.lyo.hortay.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.web.ChannelEntry
import dev.lyo.hortay.data.web.ChannelFetchStatus
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import kotlinx.coroutines.launch

/**
 * Subscribed-channels list for guest mode. Mirrors [dev.lyo.hortay.ui.channels.ChannelsScreen]
 * shape: own [Scaffold] + collapsing [LargeTopAppBar], `surfaceContainerLow` rounded
 * row chips with a 48dp [TdAvatar] and per-channel status indicator.
 *
 * Unsubscribe affordance: explicit trailing `close` icon button on each row, plus
 * a confirmation dialog. Long-press alone was discoverability-hostile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebChannelsScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    onChannelClick: (String) -> Unit,
    onAddChannel: () -> Unit = {},
) {
    val channels by graph.webFeedSource.channels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    val subscribed = channels.filter { it.isSubscribed }
    var pendingUnsubscribe by remember { mutableStateOf<ChannelEntry?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.web_channels_title),
                        style = MaterialTheme.typography.displaySmall,
                    )
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
        if (subscribed.isEmpty()) {
            EmptyChannelsState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onAddChannel = onAddChannel,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(subscribed, key = { it.info.username }) { entry ->
                ChannelRow(
                    entry = entry,
                    onClick = { onChannelClick(entry.info.username) },
                    onUnsubscribeClick = { pendingUnsubscribe = entry },
                )
            }
        }
    }

    pendingUnsubscribe?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            title = { Text(stringResource(R.string.web_unsubscribe_title)) },
            text = { Text(stringResource(R.string.web_unsubscribe_body, entry.info.username)) },
            confirmButton = {
                TextButton(onClick = {
                    val u = entry.info.username
                    pendingUnsubscribe = null
                    scope.launch { graph.webSubscriptions.remove(u) }
                }) {
                    Text(
                        stringResource(R.string.web_unsubscribe_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text(stringResource(R.string.web_cancel))
                }
            },
        )
    }
}

/**
 * Friendly empty-state for first-launch / fully-unsubscribed users. Replaces
 * the bare centered string ("Поки немає каналів") with a Material-3-shaped
 * card: a soft tinted icon disc, a title, an explanatory subtitle, and an
 * explicit "Add channel" CTA. The CTA duplicates the [WebModeScaffold] FAB
 * — both routes work — but having it in the empty state means a user who
 * landed on the Channels tab via the bottom nav doesn't have to discover
 * the FAB to make progress.
 */
@Composable
private fun EmptyChannelsState(
    modifier: Modifier = Modifier,
    onAddChannel: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = "rss_feed",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 48.dp,
            )
        }
        Spacer(Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.web_empty_channels_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.web_empty_channels_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        FilledTonalButton(onClick = onAddChannel) {
            Symbol(name = "add", contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.web_add_channel))
        }
    }
}

@Composable
private fun ChannelRow(
    entry: ChannelEntry,
    onClick: () -> Unit,
    onUnsubscribeClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(name = entry.info.title, avatarUrl = entry.info.avatarUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.info.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Symbol(
                        name = "verified",
                        tint = MaterialTheme.colorScheme.primary,
                        size = 14.dp,
                    )
                }
            }
            val subscriberLine = entry.info.subscribers?.let {
                stringResource(R.string.web_subscribers, it)
            }
            val subtitle = buildString {
                append("@${entry.info.username}")
                if (subscriberLine != null) {
                    append(" · ")
                    append(subscriberLine)
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(entry.status)
        }
        IconButton(onClick = onUnsubscribeClick) {
            Symbol(
                name = "close",
                contentDescription = stringResource(R.string.web_unsubscribe_confirm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        }
    }
}

@Composable
private fun ChannelAvatar(name: String, avatarUrl: String?) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val bg = palette[(name.hashCode().rem(palette.size) + palette.size) % palette.size]
    Box(modifier = Modifier.clip(CircleShape)) {
        TdAvatar(
            name = name,
            thumb = null,
            fileId = null,
            size = 48.dp,
            background = bg,
        )
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun StatusBadge(status: ChannelFetchStatus) {
    val labelRes = when (status) {
        ChannelFetchStatus.NotFound -> R.string.web_status_not_found
        ChannelFetchStatus.Private -> R.string.web_status_private
        ChannelFetchStatus.RateLimited -> R.string.web_status_rate_limited
        ChannelFetchStatus.Error -> R.string.web_status_error
        ChannelFetchStatus.ParseFailure -> R.string.web_status_parse_failure
        ChannelFetchStatus.Loading -> R.string.web_status_loading
        ChannelFetchStatus.Idle, ChannelFetchStatus.Ok -> return
    }
    val color = if (status == ChannelFetchStatus.Loading) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.error
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
