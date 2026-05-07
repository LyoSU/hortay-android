package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.ExpiredKind
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ServiceEvent
import dev.lyo.hortay.data.hasSpoiler
import dev.lyo.hortay.data.isSecret
import dev.lyo.hortay.data.isUnplayableVideo
import androidx.compose.foundation.clickable
import dev.lyo.hortay.data.WebPreview
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.SpoilerKind
import dev.lyo.hortay.ui.media.SpoilerOverlay
import dev.lyo.hortay.ui.media.StickerView
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
    // Telegram renders single-emoji messages centered and oversized. When TDLib has
    // resolved an animated sticker variant (premium animated set / lottie / webm), we
    // play it through StickerView. The unicode emoji stays as a fallback for the brief
    // window where TDLib is still resolving the sticker — and as the permanent path
    // when no animated variant exists for that codepoint.
    val sticker = content.sticker
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (sticker != null && sticker.fileId != null) {
            StickerView(
                media = sticker,
                thumb = content.thumb,
                format = content.format,
                contentDescription = content.emoji,
                modifier = stickerBoxModifier(
                    width = sticker.width,
                    height = sticker.height,
                    maxSide = ANIMATED_EMOJI_MAX_SIDE,
                ),
            )
        } else {
            Text(
                text = content.emoji,
                style = MaterialTheme.typography.displayLarge,
            )
        }
    }
}

private val STICKER_MAX_SIDE = 168.dp
private val ANIMATED_EMOJI_MAX_SIDE = 140.dp
private val SPOILER_BLUR_RADIUS = 28.dp
private val UNPLAYABLE_VIDEO_BLUR_RADIUS = 8.dp

// Threshold for treating a video as "glance-able" — same heuristic Telegram uses for
// inline silent autoplay. Videos at or below this duration play muted-and-looping in
// the feed; longer ones keep the static poster + play-badge until the user opens
// fullscreen, where audio and controls are available.
private const val INLINE_AUTOPLAY_MAX_SEC = 60

/**
 * Constrain a sticker box to the natural aspect ratio reported by TDLib. The longer side
 * is pinned to [maxSide]; the shorter scales down proportionally. This matters for
 * non-square stickers — Telegram allows up to 512×N or N×512, and a hardcoded square
 * box would either crop the content or letterbox it with wide transparent strips
 * (TGS/WebM frames are transparent so the strip is invisible but the layout still
 * eats the space and pushes neighbours).
 *
 * Falls back to a square at [maxSide] when dimensions aren't reported (e.g. a sticker
 * descriptor without resolved width/height during a cold start).
 */
private fun stickerBoxModifier(width: Int, height: Int, maxSide: Dp): Modifier {
    if (width <= 0 || height <= 0) return Modifier.size(maxSide)
    val ratio = width.toFloat() / height.toFloat()
    return if (ratio >= 1f) {
        Modifier.width(maxSide).height(maxSide / ratio)
    } else {
        Modifier.width(maxSide * ratio).height(maxSide)
    }
}

