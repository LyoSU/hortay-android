package dev.lyo.hortay.ui.media

import android.view.TextureView
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TdMedia

/**
 * Plays a Telegram WebM (VP9) sticker. Looped, muted, no controls — the typical "video
 * sticker" UX. Built on a per-instance [ExoPlayer] because video decoders are scarce
 * (most devices ship 2-4 VP9 decoders) and ExoPlayer manages decoder pooling internally.
 *
 * Renders into a [TextureView] (NOT `PlayerView`/SurfaceView). Telegram's WebM stickers
 * carry a real alpha channel — transparent backgrounds — and SurfaceView is a hardware
 * overlay that renders in its own window layer behind the app. Transparent regions of
 * the video, on a SurfaceView, "punch through" the app window and reveal whatever the
 * OS shows underneath, which on a dark theme reads as a black square. TextureView
 * renders into the app's normal GL-backed canvas, so alpha composites correctly with
 * the surrounding content. `isOpaque = false` is the bit that flips that behaviour on;
 * leaving it at the TextureView default (true) would still paint a solid background
 * because of the same performance optimisation that makes TextureView opaque by default.
 *
 * Lifecycle: paused on ON_PAUSE, resumed on ON_RESUME, fully released on dispose. The
 * lazy-list dispose is what stops the decoder when the sticker scrolls off-screen —
 * Compose tears the composable down and the [DisposableEffect] frees the ExoPlayer.
 *
 * The static thumbnail underlays the player until the FIRST FRAME has actually been
 * rendered to the texture. Gating on `mediaState is Ready` (the older condition) is
 * insufficient because (a) guest mode streams directly from a URL with no MediaCache
 * state at all, and (b) even in TDLib mode `Ready` fires when bytes land, well before
 * ExoPlayer has decoded the first video frame. `Player.Listener.onRenderedFirstFrame`
 * is the precise boundary: a true frame is on-texture and any thumb hide afterwards
 * is safe.
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
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRemote = fileId == null && remoteUrl != null

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    val binding = rememberMediaBinding(fileId = fileId, priority = priority, isRemote = isRemote)

    // Acquire from the shared pool. WebM stickers are inherently silent, so we
    // request the muted variant — no audio renderer is built, AudioTrack is never
    // allocated, and the AudioMix system wakelock is never taken.
    val exoPlayer = remember {
        pool.acquire(muted = true).apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
    }

    // Key on the Ready path only so Downloading-burst progress updates don't churn
    // ExoPlayer's setMediaItem/prepare cycle. fileId / remoteUrl are captured so
    // swapping to a different sticker instance triggers a fresh prepare even if
    // the new file's path coincidentally matches the previous one.
    val readyPath = binding.readyPath
    LaunchedEffect(readyPath, fileId, remoteUrl, isRemote) {
        val uri: String = when {
            // K2 smart-casts remoteUrl to String via the isRemote val above
            // (`fileId == null && remoteUrl != null`).
            isRemote -> remoteUrl
            else -> {
                val path = readyPath ?: return@LaunchedEffect
                "file://$path"
            }
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    // Track when ExoPlayer has put the first decoded frame onto the TextureView.
    // Kept on a per-(fileId, remoteUrl) key so swapping to a different sticker
    // instance correctly resets the gate: the new texture is empty until its
    // own first frame lands.
    var firstFrameRendered by remember(fileId, remoteUrl) { mutableStateOf(false) }
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
        // Thumb stays under the texture until ExoPlayer has put a real frame on
        // it. Hiding earlier (e.g. on `Ready` bytes-on-disk) leaves a window
        // where the TextureView is transparent and exposes the surface behind.
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
