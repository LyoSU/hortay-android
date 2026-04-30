package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.ExpiredKind
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ServiceEvent
import dev.lyo.hortay.data.hasSpoiler
import dev.lyo.hortay.data.isSecret
import androidx.compose.foundation.clickable
import dev.lyo.hortay.data.WebPreview
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.SpoilerKind
import dev.lyo.hortay.ui.media.SpoilerOverlay
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.TdVideoPlayer
import dev.lyo.hortay.ui.text.RichText
import dev.lyo.hortay.ui.text.rememberAnnotatedString

/**
 * Render the body of a post. [onMediaClick] fires with the resolved media list and the
 * index the user tapped, so callers can open a full-screen viewer with the correct page.
 */
@Composable
fun PostBody(
    content: PostContent,
    modifier: Modifier = Modifier,
    onMediaClick: (List<AlbumItem>, Int) -> Unit = { _, _ -> },
    /** When true, text is rendered without `maxLines` clamps — used in detail screens. */
    expanded: Boolean = false,
    /**
     * Translated body. When non-null, text/caption blocks render this instead of the
     * original `content.formatted` / `content.caption`. Other variants (sticker, poll,
     * location…) ignore the translation — they have nothing to translate.
     */
    translation: FormattedText? = null,
) {
    val textLimit = if (expanded) Int.MAX_VALUE else 18
    val captionLimit = if (expanded) Int.MAX_VALUE else 12
    Column(modifier = modifier) {
        when (content) {
            is PostContent.Text -> TextBlock(content, textLimit, translation)
            is PostContent.PhotoAlbum -> AlbumBlock(content, onMediaClick, captionLimit, translation)
            is PostContent.Video -> VideoBlock(content, onMediaClick, captionLimit, translation)
            is PostContent.Animation -> AnimationBlock(content, onMediaClick, captionLimit, translation)
            is PostContent.Document -> DocumentBlock(content, captionLimit, translation)
            is PostContent.Audio -> AudioBlock(content)
            is PostContent.VoiceNote -> VoiceNoteBlock(content)
            is PostContent.VideoNote -> VideoNoteBlock(content)
            is PostContent.Sticker -> StickerBlock(content)
            is PostContent.Poll -> PollBlock(content)
            is PostContent.Location -> LocationBlock(content)
            is PostContent.Contact -> ContactBlock(content)
            is PostContent.Dice -> DiceBlock(content)
            is PostContent.AnimatedEmoji -> AnimatedEmojiBlock(content)
            is PostContent.Checklist -> ChecklistBlock(content, captionLimit)
            is PostContent.ExpiredMedia -> ExpiredMediaBlock(content)
            is PostContent.Service -> ServiceBlock(content)
            is PostContent.Unsupported -> UnsupportedBlock(content)
        }
    }
}

@Composable
private fun AnimatedEmojiBlock(content: PostContent.AnimatedEmoji) {
    // Telegram renders these centered and oversized when the message contains a single
    // emoji. We mirror that — the sticker variant (custom-emoji set / lottie) needs full
    // sticker rendering we don't have yet, so for now the unicode emoji at display-large
    // size is the consistent fallback.
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = content.emoji,
            style = MaterialTheme.typography.displayLarge,
        )
    }
}

