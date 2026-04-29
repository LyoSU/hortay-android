package dev.lyo.telread.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.media.TdAvatar

/**
 * List of channels the user is subscribed to. Data is derived from the same feed as the
 * timeline (each unique chat is a channel), so it stays in sync without a second TDLib query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    repo: PostsRepository,
    contentPadding: PaddingValues,
    onChannelClick: (chatId: Long) -> Unit,
) {
    val posts by repo.posts.collectAsState()
    val channels = remember(posts) { aggregate(posts) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Канали", style = MaterialTheme.typography.displaySmall) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyChannels(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = channels, key = { it.chatId }) { ch ->
                    ChannelRow(channel = ch, onClick = { onChannelClick(ch.chatId) })
                }
            }
        }
    }
}

private data class ChannelSummary(
    val chatId: Long,
    val title: String,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val lastPostExcerpt: String,
    val lastPostDate: Long,
    val postCount: Int,
)

private fun aggregate(posts: List<TimelinePost>): List<ChannelSummary> = posts
    .groupBy { it.chatId }
    .map { (chatId, list) ->
        val anchor = list.maxByOrNull { it.date }!!
        ChannelSummary(
            chatId = chatId,
            title = anchor.senderName,
            avatarThumb = anchor.avatarThumb,
            avatarFileId = anchor.avatarFileId,
            lastPostExcerpt = anchor.content.captionPlain.take(120),
            lastPostDate = anchor.date,
            postCount = list.size,
        )
    }
    .sortedByDescending { it.lastPostDate }

@Composable
private fun ChannelRow(channel: ChannelSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TdAvatar(
            name = channel.title,
            thumb = channel.avatarThumb,
            fileId = channel.avatarFileId,
            size = 48.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.lastPostExcerpt.isNotBlank()) {
                Text(
                    text = channel.lastPostExcerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${channel.postCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyChannels(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Не знайдено каналів",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Підпишіться на канали в Telegram — і вони з'являться тут.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
