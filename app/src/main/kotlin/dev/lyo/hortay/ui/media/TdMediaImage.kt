package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Renders a [TdMedia] backed by TDLib's file system.
 *
 *   1. Decodes the inline minithumbnail (if present) via Coil — runs on Coil's IO
 *      dispatchers so the decode never blocks the Compose thread, even on the cold
 *      first frame of a fast scroll. The blurred minithumb is a Telegram-style
 *      placeholder that disappears under the real photo's crossfade.
 *   2. Triggers an idempotent [dev.lyo.hortay.data.MediaCache.ensure] for the file.
 *   3. Once the cache reports [MediaState.Ready], crossfades in the full-resolution
 *      image via Coil with the disk cache turned off — TDLib's own
 *      [tdlib-files] directory is already an authoritative on-disk store, and
 *      letting Coil duplicate it would double our storage footprint for every photo.
 *
 * If [TdMedia.fileId] is null (e.g. a forwarded GIF without a server-side thumbnail),
 * only the minithumb is shown — we never try to decode the playback file as an image.
 *
 * For avatar-style usage (small images that have a parent fallback like an initial letter),
 * pass `placeholderColor = null` and `showProgress = false` so this composable stays fully
 * transparent until the full-resolution image is ready, letting the parent's fallback show
 * through during loading or on failure.
 */
@Composable
fun TdMediaImage(
    media: TdMedia,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color? = MaterialTheme.colorScheme.surfaceContainerHigh,
    showProgress: Boolean = true,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
) {
    val cache = LocalMediaCache.current
    val context = LocalContext.current
    val fileId = media.fileId
    val remoteUrl = media.remoteUrl

    // Web-mode fast path: no TDLib fileId, just a remote URL. Hand straight to
    // Coil — same crossfade, same disk cache, same Compose contract — bypassing
    // the TDLib download orchestration that has nothing to do here. Lets the
    // existing PostCard/PostBody render web posts through one code path.
    if (fileId == null && remoteUrl != null) {
        val baseModifier = if (placeholderColor != null) modifier.background(placeholderColor) else modifier
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(remoteUrl)
                .crossfade(CROSSFADE_MS)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = baseModifier.fillMaxSize(),
        )
        return
    }

    val state by remember(fileId) {
        if (fileId != null) cache.observe(fileId) else MutableStateFlow(MediaState.Idle)
    }.collectAsStateWithLifecycle()

    // Skip starting the download while the host list is mid-scroll — see [LocalScrollGate].
    // When scroll settles, this LaunchedEffect re-runs with gateOpen=true and ensure fires.
    val gate = LocalScrollGate.current
    val gateOpen = gate.value
    LaunchedEffect(fileId, priority, gateOpen) {
        if (gateOpen) fileId?.let { cache.ensure(it, priority) }
    }

    DisposableEffect(fileId) {
        onDispose { fileId?.let(cache::cancelDeferred) }
    }

    // Loading overlay only paints after a 600 ms grace window — fast loads stay invisible
    // under the blurred minithumb. See [rememberDeferredLoading].
    val showLoadingOverlay = rememberDeferredLoading(state = state, key = fileId)

    val baseModifier = if (placeholderColor != null) {
        modifier.background(placeholderColor)
    } else {
        modifier
    }

    Box(modifier = baseModifier) {
        // Minithumb is a tiny inline JPEG (~150B). Coil decodes it off the main
        // thread and we Gaussian-blur the rendered output for the Telegram-style
        // "soft preview" look. The blur modifier is a GPU pass on API ≥ 31; on
        // 26-30 Compose silently no-ops the blur, and the bilinear up-scale of
        // the 40×40 minithumb still reads as a soft placeholder. Hidden once the
        // full image is Ready so Coil's crossfade isn't fighting a still-visible
        // placeholder underneath.
        val minithumb = media.minithumbBytes
        if (minithumb != null && state !is MediaState.Ready) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(minithumb)
                    // Minithumbs are deterministic from the bytes themselves; Coil's
                    // memory cache is happy to dedupe them, but disk caching them
                    // is wasteful — they ship inline with every UpdateNewMessage.
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(MINITHUMB_BLUR_RADIUS),
            )
        }
        when (val s = state) {
            is MediaState.Ready -> if (s.path.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(s.path))
                        // The file already lives in TDLib's filesDir — letting Coil
                        // copy it into its own disk cache doubles the footprint for
                        // every photo the user has ever scrolled past. Memory cache
                        // (decoded Bitmap) stays on; the cost saving is on disk.
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .crossfade(CROSSFADE_MS)
                        .listener(
                            onError = { _, _ ->
                                // TDLib's storage optimiser silently evicts cached files
                                // and never emits an UpdateFile for the deletion (per
                                // tdlib/td#3178). Coil failing to open the path is our
                                // signal that the slot is stale: invalidate it and the
                                // cache will re-issue DownloadFile on its own scope.
                                fileId?.let { cache.invalidate(it, priority) }
                            },
                        )
                        .build(),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is MediaState.Downloading -> if (showLoadingOverlay && showProgress && fileId != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MediaLoadingOverlay(
                        progress = s.progress,
                        downloadedBytes = s.downloadedBytes,
                        totalBytes = s.totalBytes,
                        onCancel = { cache.cancelExplicit(fileId) },
                    )
                }
            }
            is MediaState.Failed -> if (showProgress && fileId != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val coScope = rememberCoroutineScope()
                    MediaFailedOverlay(
                        onRetry = { coScope.launch { cache.retry(fileId, priority) } },
                    )
                }
            }
            MediaState.Idle -> Unit
        }
    }
}

private val MINITHUMB_BLUR_RADIUS = 20.dp
private const val CROSSFADE_MS = 220
