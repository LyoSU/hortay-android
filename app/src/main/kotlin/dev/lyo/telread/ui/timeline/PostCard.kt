package dev.lyo.telread.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.telread.data.ForwardOrigin
import dev.lyo.telread.data.ReplyPreview
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.media.TdMediaImage
import java.text.DateFormat
import java.util.Date

@Composable
fun PostCard(
    post: TimelinePost,
    interactions: PostInteractions = PostInteractions.Noop,
    clickable: Boolean = true,
) {
    Card(
        onClick = { if (clickable) interactions.onPostClick(post) },
        enabled = clickable,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            HeaderRow(post, onChannelClick = { interactions.onChannelClick(post) })

            post.forwardOrigin?.let {
                Spacer(Modifier.height(10.dp))
                ForwardChip(it)
            }

            post.reply?.let {
                Spacer(Modifier.height(10.dp))
                ReplyBlock(it)
            }

            Spacer(Modifier.height(12.dp))
            PostBody(
                content = post.content,
                onMediaClick = { _, idx -> interactions.onMediaClick(post, idx) },
            )

            Spacer(Modifier.height(14.dp))
            ActionRow(post, interactions)
        }
    }
}

@Composable
private fun HeaderRow(post: TimelinePost, onChannelClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onChannelClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(post)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.channelTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (post.editDate > 0L) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "edited",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRelative(post.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                post.authorSignature?.let {
                    Text(
                        text = " · $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(post: TimelinePost) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(avatarBg(post)),
        contentAlignment = Alignment.Center,
    ) {
        if (post.avatarFileId != null) {
            TdMediaImage(
                media = dev.lyo.telread.data.TdMedia(post.avatarFileId, 0, 0, null),
                contentDescription = post.channelTitle,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = post.channelTitle.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun avatarBg(post: TimelinePost) = run {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    palette[(post.channelTitle.hashCode().rem(palette.size) + palette.size) % palette.size]
}

@Composable
private fun ForwardChip(origin: ForwardOrigin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Repeat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Переслано від ${forwardLabel(origin)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun forwardLabel(origin: ForwardOrigin): String = when (origin) {
    is ForwardOrigin.User -> origin.userName
    is ForwardOrigin.Channel -> origin.channelName + (origin.authorSignature?.let { " · $it" } ?: "")
    is ForwardOrigin.HiddenUser -> origin.senderName
    is ForwardOrigin.Chat -> origin.chatName
}

@Composable
private fun ReplyBlock(reply: ReplyPreview) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = reply.authorName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = reply.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionRow(post: TimelinePost, interactions: PostInteractions) {
    val isBookmarked = interactions.isBookmarked(post)
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (post.views > 0) StatPill(Icons.Rounded.Visibility, formatViews(post.views))
        if (post.reactions.totalCount > 0) {
            Spacer(Modifier.width(12.dp))
            StatPill(Icons.Rounded.Favorite, formatViews(post.reactions.totalCount), tint = MaterialTheme.colorScheme.tertiary)
        }
        post.commentCount?.let { count ->
            Spacer(Modifier.width(12.dp))
            StatPill(
                icon = Icons.Rounded.ChatBubbleOutline,
                text = if (count > 0) formatViews(count) else "0",
                onClick = { interactions.onPostClick(post) },
            )
        }
        Spacer(Modifier.weight(1f))

        IconButton(onClick = { interactions.onBookmarkClick(post) }) {
            Icon(
                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Rounded.BookmarkBorder,
                contentDescription = if (isBookmarked) "unsave" else "save",
                tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { interactions.onShareClick(post) }) {
            Icon(
                Icons.Rounded.IosShare,
                contentDescription = "share",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = "more",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Відкрити в Telegram") },
                    onClick = {
                        menuOpen = false
                        interactions.onOpenClick(post)
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun StatPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> "щойно"
        diffMin < 60 -> "${diffMin}хв"
        diffMin < 60 * 24 -> "${diffMin / 60}год"
        diffMin < 60 * 24 * 7 -> "${diffMin / (60 * 24)}д"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private fun formatViews(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "%.1fK".format(count / 1_000.0).trimEnd('0').trimEnd('.')
    else -> "%.1fM".format(count / 1_000_000.0).trimEnd('0').trimEnd('.')
}
