package dev.lyo.hortay.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.util.rememberReducedMotion
import java.util.Locale
import kotlin.math.abs

/**
 * Playback-speed chip in the fullscreen video's bottom control row, next to [QualityChip]
 * and drawn by the same [ViewerPillChip]: "1×", "1.5×", … Tap opens [SpeedPickerPopup], a
 * compact strip of connected segments ([VIDEO_PLAYBACK_SPEEDS]) anchored just above the chip.
 *
 * A picker, not the cycle the inline audio chip ([AudioSpeedChip]) uses: the audio cycle
 * only ever speeds up (1 → 1.5 → 2), while video adds a slower-than-normal step, so a cycle
 * would have to pass through 2× to reach 0.5× — mid-watch, every intermediate tap plays
 * audibly. The strip reaches any step in one tap. It replaced a half-screen bottom sheet
 * that covered the video for a four-option choice; the strip leaves the frame visible so
 * the new speed can be judged the moment it applies.
 *
 * [holdChrome] is called with true while the strip is open and false once it closes: the
 * chip lives in the playback chrome's auto-hiding layer, and a hide would remove the strip's
 * anchor from under the user.
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
    holdChrome: (Boolean) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales.get(0)
    val label = stringResource(R.string.audio_speed_value, formatPlaybackSpeed(current, locale))
    Box(modifier = modifier) {
        ViewerPillChip(
            text = label,
            onClickLabel = stringResource(R.string.media_video_speed),
            contentDescription = stringResource(R.string.audio_speed_cd, label),
            onClick = { open = true },
        )
        if (open) {
            DisposableEffect(Unit) {
                holdChrome(true)
                onDispose { holdChrome(false) }
            }
            SpeedPickerPopup(
                current = current,
                locale = locale,
                onPick = { speed ->
                    onPick(speed)
                    open = false
                },
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * The speed strip: one row of four connected segments on a floating surface, placed above
 * its anchor (the chip) with their end edges aligned, or below it when there is no room
 * above. Focusable popup, so system back and a tap outside dismiss it; picking a segment
 * applies the speed, ticks a segment haptic when the speed actually changes, and closes it.
 *
 * Segments are >= 48 dp tall; the selected one is tonal-filled (secondaryContainer) with a
 * full-round outline, the others sit on a quiet surfaceContainerHighest fill with tight
 * inner corners — the M3 Expressive connected button-group silhouette, built from plain
 * Box + selectable so no experimental button-group API is pinned here. Selection is not
 * animated (a recurring control; the intensity budget keeps it quiet). The strip itself
 * eases in with a short fade and a 0.92 → 1 scale from its anchored edge, skipped under
 * "Remove animations".
 */
@Composable
private fun SpeedPickerPopup(
    current: Float,
    locale: Locale,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val positionProvider = remember(density) {
        AboveAnchorPositionProvider(
            gapPx = with(density) { POPUP_ANCHOR_GAP.roundToPx() },
            marginPx = with(density) { POPUP_WINDOW_MARGIN.roundToPx() },
        )
    }
    val reducedMotion = rememberReducedMotion()
    val visibleState = remember { MutableTransitionState(reducedMotion).apply { targetState = true } }
    val enter: EnterTransition = if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleIn(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                initialScale = POPUP_ENTER_SCALE,
                transformOrigin = TransformOrigin(0.5f, 1f),
            )
    }
    val title = stringResource(R.string.media_video_speed)
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(visibleState = visibleState, enter = enter) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 3.dp,
                modifier = Modifier.semantics { paneTitle = title },
            ) {
                Row(
                    modifier = Modifier
                        .selectableGroup()
                        .padding(POPUP_INNER_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIDEO_PLAYBACK_SPEEDS.forEachIndexed { index, speed ->
                        val selected = abs(speed - current) < VIDEO_SPEED_EPSILON
                        SpeedSegment(
                            label = stringResource(R.string.audio_speed_value, formatPlaybackSpeed(speed, locale)),
                            selected = selected,
                            shape = segmentShape(index, VIDEO_PLAYBACK_SPEEDS.size, selected),
                            onClick = {
                                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(speed)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSegment(
    label: String,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .heightIn(min = SEGMENT_MIN_HEIGHT)
            .widthIn(min = SEGMENT_MIN_WIDTH)
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Connected-group outline for segment [index] of [count]: fully round outer ends, tight
 * inner corners where segments meet, and fully round for the selected segment. Start/end
 * corners are relative, so the strip mirrors in RTL.
 */
private fun segmentShape(index: Int, count: Int, selected: Boolean): Shape {
    if (selected) return CircleShape
    val outer = 50
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStartPercent = if (first) outer else SEGMENT_INNER_CORNER_PERCENT,
        bottomStartPercent = if (first) outer else SEGMENT_INNER_CORNER_PERCENT,
        topEndPercent = if (last) outer else SEGMENT_INNER_CORNER_PERCENT,
        bottomEndPercent = if (last) outer else SEGMENT_INNER_CORNER_PERCENT,
    )
}

/**
 * Places the popup [gapPx] above its anchor with the anchor's end edge (right in LTR, left
 * in RTL), clamped [marginPx] inside the window; flips below the anchor when it doesn't fit
 * above.
 */
private class AboveAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rawX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = rawX.coerceIn(marginPx, maxX)
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = if (above >= marginPx) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

/** Speeds offered for fullscreen video, slowest first. ExoPlayer time-stretches, keeping pitch. */
internal val VIDEO_PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)

private const val VIDEO_SPEED_EPSILON = 0.01f

// Strip geometry: 48 dp segments (the touch target, so no extra slop), 2 dp between them,
// 4 dp of surface around them, 8 dp above the chip and from the window edges.
private val SEGMENT_MIN_HEIGHT = 48.dp
private val SEGMENT_MIN_WIDTH = 56.dp
private val SEGMENT_GAP = 2.dp
private val POPUP_INNER_PADDING = 4.dp
private val POPUP_ANCHOR_GAP = 8.dp
private val POPUP_WINDOW_MARGIN = 8.dp

// Inner corner of a connected segment, as a percent of its (48 dp) height: ~8 dp.
private const val SEGMENT_INNER_CORNER_PERCENT = 17

// The strip grows from its anchored edge from this scale, never from 0.
private const val POPUP_ENTER_SCALE = 0.92f
