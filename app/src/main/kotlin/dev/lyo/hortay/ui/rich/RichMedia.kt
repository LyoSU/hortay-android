package dev.lyo.hortay.ui.rich

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichCaption
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.timeline.AlbumRow
import dev.lyo.hortay.ui.timeline.MediaWithSpoiler
import dev.lyo.hortay.ui.timeline.NonPlayableFileRow
import dev.lyo.hortay.ui.timeline.SingleMedia
import dev.lyo.hortay.ui.timeline.formatDuration
import dev.lyo.hortay.ui.timeline.mediaAspectRatio

/**
 * Real renderings for the media-bearing rich blocks. Every one reuses the feed's media
 * components verbatim (see [dev.lyo.hortay.ui.timeline.PostMediaBlocks]):
 *
 *  - Photo / Video / Animation → [SingleMedia] (aspect-ratio box + hairline frame + spoiler
 *    cover + minithumb→full crossfade; short videos silent-autoplay through the feed's
 *    cache-ready + [dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay] probe).
 *  - Collage → [AlbumRow] (the app's album row, all items in one snap-scrolling strip).
 *  - Slideshow → a [HorizontalPager], one item per page, with dot indicators.
 *  - Audio / VoiceNote → [NonPlayableFileRow] (informational, no source-post tap yet).
 *
 * Tap on a photo / video / animation / collage / slideshow item opens the shared
 * [dev.lyo.hortay.ui.media.FullScreenMediaViewer] via [LocalMediaViewer] — that controller
 * takes a plain `List<AlbumItem>`, so the viewer mounts without any `PostContent` coupling.
 *
 * A media handle is nullable in the domain model ("media unavailable"); when the projected
 * [AlbumItem] list is empty the block falls back to [RichMediaPlaceholder] instead of crashing.
 */
@Composable
internal fun RichPhoto(block: RichBlock.Photo) {
    val item = remember(block) { block.toAlbumItem() }
    if (item == null) {
        RichMediaPlaceholder("image", block.caption)
        return
    }
    RichMediaColumn(block.caption) {
        val viewer = LocalMediaViewer.current
        val items = remember(item) { listOf(item) }
        SingleMedia(item, onClick = { viewer.open(items, 0) })
    }
}

@Composable
internal fun RichVideo(block: RichBlock.Video) {
    val item = remember(block) { block.toAlbumItem() }
    if (item == null) {
        RichMediaPlaceholder("play_circle", block.caption)
        return
    }
    RichMediaColumn(block.caption) {
        val viewer = LocalMediaViewer.current
        val items = remember(item) { listOf(item) }
        SingleMedia(item, onClick = { viewer.open(items, 0) })
    }
}

@Composable
internal fun RichAnimation(block: RichBlock.Animation) {
    val item = remember(block) { block.toAlbumItem() }
    if (item == null) {
        RichMediaPlaceholder("gif_box", block.caption)
        return
    }
    RichMediaColumn(block.caption) {
        val viewer = LocalMediaViewer.current
        val items = remember(item) { listOf(item) }
        SingleMedia(item, onClick = { viewer.open(items, 0) })
    }
}

@Composable
internal fun RichAudio(block: RichBlock.Audio) {
    RichMediaColumn(block.caption) {
        NonPlayableFileRow(
            symbol = "audio_file",
            primary = block.title.ifBlank { stringResource(R.string.content_audio_fallback) },
            secondary = listOfNotNull(
                block.performer.takeUnless { it.isBlank() },
                formatDuration(block.durationSec),
            ).joinToString(" · "),
            onClick = null,
        )
    }
}

@Composable
internal fun RichVoiceNote(block: RichBlock.VoiceNote) {
    RichMediaColumn(block.caption) {
        NonPlayableFileRow(
            symbol = "mic",
            primary = stringResource(R.string.voice_message),
            secondary = formatDuration(block.durationSec),
            onClick = null,
            shape = MaterialTheme.shapes.large,
        )
    }
}

@Composable
internal fun RichCollage(block: RichBlock.Collage) {
    val items = remember(block) { block.items.toAlbumItems() }
    if (items.isEmpty()) {
        RichMediaPlaceholder("image", block.caption)
        return
    }
    RichMediaColumn(block.caption) {
        val viewer = LocalMediaViewer.current
        if (items.size == 1) {
            SingleMedia(items.first(), onClick = { viewer.open(items, 0) })
        } else {
            AlbumRow(items, onItemClick = { idx -> viewer.open(items, idx) })
        }
    }
}

