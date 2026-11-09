package dev.lyo.hortay.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.LocalGlassBackdrop
import dev.lyo.hortay.ui.theme.rememberGlassStyle

private val BarShape = RoundedCornerShape(percent = 50)

/**
 * Floating frosted-glass navigation bar: a pill that blurs the feed scrolling under it
 * ([LocalGlassBackdrop]), with an icon and a label per destination.
 *
 * Labels are always shown — icon-only tabs made users guess what "people" or the bookmark
 * meant, and Material's own navigation-bar guidance keeps labels on for 3–5 destinations.
 *
 * Selection is ONE capsule behind the whole selected cell (icon + label) that slides
 * between tabs on the spatial spring — a lens moving across the glass. The earlier
 * design stacked a per-icon pill under a full-cell ripple inside the bar, so every tap
 * flashed three nested rounded shapes. Presses now answer with a small scale-down of the
 * tab instead of a ripple; the selected tab adds a filled, accent-tinted icon and label.
 */
@Composable
fun FloatingNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalGlassBackdrop.current
    val glass = rememberGlassStyle()
    val fallback = MaterialTheme.colorScheme.surfaceContainer
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val capsule = MaterialTheme.colorScheme.primary.copy(alpha = CAPSULE_ALPHA)
    val tabs = NavTab.entries
    val selectedIndex by animateFloatAsState(
        targetValue = tabs.indexOf(selected).toFloat(),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "nav-capsule",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // Tablets / desktop windows: a centred pill capped at a phone-like width instead
            // of a full-width bar. Phones sit below the cap, so their layout is unchanged.
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = BAR_MAX_WIDTH)
            .fillMaxWidth()
            .shadow(elevation = 10.dp, shape = BarShape, clip = false)
            .clip(BarShape)
            .then(
                if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Sources(backdrop), style = glass)
                else Modifier.background(fallback),
            )
            .border(width = 0.5.dp, color = hairline, shape = BarShape)
            .padding(BAR_INSET),
    ) {
        val cellWidth = maxWidth / tabs.size
        // The sliding capsule: drawn once, behind the row, positioned by the animated
        // index so it glides (and slightly overshoots on the expressive spring) from tab
        // to tab instead of fading out on one and in on another.
        // matchParentSize: the capsule takes the row's height, never the bar's max
        // constraint (fillMaxHeight on its own would stretch the whole bar).
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((cellWidth.toPx() * selectedIndex).roundToInt(), 0) }
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clip(BarShape)
                    .background(capsule),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
            tabs.forEach { tab ->
                NavTabButton(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavTabButton(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "nav-press",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-tint",
    )
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                // The capsule and the press scale are the feedback; a ripple on top was
                // the third nested shape.
                indication = null,
                role = Role.Tab,
                onClick = {
                    // Discrete destination switch (or home re-tap → scroll-to-top) —
                    // one ContextClick per deliberate tab press, fired before the
                    // callback so the tick lands with the press, not after navigation.
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The outline and filled drawables are different vectors; the crossfade keeps
        // the swap in step with the capsule instead of blinking in one frame.
        Crossfade(
            targetState = selected,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "nav-icon-fill",
        ) { isFilled ->
            Symbol(
                name = tab.symbol,
                contentDescription = null,
                tint = tint,
                size = 22.dp,
                filled = isFilled,
            )
        }
        Spacer(Modifier.height(2.dp))
        // The visible label doubles as the tab's accessible name — TalkBack merges it with
        // the `selectable` role into "<label>, tab, selected".
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Widest the pill gets; shared visually with the audio mini-player above it. */
private val BAR_MAX_WIDTH = 480.dp

/** Gap between the bar's edge and the sliding capsule. */
private val BAR_INSET = 5.dp

/** Capsule tint over the glass — enough to read as a lens, light enough to keep the blur. */
private const val CAPSULE_ALPHA = 0.14f

/** How far a tab shrinks under the finger. */
private const val PRESSED_SCALE = 0.92f
