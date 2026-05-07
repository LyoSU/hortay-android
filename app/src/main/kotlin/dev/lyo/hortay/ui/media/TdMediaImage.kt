package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
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
    val context = LocalContext.current
    val fileId = media.fileId
    val remoteUrl = media.remoteUrl

    // Web-mode fast path: no TDLib fileId, just a remote URL. Hand straight to
    // Coil — same crossfade, same disk cache, same Compose contract — bypassing
    // the TDLib download orchestration that has nothing to do here. Lets the
    // existing PostCard/PostBody render web posts through one code path.
    if (fileId == null && remoteUrl != null) {
        val baseModifier = if (placeholderColor != null) modifier.background(placeholderColor) else modifier
        // Memoise the ImageRequest. Without `remember`, every recomposition
        // (parent emit, sibling state change, scroll-driven layout pass) builds
        // a fresh ImageRequest reference. Coil de-dupes by URL internally but
        // each new instance still goes through its dispatcher checks; on a
        // 30-card viewport that's the difference between idle and ~1 ms of
        // request churn per frame.
        val request = remember(remoteUrl, context) {
            ImageRequest.Builder(context)
                .data(remoteUrl)
                .crossfade(CROSSFADE_MS)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = baseModifier.fillMaxSize(),
        )
        return
    }

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    // The hook handles scroll-gate gating, cancel-on-dispose, and the four-step
    // contract that every TDLib renderer must honour, in one place.
    val binding = rememberMediaBinding(fileId = fileId, priority = priority)
    val state = binding.state

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
        // the 40×40 minithumb still reads as a soft placeholder.
        //
        // **Stays composed even after [MediaState.Ready] lands.** An earlier version
        // gated this branch on `state !is Ready`, which yanked the minithumb the
        // instant the [MediaCache] reducer flipped Ready — but that's our
        // *download-completed* signal, NOT our *Coil-rendered-the-pixels* signal.
        // Coil's `.crossfade(220)` on the Ready-path AsyncImage below fades the
        // file image in from alpha 0 over 220 ms; if the minithumb is gone, that
        // entire window paints [placeholderColor] (typically `surfaceContainerHigh`),
        // producing a sharp grey blink between the soft minithumb and the full
        // photo — the symptom users describe as "блимок" / "наче не дуже
        // обробляє завантажене". Keeping the minithumb mounted under the
        // crossfading file image lets it bleed through during the fade and get
        // covered naturally as the file image reaches full opacity. Render
        // cost is negligible: the minithumb bitmap is in Coil's memory cache
        // after first decode, so what stays composed is one bitmap blit + one
        // RenderEffect blur per visible card per frame — under the noise of the
        // surrounding LazyColumn layout pass.
        val minithumb = media.minithumbBytes
        if (minithumb != null) {
            // Memoise: minithumbs are stable per-post; rebuilding the request on
            // every recomposition would churn ~150 B requests through Coil's
            // queue once per visible card per frame during scroll.
            val minithumbRequest = remember(minithumb, context) {
                ImageRequest.Builder(context)
                    .data(minithumb)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
            }
            AsyncImage(
                model = minithumbRequest,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(MINITHUMB_BLUR_RADIUS),
            )
        }
        when (val s = state) {
            is MediaState.Ready -> if (s.path.isNotEmpty()) {
                // Memoise on the Ready path string: rebuilding ImageRequest +
                // re-attaching the listener every recomp churns Coil's queue.
                val readyRequest = remember(s.path, fileId, context) {
                    ImageRequest.Builder(context)
                        .data(File(s.path))
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .crossfade(CROSSFADE_MS)
                        .listener(
                            onError = { _, _ ->
                                // TDLib's storage optimiser silently evicts cached files
                                // and never emits an UpdateFile for the deletion (per
                                // tdlib/td#3178). Coil failing to open the path is our
                                // signal that the slot is stale: invalidate it and the
                                // cache will re-issue DownloadFile on its own scope.
                                binding.invalidate(priority)
                            },
                        )
                        .build()
                }
                AsyncImage(
                    model = readyRequest,
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
                        onCancel = { binding.cancelExplicit() },
                    )
                }
            }
            is MediaState.Failed -> if (showProgress && fileId != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val coScope = rememberCoroutineScope()
                    MediaFailedOverlay(
                        onRetry = { coScope.launch { binding.retry(priority) } },
                    )
                }
            }
            MediaState.Idle -> Unit
        }
    }
}

private val MINITHUMB_BLUR_RADIUS = 20.dp
private const val CROSSFADE_MS = 220
