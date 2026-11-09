package dev.lyo.hortay.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.theme.LocalGlassBackdrop
import dev.lyo.hortay.ui.theme.rememberGlassStyle
import dev.lyo.hortay.ui.timeline.formatDuration

/** Height of the mini-player strip itself. */
val AudioMiniPlayerHeight = 52.dp

/** Vertical room a host reserves (snackbar lift, list clearance) while the mini-player shows. */
val AudioMiniPlayerClearance = AudioMiniPlayerHeight + HortaySpacing.sm

/** Same cap as the floating nav bar, so the two pills line up on tablets. */
private val MiniPlayerMaxWidth = 480.dp

private val MiniPlayerShape = RoundedCornerShape(percent = 50)

/**
 * Whether the app-level mini-player is wanted right now: a track is loaded in
 * [AudioPlaybackSession] and none of its inline rows is composed (scrolled away, another
 * tab, another screen). Hosts read it to show [AudioMiniPlayer] and to reserve
 * [AudioMiniPlayerClearance] for snackbars and list padding.
 */
@Composable
fun audioMiniPlayerWanted(): Boolean {
    val session = LocalAudioPlaybackSession.current
    val wanted by session.showMiniPlayer.collectAsStateWithLifecycle()
    return wanted
}

/**
 * Compact "now playing" strip for the inline audio / voice session: play/pause, the track
 * line, the speed chip and a close button that stops playback and clears the session. A
 * thin progress line runs along its bottom edge.
 *
 * Same surface language as the floating nav bar: a frosted-glass pill over
 * [LocalGlassBackdrop] where the host provides one (Home), an opaque
 * `surfaceContainerHigh` pill elsewhere (detail screens). Capped at the nav bar's width and
 * centred. The caller places it (above the nav bar, or above the system bar inset) and
 * decides [visible]; enter / exit ride the MotionScheme specs. [modifier] positions the
 * animated container; [stripModifier] (insets, gutters) applies inside it, so the gaps grow
 * and shrink with the strip instead of popping in.
 *
 * Recomposition budget: the session ticks its position every 200 ms. The progress line
 * reads it in the draw phase only; phase / track / elapsed seconds go through
 * [derivedStateOf], so the strip recomposes at most once per second while playing.
 */
@Composable
fun AudioMiniPlayer(
    visible: Boolean,
    modifier: Modifier = Modifier,
    stripModifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // clip = false: the pill's shadow would otherwise be cut along the container edge.
        enter = expandVertically(animationSpec = motion.defaultSpatialSpec<IntSize>(), clip = false) +
            fadeIn(motion.defaultEffectsSpec<Float>()),
        exit = shrinkVertically(animationSpec = motion.fastSpatialSpec<IntSize>(), clip = false) +
            fadeOut(motion.fastEffectsSpec<Float>()),
    ) {
        MiniPlayerStrip(stripModifier)
    }
}