@Composable
internal fun RichSlideshow(block: RichBlock.Slideshow) {
    val items = remember(block) { block.items.toAlbumItems() }
    if (items.isEmpty()) {
        RichMediaPlaceholder("image", block.caption)
        return
    }
    if (items.size == 1) {
        RichMediaColumn(block.caption) {
            val viewer = LocalMediaViewer.current
            SingleMedia(items.first(), onClick = { viewer.open(items, 0) })
        }
        return
    }
    RichMediaColumn(block.caption) {
        val viewer = LocalMediaViewer.current
        val pagerState = rememberPagerState(pageCount = { items.size })
        // Tallest item drives the shared pager height (smallest w/h ratio), so no page is
        // cropped; wider pages letterbox inside the fixed frame.
        val ratio = remember(items) {
            items.minOf { mediaAspectRatio(it.media.width, it.media.height) }
        }
        val pageLabel = stringResource(R.string.rich_slideshow_page, pagerState.currentPage + 1, items.size)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .semantics { contentDescription = pageLabel },
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .mediaFrame(MaterialTheme.shapes.medium),
            ) {
                MediaWithSpoiler(
                    item = item,
                    onClick = { viewer.open(items, page) },
                    isActive = page == pagerState.currentPage,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PagerDots(count = items.size, selected = pagerState.currentPage)
    }
}

@Composable
internal fun RichMapPreview(block: RichBlock.MapPreview) {
    RichMediaColumn(block.caption) {
        // Mirrors the feed's Location card idiom (surfaceContainerHigh card + place badge);
        // TDLib delivers only coordinates in a rich message (no static-map image URL), so we
        // surface the position rather than a rendered tile.
        NonPlayableFileRow(
            symbol = "place",
            primary = "%.5f, %.5f".format(block.latitude, block.longitude),
            secondary = "",
            onClick = null,
        )
    }
}

/**
 * Stacks a media surface and its caption in one block slot so the caption hugs the media
 * (the outer [RichBlocks] column only spaces sibling blocks). The caption text + credit
 * render muted through the shared inline renderer, matching the feed's caption spacing.
 */
@Composable
private fun RichMediaColumn(caption: RichCaption?, media: @Composable () -> Unit) {
    Column {
        media()
        RichCaptionText(caption)
    }
}

@Composable
private fun RichCaptionText(caption: RichCaption?) {
    val text = caption?.text
    val credit = caption?.credit
    if (text == null && credit == null) return
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (text != null) {
            RichInlineText(
                inline = text,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (credit != null) {
            RichInlineText(
                inline = credit,
                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

private val DOT_SIZE = 6.dp
private val DOT_SELECTED_WIDTH = 16.dp

@Composable
private fun PagerDots(count: Int, selected: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(
                targetValue = if (active) DOT_SELECTED_WIDTH else DOT_SIZE,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "rich-slideshow-dot",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = DOT_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    ),
            )
        }
    }
}

// ---- Domain → AlbumItem projection -------------------------------------------

private fun RichBlock.Photo.toAlbumItem(): AlbumItem.Photo? =
    media?.let { AlbumItem.Photo(media = it, hasSpoiler = hasSpoiler) }

private fun RichBlock.Video.toAlbumItem(): AlbumItem.Video? =
    media?.let {
        AlbumItem.Video(
            media = it,
            durationSec = durationSec,
            playbackFileId = playbackFileId,
            // The feed's video path (quality picker) needs a non-null [VideoQualities]; when
            // the rich block ships none, synthesise a single-quality descriptor from the
            // playback file so the inline poster + fullscreen open still work.
            qualities = qualities ?: singleQuality(playbackFileId, it),
            hasSpoiler = hasSpoiler,
        )
    }

private fun RichBlock.Animation.toAlbumItem(): AlbumItem.Animation? =
    media?.let { AlbumItem.Animation(media = it, playbackFileId = playbackFileId, hasSpoiler = hasSpoiler) }

private fun richBlockToAlbumItem(block: RichBlock): AlbumItem? = when (block) {
    is RichBlock.Photo -> block.toAlbumItem()
    is RichBlock.Video -> block.toAlbumItem()
    is RichBlock.Animation -> block.toAlbumItem()
    else -> null
}

private fun List<RichBlock>.toAlbumItems(): List<AlbumItem> = mapNotNull(::richBlockToAlbumItem)

private fun singleQuality(playbackFileId: Int, media: TdMedia): VideoQualities =
    VideoQualities(
        original = VideoQuality(
            fileId = playbackFileId,
            width = media.width,
            height = media.height,
            label = "",
            sizeBytes = 0L,
        ),
    )
