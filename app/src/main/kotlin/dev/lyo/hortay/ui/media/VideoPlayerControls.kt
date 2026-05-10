@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.theme.asComposeShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Custom Compose chrome painted over an [ExoPlayer]'s video surface. Replaces the
 * stock `media3` `PlayerView` controller — that one's grey scrubber + blocky
 * pause button speaks 2010s system-default and visually clashed with everything
 * else in the app, which is end-to-end Material 3 Expressive (polygon shapes,
 * wavy progress, motion-token transitions).
 *
 * The hero choices, all canonical M3E patterns:
 *
 *   • **Centre play/pause** — 72 dp filled disc with a polygon backdrop morphing
 *     `Square (paused) ↔ Circle (playing)`. This is the same pair Google's
 *     Material 3 sample app uses for every media-state toggle (play/pause,
 *     mute/unmute, record start/stop). Reusing one shape idiom = one rule the
 *     user learns instead of one per control. Cookie / Burst / Heart shapes
 *     stay reserved for hero / personality moments (reactions, empty states)
 *     and are intentionally NOT used here — the play button is high-frequency
 *     UI, expressive but calm.
 *
 *   • **Flat M3 Slider for the seek bar** — wavy progress (`LinearWavyProgressIndicator`)
 *     was the first cut here, but its canonical M3E rendering paints the wave
 *     only on the *completed* portion of the track. At 0:00 the bar reads as
 *     a straight line (no completed portion to wave); after a seek mid-clip
 *     it suddenly reads as wavy. The visual asymmetry between "video just
 *     started" and "video mid-playback" is canonical for an upload-style
 *     ongoing-process indicator, but for a seekable media bar — where the
 *     user expects one stable shape they can drag — it reads as a bug. The
 *     standard M3 `Slider` (flat track + visible thumb) is both the M3 1.5
 *     recommendation for media seekers and the Telegram / YouTube / Instagram
 *     convention. Wavy progress remains the right affordance elsewhere in the
 *     app (download, migration) where the bar fills monotonically.
 *
 *   • **Auto-hide** — controls fade out 3 s after the last interaction, but
 *     only while playing. Paused state keeps the play button onscreen as the
 *     primary "tap to resume" affordance.
 *
 *   • **Touch model** — single tap toggles controls; double-tap on the left or
 *     right half of the surface seeks ∓10 s (Telegram / YouTube canonical).
 *     Both gesture lanes coexist via Compose's standard tap-gesture detector.
 */