@Composable
private fun MiniPlayerStrip(outerModifier: Modifier) {
    val session = LocalAudioPlaybackSession.current
    val playbackState = session.state.collectAsStateWithLifecycle()
    val phase by remember(playbackState) { derivedStateOf { playbackState.value?.phase } }
    val liveTrack by remember(playbackState) { derivedStateOf { playbackState.value?.track } }
    val elapsedSec by remember(playbackState) {
        derivedStateOf { ((playbackState.value?.positionMs ?: 0L) / 1000L).toInt() }
    }
    val totalSec by remember(playbackState) {
        derivedStateOf { ((playbackState.value?.durationMs ?: 0L) / 1000L).toInt() }
    }
    // Keep the last track on screen while the strip animates out after close / track end.
    val lastTrack = remember { mutableStateOf<AudioPlaybackSession.TrackInfo?>(null) }
    SideEffect { if (liveTrack != null) lastTrack.value = liveTrack }
    val track = liveTrack ?: lastTrack.value

    val title = when {
        track?.isVoice == true -> stringResource(R.string.voice_message)
        track != null && track.title.isNotBlank() -> track.title
        else -> stringResource(R.string.content_audio_fallback)
    }
    val timeText = "${formatDuration(elapsedSec)} / ${formatDuration(totalSec)}"
    val performer = if (track != null && !track.isVoice) track.performer?.takeIf { it.isNotBlank() } else null
    val secondary = if (performer != null) "$timeText · $performer" else timeText

    val backdrop = LocalGlassBackdrop.current
    val glass = rememberGlassStyle()
    val fallback = MaterialTheme.colorScheme.surfaceContainerHigh
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HortayAlpha.HAIRLINE)
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HortayAlpha.HAIRLINE)

    Box(
        modifier = outerModifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MiniPlayerMaxWidth)
            .fillMaxWidth()
            .height(AudioMiniPlayerHeight)
            .shadow(elevation = 6.dp, shape = MiniPlayerShape, clip = false)
            .clip(MiniPlayerShape)
            .then(
                if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Sources(backdrop), style = glass)
                else Modifier.background(fallback),
            )
            .border(width = 0.5.dp, color = hairline, shape = MiniPlayerShape)
            .drawWithContent {
                drawContent()
                // Draw-phase read: the 200 ms position tick invalidates only this layer.
                val p = playbackState.value
                val fraction = if (p != null && p.durationMs > 0L) {
                    (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                // Inset by the corner radius so the line stays inside the pill's flat edge.
                val stroke = 2.dp.toPx()
                val inset = size.height / 2f
                val y = size.height - stroke
                val start = inset
                val end = size.width - inset
                if (end > start) {
                    drawLine(trackColor, Offset(start, y), Offset(end, y), strokeWidth = stroke)
                    drawLine(
                        progressColor,
                        Offset(start, y),
                        Offset(start + (end - start) * fraction, y),
                        strokeWidth = stroke,
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = HortaySpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayPauseButton(phase = phase, onClick = session::togglePlayPause)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = HortaySpacing.xs),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AudioSpeedChip()
            val closeCd = stringResource(R.string.audio_mini_close)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = closeCd, role = Role.Button, onClick = session::clear)
                    .semantics { contentDescription = closeCd },
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayPauseButton(phase: AudioPlaybackSession.Phase?, onClick: () -> Unit) {
    val cd = when (phase) {
        AudioPlaybackSession.Phase.Playing -> stringResource(R.string.rich_audio_pause)
        AudioPlaybackSession.Phase.Loading -> stringResource(R.string.rich_audio_loading)
        else -> stringResource(R.string.rich_audio_play)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = cd, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                AudioPlaybackSession.Phase.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                AudioPlaybackSession.Phase.Playing -> Symbol(
                    name = "pause",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 20.dp,
                    filled = true,
                )
                else -> Symbol(
                    name = "play_arrow",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 20.dp,
                    filled = true,
                )
            }
        }
    }
}

/**
 * Playback-speed chip ("1×" → "1.5×" → "2×" → "1×") for the session's loaded track. The
 * speed lives in [AudioPlaybackSession] for the process lifetime, so the inline row and the
 * mini-player show the same value and the next track starts at it. 48 dp touch target around
 * a compact tonal label; screen readers hear "Playback speed 1.5×".
 */
@Composable
internal fun AudioSpeedChip(modifier: Modifier = Modifier) {
    val session = LocalAudioPlaybackSession.current
    val speed by session.speed.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales.get(0)
    val label = stringResource(R.string.audio_speed_value, formatPlaybackSpeed(speed, locale))
    val cd = stringResource(R.string.audio_speed_cd, label)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = cd, role = Role.Button, onClick = session::cycleSpeed)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clearAndSetSemantics {}
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = HortaySpacing.sm, vertical = HortaySpacing.xxs),
        )
    }
}
