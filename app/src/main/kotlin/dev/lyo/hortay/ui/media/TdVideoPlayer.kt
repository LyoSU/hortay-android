package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

/**
 * Plays a TDLib-managed video. Asks [dev.lyo.hortay.data.MediaCache] to download the
 * file (deduplicated and priority-aware) and once it lands on disk, hands the path
 * to a single [ExoPlayer]. While the download is in flight a [MediaIndeterminateIndicator]
 * is shown over a transparent [PlayerView]; underlying composables (typically the
 * blurred poster from [TdMediaImage]) remain visible.
 *
 * Lifecycle:
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
 *   • [autoLoop] = true → silent looping (Telegram "GIF" animations).
 *
 * Quality switch: the caller changes [fileId]. [MediaCache] is asked to download
 * the new file; once it's Ready, ExoPlayer's MediaItem is swapped while preserving
 * the current playback position so the user resumes mid-frame instead of restarting.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TdVideoPlayer(
    fileId: Int,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    autoLoop: Boolean = false,
    showControls: Boolean = true,
    muted: Boolean = false,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
) {
    val cache = LocalMediaCache.current
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coScope = rememberCoroutineScope()

    LaunchedEffect(fileId, priority) { cache.ensure(fileId, priority) }
    val mediaState by cache.observe(fileId).collectAsStateWithLifecycle()

    // Acquire from the shared pool. Pooled instances arrive in IDLE state with empty
    // playlist (see ExoPlayerPool.release); the apply-block here re-applies the
    // per-call attributes that the pool reset on the previous release. Mute regime
    // is fixed at acquire time — muted players are built without an audio renderer
    // entirely (no AudioTrack, no AudioMix wakelock).
    val exoPlayer = remember {
        pool.acquire(muted = muted).apply {
            playWhenReady = autoPlay
            repeatMode = if (autoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
        }
    }

    // Swap the source when the file becomes Ready, or when the caller picks a
    // different quality (different fileId → new MediaState.Ready with a new path).
    // We preserve playback position across the swap so a quality flip resumes
    // mid-frame instead of restarting from zero.
    LaunchedEffect(mediaState, fileId) {
        val ready = mediaState as? MediaState.Ready ?: return@LaunchedEffect
        if (ready.path.isEmpty()) return@LaunchedEffect
        val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(MediaItem.fromUri("file://${ready.path}"))
        exoPlayer.prepare()
        if (resumeAt > 0L) exoPlayer.seekTo(resumeAt)
        exoPlayer.playWhenReady = wasPlaying
    }

    DisposableEffect(exoPlayer) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (autoPlay) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            // Hand the player back to the pool instead of releasing — saves the
            // MediaCodec/decoder allocation cost on the next viewport entry.
            pool.release(exoPlayer, muted = muted)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = showControls
                    // We render our own MediaProgressIndicator/MediaLoadingOverlay over
                    // the player; PlayerView's built-in spinner would stack on top of
                    // ours and read as "two crutilki" during the file:// download window.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
                view.useController = showControls
            },
        )
        when (val s = mediaState) {
            is MediaState.Downloading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaLoadingOverlay(
                    progress = s.progress,
                    downloadedBytes = s.downloadedBytes,
                    totalBytes = s.totalBytes,
                    onCancel = { cache.cancelExplicit(fileId) },
                )
            }
            is MediaState.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaFailedOverlay(
                    onRetry = { coScope.launch { cache.retry(fileId, priority) } },
                )
            }
            MediaState.Idle -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
            is MediaState.Ready -> Unit
        }
    }
}
