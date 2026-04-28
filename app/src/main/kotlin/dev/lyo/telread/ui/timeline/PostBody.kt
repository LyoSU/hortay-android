package dev.lyo.telread.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.telread.data.AlbumItem
import dev.lyo.telread.data.PostContent
import androidx.compose.foundation.clickable
import dev.lyo.telread.data.WebPreview
import dev.lyo.telread.ui.media.TdMediaImage
import dev.lyo.telread.ui.media.TdVideoPlayer
import dev.lyo.telread.ui.text.rememberAnnotatedString

/**
 * Render the body of a post. [onMediaClick] fires with the resolved media list and the
 * index the user tapped, so callers can open a full-screen viewer with the correct page.
 */
@Composable
fun PostBody(
    content: PostContent,
    modifier: Modifier = Modifier,
    onMediaClick: (List<AlbumItem>, Int) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier) {
        when (content) {
            is PostContent.Text -> TextBlock(content)
            is PostContent.PhotoAlbum -> AlbumBlock(content, onMediaClick)
            is PostContent.Video -> VideoBlock(content, onMediaClick)
            is PostContent.Animation -> AnimationBlock(content, onMediaClick)
            is PostContent.Document -> DocumentBlock(content)
            is PostContent.Audio -> AudioBlock(content)
            is PostContent.VoiceNote -> VoiceNoteBlock(content)
            is PostContent.VideoNote -> VideoNoteBlock(content)
            is PostContent.Sticker -> StickerBlock(content)
            is PostContent.Poll -> PollBlock(content)
            is PostContent.Location -> LocationBlock(content)
            is PostContent.Contact -> ContactBlock(content)
            is PostContent.Dice -> DiceBlock(content)
            is PostContent.Unsupported -> UnsupportedBlock(content)
        }
    }
}

@Composable
private fun TextBlock(content: PostContent.Text) {
    val annotated = rememberAnnotatedString(content.formatted)
    if (annotated.isNotEmpty()) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 18,
            overflow = TextOverflow.Ellipsis,
        )
    }
    content.webPreview?.let {
        Spacer(Modifier.height(12.dp))
        WebPreviewCard(it)
    }
}