@Composable
private fun ChecklistBlock(content: PostContent.Checklist, maxLines: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (content.title.text.isNotBlank()) {
            val titleRt = dev.lyo.hortay.ui.text.rememberRenderableText(content.title)
            Text(
                text = titleRt.text,
                inlineContent = titleRt.inlineContent,
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
                val taskRt = dev.lyo.hortay.ui.text.rememberRenderableText(task.text)
                Text(
                    text = taskRt.text,
                    inlineContent = taskRt.inlineContent,
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
        ExpiredKind.Photo -> "hide_image" to stringResource(R.string.expired_photo)
        ExpiredKind.Video -> "videocam_off" to stringResource(R.string.expired_video)
        ExpiredKind.VideoNote -> "videocam_off" to stringResource(R.string.expired_video_note)
        ExpiredKind.VoiceNote -> "mic_off" to stringResource(R.string.expired_voice)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
        is ServiceEvent.PinnedMessage -> "push_pin" to stringResource(R.string.service_pinned_message)
        is ServiceEvent.ChannelBoosted -> "rocket_launch" to
            pluralStringResource(R.plurals.service_boost, e.boostCount, e.boostCount)
        ServiceEvent.GiveawayStarted -> "card_giftcard" to stringResource(R.string.service_giveaway_started)
        ServiceEvent.ScreenshotTaken -> "photo_camera" to stringResource(R.string.service_screenshot)
        is ServiceEvent.VideoChatStarted -> "video_call" to stringResource(R.string.service_video_chat_started)
        ServiceEvent.VideoChatEnded -> "call_end" to stringResource(R.string.service_video_chat_ended)
        is ServiceEvent.GroupCall -> "call" to
            stringResource(if (e.isVideo) R.string.service_video_call else R.string.service_voice_call)
        ServiceEvent.Other -> "info" to stringResource(R.string.service_other)
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
            renderer = { annotated, inline, style, lines ->
                ExpandableText(annotated, inline, style, lines)
            },
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
        AlbumRow(items, onItemClick = { idx -> onMediaClick(items, idx) })
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
            .clip(MaterialTheme.shapes.medium),
    ) {
        MediaWithSpoiler(item = item, onClick = onClick)
    }
}

/**
 * Renders a single album item — photo, video poster, or animation thumbnail — with
 * an opt-in spoiler / sensitive-content cover. The spoiler shimmer intercepts taps
 * and reveals the underlying media; once revealed, taps fall through to [onClick]
 * (fullscreen open). Used by both [SingleMedia] and the inside of [AlbumPager].
 *
 * For short videos (≤ [INLINE_AUTOPLAY_MAX_SEC]) we render a silent looping
 * [TdVideoPlayer] on top of the poster instead of a static play badge — Telegram's
 * own UX for "glance-able" clips. The poster stays underneath so the slot has a
 * frame to show while the playback file streams in. [isActive] gates this for
 * pager pages: only the page the user is on streams; neighbours show the still
 * (so an album of 5 clips doesn't spawn five ExoPlayers).
 */
@Composable
private fun MediaWithSpoiler(item: AlbumItem, onClick: () -> Unit, isActive: Boolean = true) {
    var revealed by remember(item.media.fileId) {
        mutableStateOf(!item.hasSpoiler && !item.isSecret)
    }
    val unplayable = item.isUnplayableVideo
    val autoplayVideo = revealed
        && isActive
        && item is AlbumItem.Video
        && !unplayable
        && item.durationSec in 1..INLINE_AUTOPLAY_MAX_SEC
    // Blur regime:
    //   • spoiler / sensitive: heavy blur until the user reveals — same as TDLib mode.
    //   • unplayable video: light blur as a visual "this is a preview, you'll need to
    //     open Telegram to actually watch it" cue. Keeps the poster recognisable while
    //     making clear the in-app player can't drive it.
    //   • otherwise: no blur.
    val blur = when {
        !revealed -> SPOILER_BLUR_RADIUS
        unplayable -> UNPLAYABLE_VIDEO_BLUR_RADIUS
        else -> 0.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = revealed, onClick = onClick),
    ) {
        // When the autoplayer mounts on top, suppress the poster's own progress spinner —
        // TdVideoPlayer renders its own MediaLoadingOverlay for the playback file, and a
        // poster-side spinner would stack visibly on top of it ("два кружки в центрі").
        // Spoiler blur: while !revealed, the underlying photo is heavily blurred via
        // RenderEffect (no-op on pre-S, where the shimmer dim alone obscures the image).
        TdMediaImage(
            media = item.media,
            contentDescription = null,
            showProgress = !autoplayVideo,
            modifier = Modifier
                .fillMaxSize()
                .let { if (blur > 0.dp) it.blur(blur) else it },
        )
        if (autoplayVideo) {
            val video = item as AlbumItem.Video
            TdVideoPlayer(
                fileId = video.playbackFileId,
                remoteUrl = video.remoteVideoUrl,
                autoPlay = true,
                autoLoop = true,
                showControls = false,
                muted = true,
                modifier = Modifier.fillMaxSize(),
            )
            DurationChip(
                text = formatDuration(video.durationSec),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        } else {
            MediaOverlay(item)
        }
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
        is AlbumItem.Video -> {
            PlayBadge(item.durationSec)
            // Unplayable videos route the tap to Telegram — telegraph that
            // explicitly so the user understands where the tap is going
            // before they make it. A silent app-switch is jarring without
            // this cue.
            if (item.isUnplayableVideo) {
                OpenInTelegramHint(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }
        is AlbumItem.Animation -> DurationChip(
            text = "GIF",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        is AlbumItem.Photo -> Unit
    }
}

@Composable
private fun OpenInTelegramHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.media_open_in_telegram),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Symbol(
            name = "open_in_new",
            tint = Color.White,
            size = 14.dp,
        )
    }
}

/**
 * Threads/Instagram-style album row: each photo keeps its own aspect ratio at a fixed row
 * height, items scroll horizontally with snap fling and the next item peeks past the right
 * edge. We deliberately avoid HorizontalPager here — Pager forces every page to the same
 * width, which destroys the "portrait + landscape side-by-side" layout users expect for
 * mixed Telegram albums.
 *
 * Active-page tracking: only the most-centered item gets `isActive = true`, so silent
 * autoplay videos (≤ INLINE_AUTOPLAY_MAX_SEC) start exactly one ExoPlayer per album. This
 * preserves the same "one-player invariant" AlbumPager had via state.currentPage.
 */
@Composable
private fun AlbumRow(items: List<AlbumItem>, onItemClick: (Int) -> Unit) {
    val state = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(state)
    val activeIndex by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - viewportCenter) }
                ?.index ?: 0
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val rowHeight = rowWidth * 0.75f
        val maxItemWidth = rowWidth * 0.92f
        val minItemWidth = rowWidth * 0.42f

        LazyRow(
            state = state,
            flingBehavior = flingBehavior,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
        ) {
            itemsIndexed(items) { index, item ->
                val ratio = mediaAspectRatio(item.media.width, item.media.height)
                val itemWidth = (rowHeight * ratio).coerceIn(minItemWidth, maxItemWidth)
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    MediaWithSpoiler(
                        item = item,
                        onClick = { onItemClick(index) },
                        isActive = index == activeIndex,
                    )
                }
            }
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
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = revealed) { onMediaClick(items, 0) },
    ) {
        // Same suppression as MediaWithSpoiler: when the GIF autoplayer is mounted, its own
        // MediaLoadingOverlay covers the loading state — the poster's spinner would just
        // stack on top. When the spoiler is up, blur the still poster too.
        TdMediaImage(
            media = content.media,
            contentDescription = null,
            showProgress = !revealed,
            modifier = Modifier
                .fillMaxSize()
                .let { if (revealed) it else it.blur(SPOILER_BLUR_RADIUS) },
        )
        // Only mount the video player once the spoiler is revealed — otherwise we'd start
        // an ExoPlayer + TDLib download for content the user explicitly hasn't asked to see
        // yet, which is exactly the leak the spoiler/secret flags are meant to prevent.
        if (revealed) {
            TdVideoPlayer(
                fileId = content.playbackFileId,
                remoteUrl = content.remoteVideoUrl,
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
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge("description")
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.fileName.ifBlank { stringResource(R.string.document_unnamed) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sizeUnits = stringArrayResource(R.array.size_units)
            Text(
                text = formatFileSize(content.sizeBytes, sizeUnits),
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
            .clip(MaterialTheme.shapes.medium)
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
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge("mic")
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.voice_message),
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
    val boxModifier = stickerBoxModifier(
        width = content.media.width,
        height = content.media.height,
        maxSide = STICKER_MAX_SIDE,
    )
    Box(modifier = boxModifier) {
        // [media] is the playback file (.webp/.tgs/.webm) and [thumb] is TDLib's static
        // WEBP/PNG preview. StickerView shows the thumb instantly, then crossfades into
        // the rendered animation once the sticker file lands.
        StickerView(
            media = content.media,
            thumb = content.thumb,
            format = content.format,
            contentDescription = content.emoji,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PollBlock(content: PostContent.Poll) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
                text = stringResource(if (content.isAnonymous) R.string.poll_anonymous else R.string.poll_title),
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
            text = stringResource(R.string.poll_total_votes, content.totalVotes),
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
            .clip(MaterialTheme.shapes.medium)
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
            .clip(MaterialTheme.shapes.medium)
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
            .clip(MaterialTheme.shapes.medium)
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
                    .clip(MaterialTheme.shapes.small),
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
    // durationSec == 0 marks an unplayable video (currently only guest-mode
    // "Media is too big" posts where t.me strips `<video src>`). Showing a
    // "0:00" chip would lie about the post's length; just the play badge
    // reads correctly as "this is a video, tap to open it elsewhere".
    if (durationSec > 0) {
        DurationChip(
            text = formatDuration(durationSec),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
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
            .clip(MaterialTheme.shapes.small)
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
        renderer = { annotated, inline, style, lines ->
            ExpandableText(annotated, inline, style, lines)
        },
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
private fun ExpandableText(
    text: AnnotatedString,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent>,
    style: TextStyle,
    maxLines: Int,
) {
    if (maxLines == Int.MAX_VALUE) {
        // Detail screen path — never collapse, never offer a toggle.
        Text(text = text, inlineContent = inlineContent, style = style)
        return
    }
    var expanded by remember(text) { mutableStateOf(false) }
    var canExpand by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        inlineContent = inlineContent,
        style = style,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (!expanded && layout.hasVisualOverflow) canExpand = true
        },
    )
    if (canExpand && !expanded) {
        Text(
            text = stringResource(R.string.post_show_more),
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

private fun formatFileSize(bytes: Long, units: Array<String>): String {
    if (bytes <= 0) return "—"
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    val whole = size >= 100 || idx == 0 || size == size.toLong().toDouble()
    return if (whole) "${size.toLong()} ${units[idx]}" else "%.1f %s".format(size, units[idx])
}
