package dev.lyo.hortay.ui.rich

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.InlineAutoplay
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichCaption
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.timeline.MediaWithSpoiler
import dev.lyo.hortay.ui.util.rememberReducedMotion
import dev.lyo.hortay.ui.timeline.NonPlayableFileRow
import dev.lyo.hortay.ui.timeline.SingleMedia
import dev.lyo.hortay.ui.timeline.formatDuration
import dev.lyo.hortay.ui.timeline.mediaAspectRatio
import kotlin.math.roundToInt

/**
 * Real renderings for the media-bearing rich blocks. Every one reuses the feed's media
 * components verbatim (see [dev.lyo.hortay.ui.timeline.PostMediaBlocks]):
 *
 *  - Photo / Video / Animation → [SingleMedia] (aspect-ratio box + hairline frame + spoiler
 *    cover + minithumb→full crossfade; short videos silent-autoplay through the feed's
 *    cache-ready + [dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay] probe).
 *  - Collage → a mosaic grid ([RichCollageMosaic], geometry from [mosaicLayout]) with a
 *    "+N" overflow tile past four members.
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
            // A one-member collage isn't really a collage; render it as a plain photo rather
            // than a degenerate single-tile mosaic.
            SingleMedia(items.first(), onClick = { viewer.open(items, 0) })
        } else {
            RichCollageMosaic(items, onItemClick = { idx -> viewer.open(items, idx) })
        }
    }
}

private val MOSAIC_RADIUS = 12.dp
private val MOSAIC_GAP = 2.dp

/**
 * Telegram-style collage grid: tiles laid out by the pure [mosaicLayout] geometry (2-up,
 * 3-up, 2×2, or 2×2 with a "+N" overflow tile). Only the mosaic's four outer corners round
 * to [MOSAIC_RADIUS]; inner seams stay square and are separated by a [MOSAIC_GAP] gutter.
 * A single [dev.lyo.hortay.ui.theme.mediaFrame] hairline traces the whole mosaic so a pale
 * collage doesn't dissolve into the canvas — no per-tile frames (the gutters read as the
 * only inner separation, matching Telegram's IV collage).
 */
@Composable
private fun RichCollageMosaic(items: List<AlbumItem>, onItemClick: (Int) -> Unit) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gapPx = with(density) { MOSAIC_GAP.toPx() }
    val count = items.size
    // Corner flags / source indices are width-independent; resolve them once so each tile's
    // clip shape and content are stable across measures. Positions are recomputed at measure
    // from the real width.
    val tiles = remember(count, isRtl) { mosaicLayout(count, width = 1f, gap = 0f, isRtl = isRtl).cells }
    val outerShape = remember { RoundedCornerShape(MOSAIC_RADIUS) }
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .mediaFrame(outerShape),
        content = {
            tiles.forEach { cell -> MosaicTile(cell = cell, items = items, onItemClick = onItemClick) }
        },
    ) { measurables, constraints ->
        val widthPx = constraints.maxWidth
        val geometry = mosaicLayout(count, widthPx.toFloat(), gapPx, isRtl)
        val placeables = measurables.mapIndexed { index, measurable ->
            val cell = geometry.cells[index]
            measurable.measure(
                Constraints.fixed(
                    width = cell.widthPx.roundToInt().coerceAtLeast(0),
                    height = cell.heightPx.roundToInt().coerceAtLeast(0),
                ),
            )
        }
        layout(widthPx, geometry.height.roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val cell = geometry.cells[index]
                placeable.place(cell.left.roundToInt(), cell.top.roundToInt())
            }
        }
    }
}

