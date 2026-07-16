package dev.lyo.hortay.ui.timeline

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalExoPlayerPool
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.rememberMediaBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Telegram-parity round video message (`messageVideoNote`).
 *
 * Owns its own [androidx.media3.exoplayer.ExoPlayer] instance so it can drive
 * a Compose-side chrome:
 *
 *   • Thin **progress ring** around the perimeter, swept clockwise from
 *     12 o'clock, polled at 100 ms while playing. Lives on the inside of the
 *     clip via a small [RING_INSET] gap so the rounded stroke doesn't get
 *     half-clipped on the circle's edge.
 *   • Live **remaining-time chip** (`-0:08`) — Telegram-Android's idiom,
 *     more useful than total duration while a clip plays. Freezes when paused.
 *   • Single tap on the bubble → **toggle pause / play**. On pause the
 *     underlying frame stays visible (TextureView keeps the last decoded
 *     frame) so the bubble doesn't go black; a centred play glyph fades in
 *     so the resume affordance is obvious.
 *   • Independent **mute / unmute chip** at the top-trailing corner — its
 *     clickable is nested inside the bubble's clickable so the chip's tap
 *     consumes before the bubble's pause toggle fires (Compose pointer
 *     hit-testing dispatches to the innermost interactive node first).
 *
 * Mute toggle is the awkward bit: the pool acquires *muted* players without
 * an audio renderer at all (saves the AudioTrack allocation and the audio-
 * focus wakelock for the common silent-feed case), so a runtime
 * `setVolume(1f)` on the muted instance would be a no-op. `key(muted)` is
 * the cleanest way to drop the muted slot back to the pool and acquire the
 * unmuted one — both regimes stay warm in the pool so the toggle costs
 * about the same as a normal scroll-into-view acquisition. Position survives
 * the swap via [VideoNoteSharedState.savedPositionMs] so the audio toggle
 * is perceptually seamless.
 *
 * Caller is responsible for the cache-ready gate: this composable assumes
 * the playback file is already on disk (the call site checks `isCachedReady`).
 * The static-poster fallback path lives in [PostBody]'s `VideoNoteBlock`.
 */
