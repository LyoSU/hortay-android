@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.util.rememberReducedMotion
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
 *     The stock M3 1.5 track + bar thumb then read as a chunky white capsule
 *     with a tall handle over video, so the Slider keeps its gesture and
 *     semantics but paints a slim custom track ([SeekTrack], 4 dp → 6 dp while
 *     scrubbing) and a 12 dp round thumb with no thumb–track gap.
 *
 *   • **Auto-hide** — controls fade out 3 s after the last interaction, but
 *     only while playing. Paused state keeps the play button onscreen as the
 *     primary "tap to resume" affordance. A picker opened from [extraControls]
 *     (speed, quality) pins the controls for as long as it is open: the
 *     picker is anchored to a chip inside the fading layer, and the 3 s hide
 *     would otherwise pull it out from under the user's finger.
 *
 *   • **Touch model** — single tap toggles controls; double-tap on the left or
 *     right half of the surface seeks ∓10 s (Telegram / YouTube canonical).
 *     Both gesture lanes coexist via Compose's standard tap-gesture detector.
 */
@Composable
fun VideoPlayerControls(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    /**
     * Playback-setting chips (quality, speed) laid out in the bottom row before the mute
     * button. The lambda receives `holdChrome`: call it with true while a picker is open and
     * false once it closes, so the auto-hide doesn't fade the chips (and the picker anchored
     * to them) away mid-choice.
     */
    extraControls: (@Composable (holdChrome: (Boolean) -> Unit) -> Unit)? = null,
) {
    val isPlaying by produceState(player.isPlaying, player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { value = playing }
        }
        player.addListener(listener)
        awaitDispose { player.removeListener(listener) }
    }
    // STATE_ENDED requires special-cased Play: ExoPlayer's `play()` is a no-op
    // when position is already past `duration`, so the centre button must
    // `seekTo(0)` before resuming or the user sees a dead tap. Looping
    // (`REPEAT_MODE_ONE`) handles this automatically — this branch only
    // matters for finite-playback videos in fullscreen.
    val isEnded by produceState(player.playbackState == Player.STATE_ENDED, player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                value = state == Player.STATE_ENDED
            }
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
    // An open picker holds the chrome; closing it re-keys the effect, which restarts the
    // full 3 s countdown from that moment.
    var chromeHeld by remember { mutableStateOf(false) }
    val holdChrome: (Boolean) -> Unit = remember {
        { held ->
            chromeHeld = held
            if (held) controlsVisible = true
        }
    }
    LaunchedEffect(controlsVisible, interactionTick, isPlaying, chromeHeld) {
        if (controlsVisible && isPlaying && !chromeHeld) {
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
                // Centre play/pause. Polygon backdrop morphs between Square
                // (paused, "stopped") and Circle (playing, "in motion"); the
                // glyph crossfades between play_arrow and pause. 72 dp reads
                // hero without crowding the playback frame.
                CenterPlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = {
                        when {
                            isEnded -> {
                                player.seekTo(0L)
                                player.play()
                            }
                            isPlaying -> player.pause()
                            else -> player.play()
                        }
                        showControls()
                    },
                    modifier = Modifier.align(Alignment.Center),
                )

                // Bottom band: seek bar over time · chips · mute.
                BottomBar(
                    extraControls = extraControls,
                    holdChrome = holdChrome,
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
                        .fillMaxWidth()
                        // Bottom scrim — keeps the time labels and seek bar readable
                        // against bright frames. Painted as the band's own background
                        // (before the insets) so it reaches under the navigation bar and
                        // grows with the band when the row wraps at large font scale,
                        // instead of a fixed-height box the wrapped row could outgrow.
                        // The top of the viewer is left to the viewer's own top scrim.
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = BOTTOM_SCRIM_ALPHA),
                            ),
                        )
                        .navigationBarsPadding()
                        // Bottom inset is 4 dp under the visible 12 dp: the 48 dp touch
                        // targets of the row's 44 dp discs add that much slop below them.
                        .padding(start = 12.dp, end = 12.dp, top = BOTTOM_SCRIM_LEAD, bottom = 8.dp),
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
            .clickable(role = Role.Button, onClick = onClick),
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
                contentDescription = if (playing) {
                    stringResource(R.string.video_control_pause)
                } else {
                    stringResource(R.string.video_control_play)
                },
                tint = Color.White,
                size = 36.dp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    extraControls: (@Composable (holdChrome: (Boolean) -> Unit) -> Unit)?,
    holdChrome: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // M3 Slider for the gesture, tap-to-seek and the a11y "set progress" action;
        // [SeekTrack] + [SeekThumb] replace its stock visuals (see the file KDoc). The
        // 48 dp minimum height is the scrub hit region — the slider centres its 12 dp thumb
        // and slim track inside it, so the visible bar stays thin while the finger still
        // lands on a full touch target. Track and thumb draw white, like the rest of the
        // viewer chrome, so they read on any frame.
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val dragged by interactionSource.collectIsDraggedAsState()
        val scrubbing = pressed || dragged || isDragging
        // M3 1.5 only takes custom thumb/track on the SliderState overload. Passing
        // onValueChange keeps the slider controlled: the drag reports up, and the state is
        // re-synced from [displayProgress] after each composition.
        val sliderState = remember { SliderState(value = displayProgress) }
        SideEffect { sliderState.value = displayProgress }
        Slider(
            state = sliderState,
            onValueChange = onSliderChange,
            onValueChangeFinished = onSliderChangeFinished,
            interactionSource = interactionSource,
            thumb = { SeekThumb() },
            track = { state -> SeekTrack(fraction = state.coercedValueAsFraction, scrubbing = scrubbing) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SEEK_HIT_HEIGHT),
        )

        // Time on the start side, playback chips + mute on the end side. Wraps to two lines
        // (time over the controls) when both don't fit one — font scale 2.0, long chip labels
        // — instead of squeezing or clipping either group. Placement is start/end-relative, so
        // RTL mirrors it.
        TimeAndControlsRow(
            time = {
                // Tabular figures so the digit ribbon doesn't jitter as seconds tick over.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatHmmSs(
                            if (isDragging && durationMs > 0) (displayProgress * durationMs).toLong() else positionMs,
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
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
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    )
                }
            },
            controls = {
                Row(
                    // 48 dp touch targets around 44 dp discs leave 4 dp of slop between
                    // neighbours, which is the visible gap; no extra spacing needed.
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // The trailing 2 dp of touch-target slop is cancelled so the mute disc
                    // sits flush with the seek bar's end edge.
                    modifier = Modifier.offset(x = 2.dp),
                ) {
                    extraControls?.invoke(holdChrome)
                    // Mute is a setting, not a primary action: the same 44 dp chrome disc as
                    // close / save / share, with a glyph crossfade between volume_up and
                    // volume_off.
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(VIEWER_CHROME_DISC)
                            .viewerChromeBackdrop(CircleShape),
                    ) {
                        Crossfade(
                            targetState = muted,
                            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "mute-icon",
                        ) { isMuted ->
                            Symbol(
                                name = if (isMuted) "volume_off" else "volume_up",
                                contentDescription = if (isMuted) {
                                    stringResource(R.string.video_control_unmute)
                                } else {
                                    stringResource(R.string.video_control_mute)
                                },
                                tint = Color.White,
                                size = 22.dp,
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * Lays out [time] at the start and [controls] at the end of one line when they fit side by
 * side with a [ROW_MIN_GAP] between them; otherwise [time] takes the first line and
 * [controls] the second, end-aligned. `placeRelative` mirrors both for RTL.
 */
@Composable
private fun TimeAndControlsRow(
    time: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(time, controls),
        modifier = Modifier.fillMaxWidth(),
    ) { (timeMeasurables, controlsMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val controlsPlaceables = controlsMeasurables.map { it.measure(loose) }
        val timePlaceables = timeMeasurables.map { it.measure(loose) }
        val controlsWidth = controlsPlaceables.maxOfOrNull { it.width } ?: 0
        val controlsHeight = controlsPlaceables.maxOfOrNull { it.height } ?: 0
        val timeWidth = timePlaceables.maxOfOrNull { it.width } ?: 0
        val timeHeight = timePlaceables.maxOfOrNull { it.height } ?: 0
        val width = constraints.maxWidth
        val oneLine = timeWidth + ROW_MIN_GAP.roundToPx() + controlsWidth <= width
        val height = if (oneLine) maxOf(timeHeight, controlsHeight) else timeHeight + controlsHeight
        layout(width, height) {
            if (oneLine) {
                timePlaceables.forEach { it.placeRelative(0, (height - it.height) / 2) }
                controlsPlaceables.forEach { it.placeRelative(width - it.width, (height - it.height) / 2) }
            } else {
                timePlaceables.forEach { it.placeRelative(0, 0) }
                controlsPlaceables.forEach { it.placeRelative(width - it.width, timeHeight) }
            }
        }
    }
}

/** The seek bar's 12 dp round white thumb. Sized exactly, so the slider leaves no gap around it. */
@Composable
private fun SeekThumb() {
    Box(
        modifier = Modifier
            .size(SEEK_THUMB)
            .background(Color.White, CircleShape),
    )
}

/**
 * Slim seek track: white up to [fraction], white at [SEEK_INACTIVE_ALPHA] after it, round
 * caps, no gap at the thumb and no stop indicator. Thickens from [SEEK_TRACK] to
 * [SEEK_TRACK_SCRUBBING] while the user scrubs, on the fast spatial spring (a snap under
 * "Remove animations"). Drawn on a Canvas, which doesn't mirror on its own — the fill runs
 * from the right edge in RTL, matching the slider's own RTL value mapping.
 */
@Composable
private fun SeekTrack(fraction: Float, scrubbing: Boolean) {
    val reducedMotion = rememberReducedMotion()
    val thicknessSpec: AnimationSpec<Dp> =
        if (reducedMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec()
    val thickness by animateDpAsState(
        targetValue = if (scrubbing) SEEK_TRACK_SCRUBBING else SEEK_TRACK,
        animationSpec = thicknessSpec,
        label = "seek-track-thickness",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEEK_TRACK_SCRUBBING),
    ) {
        val stroke = thickness.toPx()
        val y = size.height / 2f
        val start = stroke / 2f
        val end = size.width - stroke / 2f
        if (end <= start) return@Canvas
        val split = start + (end - start) * fraction.coerceIn(0f, 1f)
        val activeFrom = if (rtl) size.width - split else start
        val activeTo = if (rtl) end else split
        val inactiveFrom = if (rtl) start else split
        val inactiveTo = if (rtl) size.width - split else end
        drawLine(
            color = Color.White.copy(alpha = SEEK_INACTIVE_ALPHA),
            start = Offset(inactiveFrom, y),
            end = Offset(inactiveTo, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (activeTo > activeFrom) {
            drawLine(
                color = Color.White,
                start = Offset(activeFrom, y),
                end = Offset(activeTo, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
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

// Bottom band scrim: black at this alpha at the screen edge, fading to clear over the band
// plus [BOTTOM_SCRIM_LEAD] above the seek bar.
private const val BOTTOM_SCRIM_ALPHA = 0.55f
private val BOTTOM_SCRIM_LEAD = 40.dp

// Seek bar: 4 dp resting track, 6 dp while scrubbing, 12 dp thumb, 48 dp scrub hit region.
private val SEEK_TRACK = 4.dp
private val SEEK_TRACK_SCRUBBING = 6.dp
private val SEEK_THUMB = 12.dp
private val SEEK_HIT_HEIGHT = 48.dp
private const val SEEK_INACTIVE_ALPHA = 0.3f

// Smallest gap kept between the time label and the controls before the row wraps.
private val ROW_MIN_GAP = 12.dp
private const val SEEK_STEP_MS = 10_000L