@Composable
private fun MosaicTile(cell: RichMosaicCell, items: List<AlbumItem>, onItemClick: (Int) -> Unit) {
    val shape = remember(cell) {
        AbsoluteRoundedCornerShape(
            topLeft = if (cell.roundTopLeft) MOSAIC_RADIUS else 0.dp,
            topRight = if (cell.roundTopRight) MOSAIC_RADIUS else 0.dp,
            bottomRight = if (cell.roundBottomRight) MOSAIC_RADIUS else 0.dp,
            bottomLeft = if (cell.roundBottomLeft) MOSAIC_RADIUS else 0.dp,
        )
    }
    val item = items[cell.sourceIndex]
    val openIndex = cell.viewerIndex()
    val onClick = remember(openIndex, onItemClick) { { onItemClick(openIndex) } }
    Box(modifier = Modifier.clip(shape)) {
        // A static grid has no "most-centered" tile to gate autoplay on (unlike the scrolling
        // AlbumRow), so only the first tile is eligible — keeps the one-player invariant on the
        // rare collage that mixes in short videos; photo tiles ignore isActive.
        MediaWithSpoiler(item = item, onClick = onClick, isActive = cell.sourceIndex == 0)
        if (cell.overflow > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.rich_collage_more, cell.overflow),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/** Beyond this page count the dot row is replaced by a compact "n / total" counter. */
private const val SLIDESHOW_DOT_LIMIT = 6
private val SLIDESHOW_PEEK = 22.dp
private val SLIDESHOW_PAGE_SPACING = 6.dp

/** One slideshow page: its media plus the per-item caption the AST carries (often null). */
@Immutable
private data class RichSlide(val item: AlbumItem, val caption: RichCaption?)

@Composable
internal fun RichSlideshow(block: RichBlock.Slideshow) {
    val pages = remember(block) {
        block.items.mapNotNull { child -> richBlockToAlbumItem(child)?.let { RichSlide(it, child.slideshowCaption()) } }
    }
    if (pages.isEmpty()) {
        RichMediaPlaceholder("image", block.caption)
        return
    }
    val viewer = LocalMediaViewer.current
    val items = remember(pages) { pages.map { it.item } }
    if (items.size == 1) {
        RichMediaColumn(block.caption) {
            SingleMedia(items.first(), onClick = { viewer.open(items, 0) })
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    // One stable frame for every page: the tallest item (smallest w/h ratio) fixes the height so
    // swiping between a portrait and a landscape page never resizes the frame; wider pages crop.
    val ratio = remember(items) { items.minOf { mediaAspectRatio(it.media.width, it.media.height) } }
    val hasPerPageCaptions = remember(pages) { pages.any { it.caption.hasContent() } }
    val reducedMotion = rememberReducedMotion()
    val captionSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Column {
        val pageLabel = stringResource(R.string.rich_slideshow_page, pagerState.currentPage + 1, items.size)
        Box(modifier = Modifier.fillMaxWidth().semantics { contentDescription = pageLabel }) {
            HorizontalPager(
                state = pagerState,
                // A sliver of the neighbouring pages peeks past each edge — a stateless swipe
                // affordance (no one-time hint animation to persist).
                contentPadding = PaddingValues(horizontal = SLIDESHOW_PEEK),
                pageSpacing = SLIDESHOW_PAGE_SPACING,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
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
            SlideshowIndicator(
                current = pagerState.currentPage,
                count = items.size,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
        if (hasPerPageCaptions) {
            AnimatedContent(
                targetState = pagerState.currentPage,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        fadeIn(captionSpec) togetherWith fadeOut(captionSpec)
                    }
                },
                label = "rich-slideshow-caption",
            ) { page ->
                RichCaptionText(pages[page].caption)
            }
        }
        RichCaptionText(block.caption)
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
    // Full captions on the reading surface (post-detail / comments anchor); the feed preview keeps
    // the short clamp so a long caption can't blow up a feed card.
    val captionMaxLines = if (LocalRichReading.current) Int.MAX_VALUE else 6
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (text != null) {
            RichInlineText(
                inline = text,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = captionMaxLines,
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

/**
 * Page position rendered OVER the media, inside a translucent scrim pill so it stays legible on
 * any photo. Up to [SLIDESHOW_DOT_LIMIT] pages show a dot row; past that a compact "n / total"
 * counter takes over so a long slideshow doesn't grow an unreadable stripe of dots.
 */
@Composable
private fun SlideshowIndicator(current: Int, count: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (count > SLIDESHOW_DOT_LIMIT) {
            Text(
                text = stringResource(R.string.rich_slideshow_counter, current + 1, count),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        } else {
            PagerDots(count = count, selected = current)
        }
    }
}

@Composable
private fun PagerDots(count: Int, selected: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(
                targetValue = if (active) DOT_SELECTED_WIDTH else DOT_SIZE,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "rich-slideshow-dot",
            )
            // On-scrim palette: accent for the selected page, dimmed white for the rest — the
            // pill's dark scrim guarantees contrast over any underlying photo.
            Box(
                modifier = Modifier
                    .size(width = width, height = DOT_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
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
            // TDLib's instant-view flags drive playback instead of the feed's duration
            // heuristic: `needAutoplay=false` → static poster + play button, `isLooped=false`
            // → play once.
            autoplay = if (needAutoplay) InlineAutoplay.Always else InlineAutoplay.Never,
            loop = isLooped,
        )
    }

private fun RichBlock.Animation.toAlbumItem(): AlbumItem.Animation? =
    media?.let {
        AlbumItem.Animation(
            media = it,
            playbackFileId = playbackFileId,
            hasSpoiler = hasSpoiler,
            autoplay = if (needAutoplay) InlineAutoplay.Always else InlineAutoplay.Never,
        )
    }

private fun richBlockToAlbumItem(block: RichBlock): AlbumItem? = when (block) {
    is RichBlock.Photo -> block.toAlbumItem()
    is RichBlock.Video -> block.toAlbumItem()
    is RichBlock.Animation -> block.toAlbumItem()
    else -> null
}

private fun List<RichBlock>.toAlbumItems(): List<AlbumItem> = mapNotNull(::richBlockToAlbumItem)

/** The per-item caption a slideshow child carries, if it's a media block. */
private fun RichBlock.slideshowCaption(): RichCaption? = when (this) {
    is RichBlock.Photo -> caption
    is RichBlock.Video -> caption
    is RichBlock.Animation -> caption
    else -> null
}

private fun RichCaption?.hasContent(): Boolean = this != null && (text != null || credit != null)

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