@Composable
private fun ChecklistBlock(content: PostContent.Checklist, maxLines: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (content.title.text.isNotBlank()) {
            Text(
                text = dev.lyo.hortay.ui.text.rememberAnnotatedString(content.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
        }
        content.tasks.forEach { task ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Symbol(
                    name = if (task.isDone) "check_box" else "check_box_outline_blank",
                    tint = if (task.isDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 20.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = dev.lyo.hortay.ui.text.rememberAnnotatedString(task.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ExpiredMediaBlock(content: PostContent.ExpiredMedia) {
    val (symbol, label) = when (content.kind) {
        ExpiredKind.Photo -> "hide_image" to "Тимчасове фото зникло"
        ExpiredKind.Video -> "videocam_off" to "Тимчасове відео зникло"
        ExpiredKind.VideoNote -> "videocam_off" to "Кружок зник"
        ExpiredKind.VoiceNote -> "mic_off" to "Голосове зникло"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = symbol, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceBlock(content: PostContent.Service) {
    val (symbol, label) = when (val e = content.event) {
        is ServiceEvent.PinnedMessage -> "push_pin" to "Закріпили повідомлення"
        is ServiceEvent.ChannelBoosted -> "rocket_launch" to
            if (e.boostCount > 1) "Канал отримав ${e.boostCount} бустів" else "Канал отримав буст"
        ServiceEvent.GiveawayStarted -> "card_giftcard" to "Розпочато розіграш"
        ServiceEvent.ScreenshotTaken -> "photo_camera" to "Скріншот"
        is ServiceEvent.VideoChatStarted -> "video_call" to "Розпочато груповий дзвінок"
        ServiceEvent.VideoChatEnded -> "call_end" to "Груповий дзвінок завершено"
        is ServiceEvent.GroupCall -> "call" to
            if (e.isVideo) "Відеодзвінок" else "Голосовий дзвінок"
        ServiceEvent.Other -> "info" to "Системне повідомлення"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextBlock(content: PostContent.Text, maxLines: Int, translation: FormattedText?) {
    val rendered = translation ?: content.formatted
    if (rendered.text.isNotEmpty()) {
        RichText(
            formatted = rendered,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = maxLines,
            renderer = { annotated, style, lines -> ExpandableText(annotated, style, lines) },
        )
    }
    content.webPreview?.let {
        Spacer(Modifier.height(12.dp))
        WebPreviewCard(it)
    }
}

@Composable
private fun AlbumBlock(content: PostContent.PhotoAlbum, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    val items = content.items
    if (items.isEmpty()) return
    val caption = translation ?: content.caption

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    if (items.size == 1) {
        SingleMedia(items.first(), onClick = { onMediaClick(items, 0) })
    } else {
        AlbumPager(items, onItemClick = { idx -> onMediaClick(items, idx) })
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun SingleMedia(item: AlbumItem, onClick: () -> Unit) {
    val ratio = mediaAspectRatio(item.media.width, item.media.height)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        MediaWithSpoiler(item = item, onClick = onClick)
    }
}

/**
 * Renders a single album item — photo, video poster, or animation thumbnail — with
 * an opt-in spoiler / sensitive-content cover. The spoiler shimmer intercepts taps
 * and reveals the underlying media; once revealed, taps fall through to [onClick]
 * (fullscreen open). Used by both [SingleMedia] and the inside of [AlbumPager].
 */
@Composable
private fun MediaWithSpoiler(item: AlbumItem, onClick: () -> Unit) {
    var revealed by remember(item.media.fileId) {
        mutableStateOf(!item.hasSpoiler && !item.isSecret)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = revealed, onClick = onClick),
    ) {
        TdMediaImage(media = item.media, contentDescription = null, modifier = Modifier.fillMaxSize())
        MediaOverlay(item)
        if (!revealed) {
            SpoilerOverlay(
                kind = if (item.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = item.media.fileId ?: 0,
                onReveal = { revealed = true },
            )
        }
    }
}

@Composable
private fun BoxScope.MediaOverlay(item: AlbumItem) {
    when (item) {
        is AlbumItem.Video -> PlayBadge(item.durationSec)
        is AlbumItem.Animation -> DurationChip(
            text = "GIF",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        is AlbumItem.Photo -> Unit
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
            Box(modifier = Modifier.fillMaxSize()) {
                MediaWithSpoiler(item = item, onClick = { onItemClick(page) })
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
private fun VideoBlock(content: PostContent.Video, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    val item = AlbumItem.Video(
        media = content.media,
        durationSec = content.durationSec,
        playbackFileId = content.playbackFileId,
        qualities = content.qualities,
        hasSpoiler = content.hasSpoiler,
        isSecret = content.isSecret,
    )
    val items = listOf(item)
    val caption = translation ?: content.caption
    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    SingleMedia(item, onClick = { onMediaClick(items, 0) })
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun AnimationBlock(content: PostContent.Animation, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    // Inline auto-loop playback: Telegram animations are silent MP4s, so we drive them via
    // ExoPlayer (Coil cannot decode MP4). Tap escalates to full-screen.
    val ratio = mediaAspectRatio(content.media.width, content.media.height)
    val items = listOf(
        AlbumItem.Animation(
            media = content.media,
            playbackFileId = content.playbackFileId,
            hasSpoiler = content.hasSpoiler,
            isSecret = content.isSecret,
        ),
    )
    val caption = translation ?: content.caption

    var revealed by remember(content.playbackFileId) {
        mutableStateOf(!content.hasSpoiler && !content.isSecret)
    }

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = revealed) { onMediaClick(items, 0) },
    ) {
        TdMediaImage(media = content.media, contentDescription = null, modifier = Modifier.fillMaxSize())
        // Only mount the video player once the spoiler is revealed — otherwise we'd start
        // an ExoPlayer + TDLib download for content the user explicitly hasn't asked to see
        // yet, which is exactly the leak the spoiler/secret flags are meant to prevent.
        if (revealed) {
            TdVideoPlayer(
                fileId = content.playbackFileId,
                autoPlay = true,
                autoLoop = true,
                showControls = false,
                muted = true,
                modifier = Modifier.fillMaxSize(),
            )
            DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        } else {
            SpoilerOverlay(
                kind = if (content.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = content.playbackFileId,
                onReveal = { revealed = true },
            )
        }
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun DocumentBlock(content: PostContent.Document, maxLines: Int, translation: FormattedText?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge("description")
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
    // Documents never carry the caption-above flag (Telegram only exposes that toggle for
    // photo/video/animation/paid-media), so we always render below.
    MediaCaption(translation ?: content.caption, maxLines, above = false, show = true)
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
        IconBadge("audio_file")
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
        IconBadge("mic")
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
        Symbol(
            name = "video_camera_front",
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
            Symbol(
                name = "poll",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
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
            IconBadge("place")
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
        IconBadge("call_received")
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
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = preview.url.isNotBlank()) {
                runCatching { uriHandler.openUri(preview.url) }
            }
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
        Symbol(
            name = "play_circle",
            tint = Color.White,
            size = 36.dp,
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
private fun IconBadge(symbol: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 22.dp,
        )
    }
}

/**
 * Caption row that wraps photo/video/animation/document blocks. Telegram supports
 * caption-above-media (set when the poster ticks "Show caption above") so each block
 * calls this twice — once with [above]=true, once below — and the [show] flag picks
 * which one renders. Spacer goes on the side adjacent to the media so the gap between
 * caption and media is consistent regardless of which side the caption lives on.
 */
@Composable
private fun MediaCaption(caption: FormattedText, maxLines: Int, above: Boolean, show: Boolean) {
    if (!show || caption.text.isBlank()) return
    if (!above) Spacer(Modifier.height(12.dp))
    RichText(
        formatted = caption,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = maxLines,
        renderer = { annotated, style, lines -> ExpandableText(annotated, style, lines) },
    )
    if (above) Spacer(Modifier.height(12.dp))
}

/**
 * Self-contained Text + "Показати більше" toggle. The collapsed render binds the layout
 * callback to flip [canExpand] when Compose reports overflow at [maxLines]; we deliberately
 * keep the toggle hidden until that signal lands so short posts never see it. Tapping the
 * toggle flips [expanded] and the same Text re-renders without a clamp.
 *
 * State is keyed on the [text] reference so editing a post or scrolling away and back
 * resets to collapsed — same as the official Telegram client.
 */
@Composable
private fun ExpandableText(text: AnnotatedString, style: TextStyle, maxLines: Int) {
    if (maxLines == Int.MAX_VALUE) {
        // Detail screen path — never collapse, never offer a toggle.
        Text(text = text, style = style)
        return
    }
    var expanded by remember(text) { mutableStateOf(false) }
    var canExpand by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = style,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (!expanded && layout.hasVisualOverflow) canExpand = true
        },
    )
    if (canExpand && !expanded) {
        Text(
            text = "Показати більше",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { expanded = true },
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
    val whole = size >= 100 || idx == 0 || size == size.toLong().toDouble()
    return if (whole) "${size.toLong()} ${units[idx]}" else "%.1f %s".format(size, units[idx])
}
