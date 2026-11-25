package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.R
import kotlin.math.abs

/**
 * Playback-speed chip on the fullscreen viewer's top-right tool column, directly under
 * [QualityChip] and drawn by the same [ViewerPillChip]: "1×", "1.5×", … Tap opens a
 * [ViewerPickerSheet] with [VIDEO_PLAYBACK_SPEEDS].
 *
 * A picker, not the cycle the inline audio chip ([AudioSpeedChip]) uses: the audio cycle
 * only ever speeds up (1 → 1.5 → 2), while video adds a slower-than-normal step, so a cycle
 * would have to pass through 2× to reach 0.5× — mid-watch, every intermediate tap plays
 * audibly. The sheet reaches any step in one tap and matches how the neighbouring quality
 * setting is picked, so both playback settings in this viewer read as one system.
 *
 * Stateless: the viewer owns the per-session speed and feeds it to the active page's
 * [TdVideoPlayer]. Only this chip reads [current], so a speed change recomposes the chip
 * and the player's speed effect, nothing per frame.
 */
@Composable
internal fun SpeedChip(
    current: Float,
    onPick: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales.get(0)
    val label = stringResource(R.string.audio_speed_value, formatPlaybackSpeed(current, locale))
    ViewerPillChip(
        text = label,
        onClickLabel = stringResource(R.string.media_video_speed),
        contentDescription = stringResource(R.string.audio_speed_cd, label),
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        ViewerPickerSheet(
            title = stringResource(R.string.media_video_speed),
            onDismiss = { open = false },
        ) {
            VIDEO_PLAYBACK_SPEEDS.forEach { speed ->
                ViewerPickerRow(
                    label = stringResource(R.string.audio_speed_value, formatPlaybackSpeed(speed, locale)),
                    selected = abs(speed - current) < VIDEO_SPEED_EPSILON,
                    onClick = {
                        onPick(speed)
                        open = false
                    },
                )
            }
        }
    }
}

/** Speeds offered for fullscreen video, slowest first. ExoPlayer time-stretches, keeping pitch. */
internal val VIDEO_PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)

private const val VIDEO_SPEED_EPSILON = 0.01f
