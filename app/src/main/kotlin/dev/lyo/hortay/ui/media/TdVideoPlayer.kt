package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.lyo.hortay.data.LocalTdSender
import dev.lyo.hortay.data.TdLibDataSource

/**
 * Plays a TDLib-managed video by streaming from a partial download. Backed by
 * [TdLibDataSource], which lets ExoPlayer start playback as soon as a usable
 * prefix lands on disk — typically 1-3 seconds in.
 *
 * Single-player design: the underlying [ExoPlayer] is constructed once per
 * composable instance and kept across [fileId] changes. When the user switches
 * quality (which changes the file id), we capture the current position and
 * resume there on the new file — same behaviour the Telegram clients have, and
 * dramatically nicer than restarting a 5-minute video from frame 0 because the
 * user wanted to bump it from 480p to 720p.
 *
 * Lifecycle:
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
 *   • [autoLoop] = true → silent, looping playback (Telegram "GIF" animations).
 *
 * The [PlayerView] uses a transparent background so any composable underneath
 * (typically the Telegram-style blurred poster from [TdMediaImage]) shows
 * through during buffering.
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
) {
    val td = LocalTdSender.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isBuffering by remember { mutableStateOf(true) }

    val mediaSourceFactory = remember(td) {
        ProgressiveMediaSource.Factory(TdLibDataSource.Factory(td))
    }

    // Single ExoPlayer for the lifetime of this composable. NOT keyed on fileId — a
    // quality switch (which IS a fileId change) must preserve playback position; that's
    // achieved by re-using the same player and only swapping the MediaItem inside the
    // LaunchedEffect below. Keying on the factory is enough — a different TdSender
    // would mean a different DataSource pipeline, which warrants a fresh player.
    val exoPlayer = remember(mediaSourceFactory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = autoPlay
                repeatMode = if (autoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                volume = if (muted) 0f else 1f
            }
    }

    // Swap the source when fileId changes. We capture the current position (and play
    // intent) before replacing the MediaItem so a quality switch resumes from the
    // exact frame the user was on instead of restarting at zero.
    LaunchedEffect(fileId) {
        val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(MediaItem.fromUri("tdlib://$fileId"))
        exoPlayer.prepare()
        if (resumeAt > 0L) exoPlayer.seekTo(resumeAt)
        exoPlayer.playWhenReady = wasPlaying
    }

    DisposableEffect(exoPlayer) {
        val playbackListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (autoPlay) exoPlayer.play()
                else -> Unit
            }
        }
        exoPlayer.addListener(playbackListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            exoPlayer.removeListener(playbackListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = showControls
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            // Re-attach the player to the view if the player instance survives across
            // a recompose where AndroidView's factory wouldn't run again. Same player
            // instance, but PlayerView caches it and we want to be defensive.
            update = { view ->
                if (view.player !== exoPlayer) view.player = exoPlayer
                view.useController = showControls
            },
        )
        if (isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
        }
    }
}
