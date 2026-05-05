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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.web.ChannelEntry
import dev.lyo.hortay.data.web.ChannelFetchStatus
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import kotlinx.coroutines.launch

/**
 * Subscribed-channels list for guest mode. Mirrors the TDLib mode's
 * [dev.lyo.hortay.ui.channels.ChannelsScreen] visual shape: scrollable rows of
 * 56dp avatar + name + lightweight subtitle, divider between rows. Tapping a
 * row jumps the user back to the feed filtered to that channel — TODO once
 * the per-channel filter is wired into [WebTimelineScreen].
 *
 * Per-channel fetch status surfaces as a small chip on the right of each row
 * (rate-limited / error / not-found). For "ok" state we show last-fetched
 * relative time, matching the TDLib mode's "sync indicator" pattern.
 */
@Composable
fun WebChannelsScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    onChannelClick: (String) -> Unit,
) {
    val channels by graph.webFeedSource.channels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current

    val subscribed = channels.filter { it.isSubscribed }

    if (subscribed.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Жодних підписок поки що.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        items(subscribed, key = { it.info.username }) { entry ->
            ChannelRow(
                entry = entry,
                onClick = { onChannelClick(entry.info.username) },
                onLongClick = {
                    // Long-press unsubscribes; subscriptions store mirrors back into DB.
                    scope.launch { graph.webSubscriptions.remove(entry.info.username) }
                },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    entry: ChannelEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(name = entry.info.title, avatarUrl = entry.info.avatarUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.info.title,
                    style = MaterialTheme.typography.titleSmall,
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
            val subtitle = buildString {
                append("@${entry.info.username}")
                if (entry.info.subscribers != null) {
                    append(" · ${entry.info.subscribers} підписників")
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusChip(entry.status)
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
private fun StatusChip(status: ChannelFetchStatus) {
    val (label, color) = when (status) {
        ChannelFetchStatus.Idle -> null to MaterialTheme.colorScheme.onSurfaceVariant
        ChannelFetchStatus.Loading -> "оновлюється" to MaterialTheme.colorScheme.onSurfaceVariant
        ChannelFetchStatus.Ok -> null to MaterialTheme.colorScheme.onSurfaceVariant
        ChannelFetchStatus.NotFound -> "не знайдено" to MaterialTheme.colorScheme.error
        ChannelFetchStatus.Private -> "приватний" to MaterialTheme.colorScheme.error
        ChannelFetchStatus.RateLimited -> "ліміт" to MaterialTheme.colorScheme.error
        ChannelFetchStatus.Error -> "помилка" to MaterialTheme.colorScheme.error
        ChannelFetchStatus.ParseFailure -> "парсинг" to MaterialTheme.colorScheme.error
    }
    if (label != null) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