@Composable
private fun AlbumBlock(content: PostContent.PhotoAlbum, onMediaClick: (List<AlbumItem>, Int) -> Unit) {
    val items = content.items
    if (items.isEmpty()) return

    if (items.size == 1) {
        SingleMedia(items.first(), onClick = { onMediaClick(items, 0) })
    } else {
        AlbumPager(items, onItemClick = { idx -> onMediaClick(items, idx) })
    }

    if (content.caption.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = rememberAnnotatedString(content.caption),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SingleMedia(item: AlbumItem, onClick: () -> Unit) {
    val ratio = mediaAspectRatio(item.media.width, item.media.height)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        TdMediaImage(media = item.media, contentDescription = null, modifier = Modifier.fillMaxSize())
        when (item) {
            is AlbumItem.Video -> PlayBadge(item.durationSec)
            is AlbumItem.Animation -> DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            is AlbumItem.Photo -> Unit
        }
    }
}

@Composable
private fun AlbumPager(items: List<AlbumItem>, onItemClick: (Int) -> Unit) {
    val state = rememberPagerState(pageCount = { items.size })
    val ratio = items.firstOrNull()?.let { mediaAspectRatio(it.media.width, it.media.height) } ?: (16f / 10f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onItemClick(page) },
            ) {
                TdMediaImage(media = item.media, contentDescription = null, modifier = Modifier.fillMaxSize())
                when (item) {
                    is AlbumItem.Video -> PlayBadge(item.durationSec)
                    is AlbumItem.Animation -> DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                    is AlbumItem.Photo -> Unit
                }
            }
        }
        AlbumIndicator(
            current = state.currentPage,
            total = items.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun AlbumIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { idx ->
            val active = idx == current
            Box(
                modifier = Modifier
                    .size(width = if (active) 16.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}

@Composable
private fun VideoBlock(content: PostContent.Video, onMediaClick: (List<AlbumItem>, Int) -> Unit) {
    val items = listOf(AlbumItem.Video(content.media, content.durationSec, content.playbackFileId))
    SingleMedia(items.first(), onClick = { onMediaClick(items, 0) })

    if (content.caption.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = rememberAnnotatedString(content.caption),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AnimationBlock(content: PostContent.Animation, onMediaClick: (List<AlbumItem>, Int) -> Unit) {
    // Inline auto-loop playback: Telegram animations are silent MP4s, so we drive them via
    // ExoPlayer (Coil cannot decode MP4). Tap escalates to full-screen.
    val ratio = mediaAspectRatio(content.media.width, content.media.height)
    val items = listOf(AlbumItem.Animation(content.media, content.playbackFileId))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onMediaClick(items, 0) },
    ) {
        TdMediaImage(media = content.media, contentDescription = null, modifier = Modifier.fillMaxSize())
        TdVideoPlayer(
            fileId = content.playbackFileId,
            autoPlay = true,
            autoLoop = true,
            showControls = false,
            muted = true,
            modifier = Modifier.fillMaxSize(),
        )
        DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
    if (content.caption.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = rememberAnnotatedString(content.caption),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DocumentBlock(content: PostContent.Document) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.Description)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.fileName.ifBlank { "Документ" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatFileSize(content.sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (content.caption.text.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = rememberAnnotatedString(content.caption),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AudioBlock(content: PostContent.Audio) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.AudioFile)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.title.ifBlank { "Audio" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    content.performer.takeUnless { it.isBlank() },
                    formatDuration(content.durationSec),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceNoteBlock(content: PostContent.VoiceNote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Rounded.Mic)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Голосове повідомлення",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatDuration(content.durationSec),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoNoteBlock(content: PostContent.VideoNote) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            content.thumb?.let { TdMediaImage(media = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = formatDuration(content.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Rounded.VideoCameraFront,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StickerBlock(content: PostContent.Sticker) {
    val side = 168.dp
    Box(modifier = Modifier.size(side)) {
        TdMediaImage(media = content.media, contentDescription = content.emoji, modifier = Modifier.fillMaxSize())
        if (content.emoji.isNotEmpty()) {
            Text(
                text = content.emoji,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun PollBlock(content: PostContent.Poll) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Poll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (content.isAnonymous) "Анонімне опитування" else "Опитування",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = content.question,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        content.options.forEach { option ->
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = option.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${option.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { option.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${content.totalVotes} голосів",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationBlock(content: PostContent.Location) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Place)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                content.title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = content.address ?: "%.5f, %.5f".format(content.latitude, content.longitude),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContactBlock(content: PostContent.Contact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.AutoMirrored.Rounded.CallReceived)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(content.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(content.phone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiceBlock(content: PostContent.Dice) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = content.emoji,
            style = MaterialTheme.typography.displayLarge,
        )
    }
}

@Composable
private fun UnsupportedBlock(content: PostContent.Unsupported) {
    Text(
        text = content.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WebPreviewCard(preview: WebPreview) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        preview.image?.let {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                TdMediaImage(media = it, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (preview.siteName.isNotBlank()) {
                Text(
                    text = preview.siteName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (preview.title.isNotBlank()) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (preview.description.isNotBlank()) {
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.PlayBadge(durationSec: Int) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayCircleFilled,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
    DurationChip(
        text = formatDuration(durationSec),
        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
    )
}

@Composable
private fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun mediaAspectRatio(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 10f
    val raw = width.toFloat() / height.toFloat()
    // Clamp to keep extreme verticals/horizontals readable in the feed.
    return raw.coerceIn(9f / 16f, 21f / 9f)
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    return "%.1f %s".format(size, units[idx]).replace(".0 ", " ")
}