@Composable
internal fun VideoNotePlayerBubble(
    content: PostContent.VideoNote,
    fileId: Int,
) {
    // State that survives the muted-toggle pool swap.
    val sharedState = remember(fileId) { VideoNoteSharedState() }
    var muted by remember(fileId) { mutableStateOf(true) }
    var playing by remember(fileId) { mutableStateOf(true) }

    val a11y = stringResource(R.string.content_description_video_note)
    Box(
        modifier = Modifier
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .size(VIDEO_NOTE_DIAMETER)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // Bubble-wide tap toggles pause / play. The mute chip below
            // nests its own clickable so a tap on the chip never reaches
            // this handler (innermost interactive node wins).
            .clickable(
                role = Role.Button,
                onClick = { playing = !playing },
            )
            .semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        // Poster persists behind the player — TextureView is transparent
        // until the decoder writes its first frame, so without this the
        // bubble would flash the surface colour on mount. Once playback
        // starts the poster is fully covered.
        content.thumb?.let {
            TdMediaImage(
                media = it,
                contentDescription = stringResource(R.string.media_desc_video_note),
                showProgress = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
        key(muted) {
            VideoNoteRenderer(
                fileId = fileId,
                muted = muted,
                playing = playing,
                sharedState = sharedState,
            )
        }
        VideoNoteChrome(
            content = content,
            playing = playing,
            muted = muted,
            sharedState = sharedState,
            onToggleMute = { muted = !muted },
        )
    }
}

/**
 * Inner renderer — mounts the [androidx.media3.exoplayer.ExoPlayer], the
 * [TextureView], and reports position / duration up to [VideoNoteChrome]
 * via [sharedState].
 *
 * Lives inside `key(muted)` because the muted regime determines which pool
 * slot we acquire (muted ones are built without an AudioTrack — see class
 * KDoc on [VideoNotePlayerBubble]). When `muted` flips, this whole subtree
 * is disposed (the old player rides back into the pool via
 * [DisposableEffect]'s onDispose) and a fresh one mounts with the new regime.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoNoteRenderer(
    fileId: Int,
    muted: Boolean,
    playing: Boolean,
    sharedState: VideoNoteSharedState,
) {
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val binding = rememberMediaBinding(
        fileId = fileId,
        priority = DownloadPriority.VisibleMedia,
    )
    val readyPath = binding.readyPath

    val exoPlayer = remember {
        pool.acquire(muted = muted).apply {
            playWhenReady = playing
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (muted) 0f else 1f
        }
    }

    LaunchedEffect(playing) { exoPlayer.playWhenReady = playing }

    LaunchedEffect(readyPath) {
        if (readyPath.isNullOrEmpty()) return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri("file://$readyPath"))
        exoPlayer.prepare()
        // Seek to where the previous (muted / unmuted) regime left off so
        // the user doesn't see a restart when they toggle audio mid-clip.
        if (sharedState.savedPositionMs > 0L) exoPlayer.seekTo(sharedState.savedPositionMs)
        exoPlayer.playWhenReady = playing
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    sharedState.durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (playing) exoPlayer.play()
                else -> Unit
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            // Preserve playhead for the next regime so the audio toggle is
            // perceptually seamless. The reader (next mount of this
            // composable) seeks to this value after [prepare].
            sharedState.savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            exoPlayer.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            pool.release(exoPlayer, muted = muted)
        }
    }

    // Position polling — 100 ms while playing keeps the ring smooth at
    // human-perceivable rates; 500 ms while paused (the dispatcher doesn't
    // need to wake 10× per second to read the same number).
    val positionMs by produceState(0L, exoPlayer) {
        while (isActive) {
            value = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(if (exoPlayer.isPlaying) 100L else 500L)
        }
    }
    LaunchedEffect(positionMs) {
        sharedState.positionMs = positionMs
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val texture = TextureView(ctx).apply { isOpaque = false }
            exoPlayer.setVideoTextureView(texture)
            AspectRatioFrameLayout(ctx).apply {
                // ZOOM mode crops to fill the circle. FIT would letterbox
                // inside a square but the bubble would show empty corners
                // if the source were ever non-square (TDLib permits it
                // even though the official client doesn't capture that
                // way).
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setAspectRatio(1f)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addView(texture)
            }
        },
        onRelease = { frame ->
            exoPlayer.clearVideoTextureView(frame.getChildAt(0) as TextureView)
        },
    )
}

/**
 * Chrome painted over the [VideoNoteRenderer]:
 *   • progress ring (canvas arc against the player's live position)
 *   • remaining-time chip
 *   • centred play glyph (only when paused)
 *   • mute / unmute chip (independent tap target)
 *
 * Sits outside `key(muted)` so the chrome state survives the player swap
 * when audio is toggled. Position / duration ride in through [sharedState]
 * which the renderer writes to.
 */
@Composable
private fun BoxScope.VideoNoteChrome(
    content: PostContent.VideoNote,
    playing: Boolean,
    muted: Boolean,
    sharedState: VideoNoteSharedState,
    onToggleMute: () -> Unit,
) {
    val durationMs = sharedState.durationMs
    val positionMs = sharedState.positionMs
    val durationFallbackMs = (content.durationSec.toLong() * 1000L).coerceAtLeast(0L)
    val effectiveDuration = if (durationMs > 0L) durationMs else durationFallbackMs

    // Progress ring — sits just inside the clip so the rounded stroke isn't
    // sliced off by the CircleShape clip on the parent. Hidden until we
    // have a known duration (avoids a flicker of the empty ring on the very
    // first frame when ExoPlayer hasn't reported STATE_READY yet).
    if (effectiveDuration > 0L) {
        val progress = (positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
        val density = LocalDensity.current
        val stroke = with(density) { RING_STROKE.toPx() }
        val inset = with(density) { RING_INSET.toPx() } + stroke / 2f
        val ringColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }

    // Centred play glyph — shown when paused. The user can read "playing"
    // visually from the video itself; on pause the bubble freezes on the
    // current frame and we surface the resume affordance.
    if (!playing) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = "play_arrow",
                tint = Color.White,
                size = 32.dp,
                filled = true,
            )
        }
    }

    // Mute / unmute chip — independent tap target with its own scrim.
    // Nested clickable here intercepts before the bubble-wide pause toggle
    // (Compose pointer hit-testing dispatches to the innermost interactive
    // node first).
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Switch, onClick = onToggleMute),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = if (muted) "volume_off" else "volume_up",
            tint = Color.White,
            size = 18.dp,
        )
    }

    // Remaining-time chip — bottom-centre, the part of the bubble the
    // user's eye is least likely tracking the speaker's face, so the chip
    // doesn't obscure lips / expressions.
    if (effectiveDuration > 0L) {
        val remainingMs = (effectiveDuration - positionMs).coerceAtLeast(0L)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = formatRemaining(remainingMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/**
 * Live position / duration handed from [VideoNoteRenderer] to
 * [VideoNoteChrome], plus the saved playhead that survives the muted-toggle
 * pool swap. `mutableLongStateOf` so writes don't allocate; `@Stable` so the
 * chrome skips when neither field changed.
 */
@Stable
internal class VideoNoteSharedState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var savedPositionMs by mutableLongStateOf(0L)
}

/** "-0:08" / "-1:23" — minutes:seconds, leading minus mirrors Telegram-X. */
private fun formatRemaining(remainingMs: Long): String {
    val totalSec = (remainingMs + 500L) / 1000L
    val m = totalSec / 60
    val s = totalSec % 60
    return "-%d:%02d".format(m, s)
}

internal val VIDEO_NOTE_DIAMETER = 220.dp
private val RING_STROKE = 4.dp
private val RING_INSET = 2.dp
