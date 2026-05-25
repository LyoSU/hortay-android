package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchivedMediaRef
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import java.io.File

/**
 * Tri-state media preview for an archived revision:
 *
 *   1. **Best**: file is in archive storage (the snapshot captured it before
 *      TDLib evicted) — render full-resolution from disk via Coil.
 *   2. **Fallback**: only the inline minithumb survived (file wasn't downloaded
 *      at capture time) — render the 40-px JPEG, blurred slightly so the
 *      coarseness doesn't read as a defect.
 *   3. **Nothing**: neither — render a "media unavailable" placeholder card
 *      that tells the user the bytes weren't captured (NOT a generic error).
 *
 * Aspect ratio comes from [ArchivedMediaRef.width] / [ArchivedMediaRef.height]
 * when present, falling back to 16:9. Non-image types (audio, voice, document,
 * video-note) skip the image surface and render a small descriptor row only.
 */
@Composable
fun RevisionMediaPreview(
    media: ArchivedMediaRef,
    mediaStore: ArchivedMediaStore?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (media.type) {
            "photo", "video", "animation", "videoNote" -> {
                ImageOrThumb(media = media, mediaStore = mediaStore)
                if (media.type == "video" || media.type == "animation" || media.type == "videoNote") {
                    Text(
                        text = stringResource(
                            R.string.revision_media_video_caption,
                            media.durationMs / 1000L,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            "document" -> Text(
                text = media.fileName ?: stringResource(R.string.revision_media_document),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            "audio", "voice" -> Text(
                text = stringResource(
                    R.string.revision_media_audio_caption,
                    media.durationMs / 1000L,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun ImageOrThumb(media: ArchivedMediaRef, mediaStore: ArchivedMediaStore?) {
    val ratio = remember(media) {
        if (media.width > 0 && media.height > 0) {
            media.width.toFloat() / media.height.toFloat()
        } else 16f / 9f
    }
    val archivedPath by produceState<String?>(initialValue = null, media.localArchiveSha, mediaStore) {
        val sha = media.localArchiveSha
        value = if (sha != null && mediaStore != null) {
            mediaStore.pathFor(sha)?.takeIf { File(it).exists() }
        } else null
    }
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 3f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val thumbBytes = media.minithumbBytes
        if (archivedPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(archivedPath!!))
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
            )
        } else if (thumbBytes != null) {
            // Decoded each composition — acceptable: minithumbs are tiny (≤2 KB).
            val bitmap = remember(thumbBytes) {
                runCatching {
                    android.graphics.BitmapFactory.decodeByteArray(
                        thumbBytes, 0, thumbBytes.size,
                    )?.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { renderEffect = BlurEffect(8f, 8f, TileMode.Decal) },
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = stringResource(R.string.revision_media_low_res_overlay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.revision_media_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.revision_media_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
