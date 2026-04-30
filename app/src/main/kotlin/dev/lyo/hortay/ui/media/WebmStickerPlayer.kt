package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Plays a Telegram WebM (VP9) sticker. Looped, muted, no controls — the typical "video
 * sticker" UX. Built on a per-instance [ExoPlayer] because video decoders are scarce
 * (most devices ship 2-4 VP9 decoders) and ExoPlayer manages decoder pooling internally.
 *
 * Lifecycle: paused on ON_PAUSE, resumed on ON_RESUME, fully released on dispose. The
 * lazy-list dispose is what stops the decoder when the sticker scrolls off-screen —
 * Compose tears the composable down and the [DisposableEffect] frees the ExoPlayer.
 *
 * The static thumbnail underlays the player while the WebM is downloading or while the
 * decoder is still initialising; ExoPlayer renders on top once the first frame lands.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun WebmStickerPlayer(
    fileId: Int?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
) {
    val cache = LocalMediaCache.current
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val gate = LocalScrollGate.current
    val gateOpen = gate.value
    LaunchedEffect(fileId, priority, gateOpen) {
        if (gateOpen) fileId?.let { cache.ensure(it, priority) }
    }
    // Mirror TdMediaImage's dispose-cancels-download contract — otherwise a sticker that
    // scrolled off-screen keeps holding a TDLib download slot until it finishes,
    // queueing behind currently-visible media. Bytes survive on disk so a re-mount
    // resumes from the saved offset.
    DisposableEffect(fileId) {
        onDispose { fileId?.let(cache::cancelDeferred) }
    }
    val mediaState by remember(fileId) {
        if (fileId != null) cache.observe(fileId)
        else MutableStateFlow(MediaState.Idle)
    }.collectAsStateWithLifecycle()

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
    // ExoPlayer's setMediaItem/prepare cycle. fileId is captured as a key so swapping
    // to a different sticker instance triggers a fresh prepare even if the new file's
    // path coincidentally matches the previous one.
    val readyPath = (mediaState as? MediaState.Ready)?.path
    LaunchedEffect(readyPath, fileId) {
        val path = readyPath ?: return@LaunchedEffect
        if (path.isEmpty()) return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri("file://${path}"))
        exoPlayer.prepare()
    }

    DisposableEffect(exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pool.release(exoPlayer, muted = true)
        }
    }

    Box(modifier = modifier) {
        if (thumb != null && mediaState !is MediaState.Ready) {
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
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Sticker boxes are square; keeping CONTENT_RESIZE_MODE_FIT preserves
                    // the source aspect ratio inside the surface — Telegram's video
                    // stickers are almost always already square but a few outliers exist.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
            },
        )
    }
}
