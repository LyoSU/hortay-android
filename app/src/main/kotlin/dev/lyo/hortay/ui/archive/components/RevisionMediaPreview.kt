package dev.lyo.hortay.ui.archive.components

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameOption
import coil3.video.videoFramePercent
import dev.lyo.hortay.R
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ArchivedMediaResolver
import dev.lyo.hortay.data.archive.ArchivedMediaSource
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.timeline.DurationChip
import dev.lyo.hortay.ui.timeline.formatDuration
import dev.lyo.hortay.ui.timeline.mediaAspectRatio
import java.io.File

/**
 * Media of one archived version, compact so the text — the thing whose history this is —
 * stays above the fold: true aspect ratio, height capped at [REVISION_MEDIA_MAX_HEIGHT],
 * start-aligned on the text inset, same shape and hairline frame as feed media.
 *
 * Sharp sources come from [ArchivedMediaResolver] (archive copy, then the same TDLib file
 * rendered through the feed's [TdMediaImage]). Only when neither exists does it show the
 * inline minithumb, blurred, with a "Preview only" pill in the bottom-start corner — the
 * same dark translucent badge language as the feed's GIF / duration chips. The old sheet
 * laid grey caption text across the blurred picture, where it was barely readable, and
 * showed the blur even for live posts whose photo was sitting in TDLib's cache.
 *
 * Documents, audio and voice versions have no picture; they render a one-line descriptor.
 */
@Composable
fun RevisionMediaPreview(
    media: ArchivedMediaRef,
    resolver: ArchivedMediaResolver?,
    postDeleted: Boolean,
    modifier: Modifier = Modifier,
) {
    when (media.type) {
        "photo", "video", "animation", "videoNote" -> VisualMedia(media, resolver, postDeleted, modifier)
        "document" -> Text(
            text = media.fileName ?: stringResource(R.string.revision_media_document),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        "audio", "voice" -> Text(
            text = stringResource(R.string.revision_media_audio_caption, media.durationMs / 1000L),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        else -> Unit
    }
}

@Composable
private fun VisualMedia(
    media: ArchivedMediaRef,
    resolver: ArchivedMediaResolver?,
    postDeleted: Boolean,
    modifier: Modifier,
) {
    val ratio = remember(media) { mediaAspectRatio(media.width, media.height).coerceIn(MIN_RATIO, MAX_RATIO) }
    // null while resolving: the minithumb shows without the "preview only" badge, so a
    // sharp source arriving a moment later doesn't flash a badge first.
    val source by produceState<ArchivedMediaSource?>(initialValue = null, media, resolver, postDeleted) {
        value = resolver?.resolve(media, postDeleted) ?: ArchivedMediaSource.PreviewOnly
    }
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .heightIn(max = REVISION_MEDIA_MAX_HEIGHT)
            .widthIn(max = REVISION_MEDIA_MAX_HEIGHT * ratio)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(shape)
            .mediaFrame(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        when (val s = source) {
            is ArchivedMediaSource.ArchivedFile -> ArchivedFileImage(media, s.path)
            is ArchivedMediaSource.TdlibFile -> TdMediaImage(
                media = TdMedia(
                    fileId = s.fileId,
                    width = media.width,
                    height = media.height,
                    minithumbBytes = media.minithumbBytes,
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            ArchivedMediaSource.PreviewOnly, null -> PreviewOnly(media, showBadge = s != null)
        }
        if (media.type != "photo" && media.durationMs > 0) {
            DurationChip(
                text = formatDuration((media.durationMs / 1000L).toInt()),
                modifier = Modifier.align(Alignment.BottomEnd).padding(BADGE_INSET),
            )
        }
    }
}

/**
 * The archive's own copy, `archive_media/<sha>.bin`. The `.bin` name gives Coil no MIME type,
 * so for video, GIF (animation) and round-video copies the default decoder chain tries the
 * bitmap decoder, fails, and the box stays empty. Naming `VideoFrameDecoder.Factory()` on the
 * request is not enough either — that factory returns null unless the fetch result's MIME
 * type starts with `video/` (tried; it fell straight to the preview-only fallback on device).
 * [ArchivedVideoFrameDecoderFactory] skips the MIME gate. Photos keep the default chain.
 *
 * The poster is the sync frame nearest [VIDEO_POSTER_FRAME_PERCENT] into the clip rather
 * than frame 0, which is black on clips that fade in. On a short clip the nearest sync frame
 * is usually the first one anyway; a longer clip lands on a real keyframe, so no extra
 * decoding past a GOP boundary.
 *
 * Until the file decodes, the blurred minithumb sits underneath without a badge (no flash
 * before a sharp frame lands); a copy that fails to decode (corrupt, unsupported codec)
 * falls back to the full [PreviewOnly] rendering with its badge instead of an empty box.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ArchivedFileImage(media: ArchivedMediaRef, path: String) {
    val context = LocalContext.current
    val isVideo = media.type != "photo"
    val request = remember(path, isVideo, context) {
        ImageRequest.Builder(context)
            .data(File(path))
            .apply {
                if (isVideo) {
                    decoderFactory(ArchivedVideoFrameDecoderFactory)
                    videoFramePercent(VIDEO_POSTER_FRAME_PERCENT)
                    videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
            .build()
    }
    var load by remember(path) { mutableStateOf(ArchivedLoad.Loading) }
    if (load != ArchivedLoad.Success) {
        PreviewOnly(media, showBadge = load == ArchivedLoad.Failed)
    }
    if (load != ArchivedLoad.Failed) {
        AsyncImage(
            model = request,
            contentDescription = null,
            onSuccess = { load = ArchivedLoad.Success },
            onError = { load = ArchivedLoad.Failed },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private enum class ArchivedLoad { Loading, Success, Failed }

/**
 * [VideoFrameDecoder] without its factory's `video/` MIME check. Only attached to requests
 * for archived non-photo copies, whose type is known from [ArchivedMediaRef.type] rather
 * than from the (extension-less) file. The decoder reads a file source by path through
 * `MediaMetadataRetriever`; a copy it can't read fails the request, which shows the
 * preview-only fallback.
 */
private object ArchivedVideoFrameDecoderFactory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
        VideoFrameDecoder(result.source, options)
}

private const val VIDEO_POSTER_FRAME_PERCENT = 0.1

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PreviewOnly(media: ArchivedMediaRef, showBadge: Boolean) {
    val bytes = media.minithumbBytes
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (bitmap == null) {
        if (showBadge) {
            Text(
                text = stringResource(R.string.revision_media_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().blur(PREVIEW_BLUR),
    )
    if (showBadge) {
        DurationChip(
            text = stringResource(R.string.revision_media_low_res_overlay),
            modifier = Modifier.align(Alignment.BottomStart).padding(BADGE_INSET),
        )
    }
}

/**
 * Height cap for a version's media. About a third of a typical phone's height (an
 * 1080×2340 phone is ~900 dp tall), so a portrait photo leaves the version's text visible
 * without scrolling the sheet.
 */
private val REVISION_MEDIA_MAX_HEIGHT: Dp = 280.dp
private val BADGE_INSET = 8.dp
private val PREVIEW_BLUR = 12.dp
private const val MIN_RATIO = 0.4f
private const val MAX_RATIO = 3f
