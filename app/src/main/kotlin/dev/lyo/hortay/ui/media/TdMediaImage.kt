package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch
import java.io.File

/**
 * Renders a [TdMedia] backed by TDLib's file system.
 *
 *   1. Decodes the inline minithumbnail (if present) via Coil — runs on Coil's IO
 *      dispatchers so the decode never blocks the Compose thread, even on the cold
 *      first frame of a fast scroll. The blurred minithumb is a Telegram-style
 *      placeholder that the real photo cross-dissolves over.
 *   2. Triggers an idempotent [dev.lyo.hortay.data.MediaCache.ensure] for the file.
 *   3. Once the cache reports [MediaState.Ready], hands the on-disk path to Coil with
 *      the disk cache turned off — TDLib's own [tdlib-files] directory is already an
 *      authoritative on-disk store, and letting Coil duplicate it would double our
 *      storage footprint for every photo.
 *
 * **Reveal = first decoded pixel, not bytes-on-disk.** The photo fades in via the shared
 * [MediaReveal] primitive, driven by Coil's `onSuccess` (the frame Coil has actually
 * decoded the file and is ready to paint), NOT by [MediaState.Ready] (bytes finished
 * downloading). Keying the reveal on Ready left a window where the file was local but
 * undecoded; the old code papered over it with Coil's own `.crossfade()` against the
 * lingering minithumb, but that double-faded and could still flash [placeholderColor]
 * through a half-decoded image. Driving the reveal off `onSuccess` lets the blurred
 * minithumb hold solid right up to the first painted pixel — no grey "блимок".
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
        // Guest-mode stale-media handler (null in TDLib mode). On an expired-CDN-URL
        // error we report the URL so WebFeedSource can re-fetch the owning channel —
        // t.me/s/ CDN tokens live only 1–4 h (see ARCHITECTURE "Web-mode media TTL").
        val onStaleMedia = LocalWebStaleMedia.current
        // Memoise the ImageRequest. Without `remember`, every recomposition
        // (parent emit, sibling state change, scroll-driven layout pass) builds
        // a fresh ImageRequest reference. Coil de-dupes by URL internally but
        // each new instance still goes through its dispatcher checks; on a
        // 30-card viewport that's the difference between idle and ~1 ms of
        // request churn per frame.
        val request = remember(remoteUrl, context, onStaleMedia) {
            ImageRequest.Builder(context)
                .data(remoteUrl)
                .crossfade(CROSSFADE_MS)
                .apply {
                    if (onStaleMedia != null) {
                        listener(
                            onError = { _, result ->
                                val code = (result.throwable as? HttpException)?.response?.code
                                if (code == 401 || code == 403 || code == 410) {
                                    onStaleMedia(remoteUrl)
                                }
                            },
                        )
                    }
                }
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

    // Deleted-post tombstone: the binding is observe-only (no download will ever
    // complete), so the progress / retry affordances would lie. We keep the Ready
    // image and the blurred inline minithumb — which is a free, already-in-memory
    // preview of what the media was — and drop the spinner/retry chrome. See
    // [LocalMediaPassive].
    val passive = LocalMediaPassive.current

    // Loading overlay only paints after a 600 ms grace window — fast loads stay invisible
    // under the blurred minithumb. See [rememberDeferredLoading].
    val showLoadingOverlay = rememberDeferredLoading(state = state, key = fileId)

    val baseModifier = if (placeholderColor != null) {
        modifier.background(placeholderColor)
    } else {
        modifier
    }

    // `revealed` = Coil has DECODED + is ready to paint the file image, not just
    // MediaState.Ready (bytes on disk). Keyed on (fileId, ready path) so an in-place file
    // change (in-channel media edit, album swipe reusing the slot) resets the reveal and
    // re-shows the minithumb under the new file's fade.
    val readyPath = (state as? MediaState.Ready)?.path?.takeIf { it.isNotEmpty() }
    var photoPainted by remember(fileId, readyPath) { mutableStateOf(false) }

    Box(modifier = baseModifier) {
        // Blurred minithumb placeholder + the full-resolution photo cross-dissolving in on
        // top via [MediaReveal]. The minithumb is a tiny inline JPEG (~150 B); Coil decodes
        // it off the main thread and we Gaussian-blur the rendered output for the Telegram-
        // style "soft preview" look (a GPU pass on API ≥ 31; a no-op on 26-30 where the
        // bilinear up-scale of the 40×40 thumb still reads as soft). MediaReveal keeps the
        // minithumb at full alpha through the photo fade and for [MEDIA_REVEAL_LINGER_MS]
        // after the first painted pixel, then drops it — so the opaque thumb covers the
        // whole fade and [placeholderColor] never bleeds through a half-decoded image.
        val minithumb = media.minithumbBytes
        MediaReveal(
            revealed = photoPainted,
            key = fileId,
            modifier = Modifier.fillMaxSize(),
            placeholder = {
                if (minithumb != null) {
                    // Memoise: minithumbs are stable per-post; rebuilding the request on
                    // every recomposition would churn ~150 B requests through Coil's queue
                    // once per visible card per frame during scroll.
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
                        modifier = Modifier.fillMaxSize().blur(MINITHUMB_BLUR_RADIUS),
                    )
                }
            },
            content = {
                if (readyPath != null) {
                    // No Coil .crossfade(): MediaReveal owns the fade now (driven by
                    // onSuccess below). Memoise on the Ready path string so rebuilding the
                    // request + re-attaching listeners doesn't churn Coil's queue.
                    val readyRequest = remember(readyPath, fileId, context) {
                        ImageRequest.Builder(context)
                            .data(File(readyPath))
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .listener(
                                onSuccess = { _, _ -> photoPainted = true },
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
            },
        )
        // Loading / failed overlays — layered on top of the dissolve. Suppressed for a
        // passive (deleted-post tombstone) slot: the download will never complete, so the
        // spinner/retry chrome would lie. See [LocalMediaPassive].
        when (val s = state) {
            is MediaState.Downloading -> if (showLoadingOverlay && showProgress && fileId != null && !passive) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MediaLoadingOverlay(
                        progress = s.progress,
                        downloadedBytes = s.downloadedBytes,
                        totalBytes = s.totalBytes,
                        onCancel = { binding.cancelExplicit() },
                    )
                }
            }
            is MediaState.Failed -> if (showProgress && fileId != null && !passive) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val coScope = rememberCoroutineScope()
                    MediaFailedOverlay(
                        onRetry = { coScope.launch { binding.retry(priority) } },
                    )
                }
            }
            MediaState.Idle, is MediaState.Ready -> Unit
        }
        // Passive tombstone with neither an on-disk file nor an inline minithumb to
        // preview (rare — TDLib almost always ships a minithumbnail): a decorative
        // "image unavailable" glyph reads better than a bare placeholder rectangle.
        // contentDescription is null because the PostCard's DeletedBadge already
        // announces the card's deleted state to TalkBack.
        if (passive && state !is MediaState.Ready && minithumb == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Symbol(name = "hide_image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Guest-mode-only hook: reports a remote media URL that failed to load with an
 * auth/expiry HTTP code (401/403/410) so [dev.lyo.hortay.data.web.WebFeedSource]
 * can re-fetch the owning channel and pick up fresh CDN tokens. Null in TDLib
 * mode (fileId-backed media never hits this path); provided by
 * [dev.lyo.hortay.ui.web.WebModeScaffold] in guest mode. Static because the value
 * (a stable lambda over the process-singleton feed source) never changes for the
 * lifetime of a guest session — no need to invalidate readers on a new provider.
 */
val LocalWebStaleMedia = staticCompositionLocalOf<((String) -> Unit)?> { null }

private val MINITHUMB_BLUR_RADIUS = 20.dp

// Web-mode (remote URL) photos still use Coil's own crossfade — they have no minithumb
// underlay and no MediaCache reveal signal, so Coil's fade is the only transition there.
private const val CROSSFADE_MS = 220