@Composable
fun VideoPlayerControls(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    val isPlaying by produceState(player.isPlaying, player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { value = playing }
        }
        player.addListener(listener)
        awaitDispose { player.removeListener(listener) }
    }
    val durationMs by produceState(player.duration.coerceAtLeast(0L), player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    value = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        awaitDispose { player.removeListener(listener) }
    }
    // Position is polled rather than push-based: ExoPlayer doesn't emit per-ms
    // updates and a 100 ms tick is plenty for the slider thumb to track playback
    // smoothly at human-readable resolution. Falls back to a slower beat while
    // paused — no sense waking the dispatcher 10× per second to read the same
    // number.
    val positionMs by produceState(player.currentPosition.coerceAtLeast(0L), player) {
        while (isActive) {
            value = player.currentPosition.coerceAtLeast(0L)
            delay(if (player.isPlaying) 100L else 500L)
        }
    }

    // Mirror of player.volume kept in compose state for the mute toggle's
    // immediate visual feedback. ExoPlayer.Listener.onVolumeChanged also fires
    // (e.g. system volume slider, route changes) — wired so the icon stays
    // truthful regardless of who flipped the switch.
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVolumeChanged(volume: Float) { muted = volume == 0f }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Auto-hide: any user-meaningful event bumps the tick; the LaunchedEffect
    // below schedules a 3 s hide off the latest tick. Pausing keeps controls
    // pinned (paused video should always show the resume affordance).
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    val showControls: () -> Unit = remember {
        {
            controlsVisible = true
            interactionTick = interactionTick + 1
        }
    }
    LaunchedEffect(controlsVisible, interactionTick, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Seek-bar drag state. While the Slider thumb is being dragged we display the
    // user's in-flight value instead of [positionMs] — the player's reported
    // position lags ExoPlayer's seek RPC by hundreds of ms, so without this
    // shadow the thumb visibly snaps back to "yesterday" between drag updates
    // and the seek RPC committing.
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val displayProgress: Float = when {
        isDragging -> dragProgress
        durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Full-surface tap detector: single tap toggles the chrome,
            // double-tap on the left/right half seeks ±10 s. Position-aware
            // double-tap is the canonical mobile-video pattern (YouTube /
            // Telegram / Instagram).
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) interactionTick = interactionTick + 1
                    },
                    onDoubleTap = { offset ->
                        // Read size at gesture time, not at pointerInput setup,
                        // so a window resize / rotation between mount and the
                        // first double-tap still picks the correct half-line.
                        val width = size.width.toFloat()
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val total = player.duration.coerceAtLeast(0L)
                        val target = if (offset.x < width / 2f) {
                            (pos - SEEK_STEP_MS).coerceAtLeast(0L)
                        } else if (total > 0L) {
                            (pos + SEEK_STEP_MS).coerceAtMost(total)
                        } else {
                            pos + SEEK_STEP_MS
                        }
                        player.seekTo(target)
                        showControls()
                    },
                )
            },
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Bottom scrim — keeps the time labels and seek bar readable
                // against bright photo / sky backgrounds. Top half of the
                // viewer is left unscrimmed because the close button + counter
                // already carry their own polygon backdrops.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                )

                // Centre play/pause. Polygon backdrop morphs between Square
                // (paused, "stopped") and Circle (playing, "in motion"); the
                // glyph crossfades between play_arrow and pause. 72 dp reads
                // hero without crowding the playback frame.
                CenterPlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = {
                        if (isPlaying) player.pause() else player.play()
                        showControls()
                    },
                    modifier = Modifier.align(Alignment.Center),
                )

                // Bottom row: current time · seek slider · total time · mute.
                BottomBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    displayProgress = displayProgress,
                    isDragging = isDragging,
                    onSliderChange = { progress ->
                        isDragging = true
                        dragProgress = progress
                        showControls()
                    },
                    onSliderChangeFinished = {
                        if (durationMs > 0L) {
                            player.seekTo((dragProgress * durationMs).toLong().coerceIn(0L, durationMs))
                        }
                        isDragging = false
                        showControls()
                    },
                    muted = muted,
                    onToggleMute = {
                        val next = !muted
                        player.volume = if (next) 0f else 1f
                        muted = next
                        showControls()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drive the Square↔Circle morph off a single 0..1 progress through M3E's
    // spatial-channel spring. Reads as a deliberate state transition, not a
    // flicker. Riding `motionScheme.defaultSpatialSpec` keeps the morph in lock
    // step with every other shape transition in the app (chips, nav, screen
    // slides) instead of rolling its own bouncy spring.
    val morphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "play-pause-morph",
    )
    // MorphShape allocates a fresh Path on every createOutline; cheap, but we
    // re-instantiate per-frame anyway because Shape is a no-arg interface and
    // animation drives it. The cost (one Path object per frame) is well below
    // the playback / decode overhead at this point in the frame.
    val liveShape = MorphShape(HortayExpressive.PlayPauseMorph, morphProgress)

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(liveShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Crossfade the glyph so play_arrow ↔ pause swap reads as a smooth
        // morph rather than a stutter — pairs naturally with the shape morph,
        // riding the same MotionScheme spring as the rest of the app.
        Crossfade(
            targetState = isPlaying,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "play-pause-icon",
        ) { playing ->
            Symbol(
                name = if (playing) "pause" else "play_arrow",
                contentDescription = if (playing) "pause" else "play",
                tint = Color.White,
                size = 36.dp,
            )
        }
    }
}

@Composable
private fun BottomBar(
    positionMs: Long,
    durationMs: Long,
    displayProgress: Float,
    isDragging: Boolean,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // M3 Slider: built-in drag + tap-to-seek + thumb position is the
        // canonical seek affordance. White-on-translucent palette overrides
        // primary so the thumb / track read against any photo or video frame
        // (matches the rest of the viewer chrome — close button, page
        // counter, mute toggle all paint white over a black-translucent
        // backdrop).
        Slider(
            value = displayProgress,
            onValueChange = onSliderChange,
            onValueChangeFinished = onSliderChangeFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.30f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Time row + mute toggle. Tabular figures so the digit ribbon
        // doesn't jitter as seconds tick over.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatHmmSs(if (isDragging && durationMs > 0) (displayProgress * durationMs).toLong() else positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "/",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatHmmSs(durationMs),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
            // Mute toggle re-uses the same toggle vocabulary as the centre
            // play/pause: a polygon backdrop with a glyph crossfade between
            // volume_up and volume_off. 40 dp is the minimum M3 IconButton
            // size and visually subordinates this to the centre control —
            // mute is a setting, not a primary action.
            val muteShape = HortayExpressive.Pill.asComposeShape()
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.55f), muteShape),
            ) {
                Crossfade(
                    targetState = muted,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "mute-icon",
                ) { isMuted ->
                    Symbol(
                        name = if (isMuted) "volume_off" else "volume_up",
                        contentDescription = if (isMuted) "unmute" else "mute",
                        tint = Color.White,
                        size = 20.dp,
                    )
                }
            }
        }
    }
}

private fun formatHmmSs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val AUTO_HIDE_MS = 3_000L
private const val SEEK_STEP_MS = 10_000L
