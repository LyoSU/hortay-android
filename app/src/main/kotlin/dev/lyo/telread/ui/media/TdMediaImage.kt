package dev.lyo.telread.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.lyo.telread.data.MediaState
import dev.lyo.telread.data.TdMedia
import java.io.File

/**
 * Renders a [TdMedia] backed by TDLib's file system.
 *
 *   1. Decodes the inline minithumbnail (if present) and shows it instantly.
 *   2. Triggers an idempotent [MediaCache.ensureDownloaded] for the file.
 *   3. Once the cache reports [MediaState.Ready], swaps in the full-resolution image
 *      via Coil with crossfade.
 *
 * For media without minithumbnail, falls back to a tonal placeholder + spinner.
 */
@Composable
fun TdMediaImage(
    media: TdMedia,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cache = LocalMediaCache.current
    val state by cache.observe(media.fileId).collectAsStateWithLifecycle()

    LaunchedEffect(media.fileId) { cache.ensureDownloaded(media.fileId) }

    val placeholder = remember(media.fileId, media.minithumbBytes) {
        media.minithumbBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (placeholder != null) {
            androidx.compose.foundation.Image(
                bitmap = placeholder.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (val s = state) {
            is MediaState.Ready -> AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(File(s.path))
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            is MediaState.Downloading -> if (placeholder == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = ProgressIndicatorDefaults.CircularStrokeWidth,
                    )
                }
            }
            is MediaState.Failed,
            MediaState.Idle -> Unit
        }
    }
}
