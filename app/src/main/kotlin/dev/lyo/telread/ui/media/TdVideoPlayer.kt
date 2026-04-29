package dev.lyo.telread.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.lyo.telread.data.MediaState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Plays a TDLib-managed video (or MP4-backed animation) once it is downloaded.
 *
 * Behaviour:
 *   • Triggers a download for [fileId] via [LocalMediaCache].
 *   • Once [MediaState.Ready], hands the local file path to a single [ExoPlayer].
 *   • [autoLoop] = true → silent, looping playback (Telegram "GIF" animations).
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
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
    priority: dev.lyo.telread.data.DownloadPriority = dev.lyo.telread.data.DownloadPriority.VisibleMedia,
) {
    val cache = LocalMediaCache.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Caller picks priority. Timeline-embedded GIFs default to VisibleMedia so they
    // don't starve avatars or compete with the active fullscreen viewer; the viewer
    // passes Foreground.
    LaunchedEffect(fileId, priority) { cache.ensure(fileId, priority) }
    val mediaState by cache.observe(fileId).collectAsStateWithLifecycle()

    val exoPlayer = remember(fileId) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = autoPlay
            repeatMode = if (autoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
        }
    }

    DisposableEffect(fileId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (autoPlay) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(mediaState) {
        val ready = mediaState as? MediaState.Ready ?: return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri("file://${ready.path}"))
        exoPlayer.prepare()
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = showControls
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(MaterialThemeBlackArgb)
                }
            },
        )
    }
}

private const val MaterialThemeBlackArgb: Int = 0xFF000000.toInt()
