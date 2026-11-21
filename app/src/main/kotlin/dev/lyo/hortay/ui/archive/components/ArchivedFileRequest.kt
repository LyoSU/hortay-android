package dev.lyo.hortay.ui.archive.components

import android.content.Context
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Scale
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameOption
import coil3.video.videoFramePercent
import java.io.File

/**
 * Coil request for one of the archive's own media copies, `archive_media/<sha>.bin` — shared
 * by the revision sheet ([RevisionMediaPreview]) and the archive list row ([ArchiveEntryRow]).
 *
 * The `.bin` name gives Coil no MIME type, so for video, GIF (animation) and round-video
 * copies the default decoder chain tries the bitmap decoder, fails, and the box stays empty.
 * Naming `VideoFrameDecoder.Factory()` on the request is not enough either — that factory
 * returns null unless the fetch result's MIME type starts with `video/` (tried; it fell
 * straight to the preview-only fallback on device). [ArchivedVideoFrameDecoderFactory] skips
 * the MIME gate. Photos keep the default chain.
 *
 * The poster is the sync frame nearest [VIDEO_POSTER_FRAME_PERCENT] into the clip rather
 * than frame 0, which is black on clips that fade in. On a short clip the nearest sync frame
 * is usually the first one anyway; a longer clip lands on a real keyframe, so no extra
 * decoding past a GOP boundary.
 *
 * @param isVideoFrame decode a frame of a video-like copy instead of a bitmap; the caller
 *   knows the type from the stored media ref, never from the (extension-less) file.
 * @param sizePx decode to fill this square (a list thumbnail) instead of letting the
 *   composable's constraints decide; null keeps Coil's default sizing.
 */
internal fun archivedFileRequest(
    context: Context,
    path: String,
    isVideoFrame: Boolean,
    sizePx: Int? = null,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(File(path))
        .apply {
            if (sizePx != null) {
                size(sizePx)
                scale(Scale.FILL)
            }
            if (isVideoFrame) {
                decoderFactory(ArchivedVideoFrameDecoderFactory)
                videoFramePercent(VIDEO_POSTER_FRAME_PERCENT)
                videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }
        .build()

/**
 * [VideoFrameDecoder] without its factory's `video/` MIME check. Only attached to requests
 * for archived video-like copies, whose type is known from the stored media ref rather than
 * from the (extension-less) file. The decoder reads a file source by path through
 * `MediaMetadataRetriever`; a copy it can't read fails the request, which shows the
 * caller's fallback.
 */
private object ArchivedVideoFrameDecoderFactory : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
        VideoFrameDecoder(result.source, options)
}

private const val VIDEO_POSTER_FRAME_PERCENT = 0.1
