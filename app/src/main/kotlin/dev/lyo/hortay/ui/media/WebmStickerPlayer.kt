package dev.lyo.hortay.ui.media

import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TdMedia

/**
 * Plays a Telegram WebM (VP9+alpha) sticker. Looped, muted, no controls — the typical
 * "video sticker" UX.
 *
 * **TDLib mode** (local file): rendered via [WebmAlphaImage] — ffmpeg software decode
 * with a true VP9+alpha sidecar via Matroska BlockAdditional. Frames land as
 * [androidx.compose.ui.graphics.ImageBitmap] drawn on a Compose [Canvas] with native
 * srcOver compositing, so transparent sticker regions blend correctly with any surface.
 *
 * **Guest mode** (URL): ExoPlayer + [TextureView] path retained unchanged. [WebmAlphaImage]
 * requires a local file path and cannot decode a URL; guest-mode alpha rendering is an
 * explicit follow-up.
 *
 * The static thumbnail underlays the renderer until the first frame arrives.
 * [onFirstFrame] (from [WebmAlphaImage]) / `Player.Listener.onRenderedFirstFrame` (guest)
 * is the precise boundary: a real frame is on-canvas and any thumb hide afterwards is safe.
 *
 * Guest-mode looping: ExoPlayer `REPEAT_MODE_ONE` is unsafe for short Telegram WebM
 * stickers in media3 1.10 — re-prepare reuses the tail of the previous read and the
 * second+ cycles replay only the last fragment. Manually seeking to 0 on STATE_ENDED
 * sidesteps the internal path (same fix as before; the TDLib path no longer has this
 * problem because [WebmAlphaImage] loops by frame-index arithmetic on the decoded array).
 */
@Composable
fun WebmStickerPlayer(
    fileId: Int?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    /**
     * Web-mode WebM URL. When [fileId] is null and [remoteUrl] is set, ExoPlayer
     * streams the URL directly via its built-in HTTP DataSource — no
     * [dev.lyo.hortay.data.MediaCache] interaction. Same path that
     * [TdVideoPlayer] uses for guest-mode video posts, kept consistent so a
     * future single-source-of-truth refactor merges easily.
     */
    remoteUrl: String? = null,
) {
    val isRemote = fileId == null && remoteUrl != null

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    val binding = rememberMediaBinding(fileId = fileId, priority = priority, isRemote = isRemote)

    if (isRemote) {
        // ── Guest mode: ExoPlayer + TextureView ──────────────────────────────
        // WebmAlphaImage requires a local file path; URL-only WebM alpha is a follow-up.
        // The full ExoPlayer path is preserved verbatim here.
        GuestModeWebmPlayer(
            remoteUrl = remoteUrl,
            thumb = thumb,
            contentDescription = contentDescription,
            modifier = modifier,
            priority = priority,
        )
    } else {
        // ── TDLib mode: WebmAlphaImage (ffmpeg VP9+alpha software decode) ────
        // sizePx: the sticker box is always constrained to STICKER_MAX_SIDE on its
        // longer axis (see stickerBoxModifier). We read the actual laid-out size from
        // BoxWithConstraints so the cache key reflects the real pixel budget and
        // non-square stickers (shorter side < maxSide) don't over-allocate.
        BoxWithConstraints(modifier = modifier) {
            val density = LocalDensity.current
            val sizePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            var firstFrameRendered by remember(fileId) { mutableStateOf(false) }

            // Thumb stays under the canvas until WebmAlphaImage reports onFirstFrame.
            if (thumb != null && !firstFrameRendered) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
            WebmAlphaImage(
                key = fileId.toString(),
                path = binding.readyPath,
                sizePx = sizePx,
                modifier = Modifier.fillMaxSize(),
                animate = true,
                onFirstFrame = { firstFrameRendered = true },
            )
        }
    }
}

/** ExoPlayer + TextureView renderer for guest-mode (URL) WebM stickers. Kept separate so
 *  [WebmStickerPlayer] can use [BoxWithConstraints] for the TDLib branch without sharing
 *  composition scope with all the ExoPlayer [DisposableEffect] / [LaunchedEffect] machinery. */
@Composable
private fun GuestModeWebmPlayer(
    remoteUrl: String?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier,
    priority: DownloadPriority,
) {
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember {
        pool.acquire(muted = true).apply {
            playWhenReady = true
            // Loop is driven manually below — see class KDoc for why REPEAT_MODE_ONE
            // is unsafe for short Telegram WebM stickers in media3 1.10.
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
        }
    }

    LaunchedEffect(remoteUrl) {
        val url = remoteUrl ?: return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
    }

    var firstFrameRendered by remember(remoteUrl) { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> Unit
            }
        }
        val playerListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        exoPlayer.addListener(playerListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(playerListener)
            pool.release(exoPlayer, muted = true)
        }
    }

    Box(modifier = modifier) {
        // Thumb stays under the texture until ExoPlayer has put a real frame on it.
        if (thumb != null && !firstFrameRendered) {
            TdMediaImage(
                media = thumb,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                placeholderColor = null,
                showProgress = false,
                priority = priority,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    // CRITICAL for alpha-channel WebM stickers. Without this, the
                    // TextureView paints its background opaque (a perf default) and
                    // transparent video pixels render over a solid colour rather
                    // than blending with the post card behind it.
                    isOpaque = false
                }
            },
            update = { view ->
                // Re-attach guards against ExoPlayer being recycled across composables
                // — the pool can hand us a player that was previously bound to a
                // different texture; we always re-bind to ours on update.
                exoPlayer.setVideoTextureView(view)
            },
            // When the AndroidView leaves composition, detach the texture before
            // ExoPlayer is released by the pool. Otherwise the player holds a stale
            // reference to a TextureView whose SurfaceTexture has been destroyed,
            // and the next bind from the pool throws.
            onRelease = { exoPlayer.clearVideoTextureView(it) },
        )
    }
}
