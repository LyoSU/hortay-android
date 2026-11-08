package dev.lyo.hortay.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.LocalGlassBackdrop
import dev.lyo.hortay.ui.theme.rememberGlassStyle

private val BarShape = RoundedCornerShape(percent = 50)
private val IndicatorShape = RoundedCornerShape(percent = 50)

/**
 * Floating frosted-glass navigation bar: a pill that blurs the feed scrolling under it
 * ([LocalGlassBackdrop]), with an icon and a label per destination.
 *
 * Labels are always shown — icon-only tabs made users guess what "people" or the bookmark
 * meant, and Material's own navigation-bar guidance keeps labels on for 3–5 destinations.
 * The selected tab gets a filled icon on a tonal indicator pill and a bolder label: three
 * cues that land together on the same effects spring.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(elevation = 10.dp, shape = BarShape, clip = false)
            .clip(BarShape)
            .then(
                if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Sources(backdrop), style = glass)
                else Modifier.background(fallback),
            )
            .border(width = 0.5.dp, color = hairline, shape = BarShape)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .selectableGroup(),
    ) {
        NavTab.entries.forEach { tab ->
            NavTabButton(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
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
    val haptics = LocalHapticFeedback.current
    val indicator by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-indicator",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-fg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-icon",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.Tab,
                onClick = {
                    // Discrete destination switch (or home re-tap → scroll-to-top) —
                    // one ContextClick per deliberate tab press, fired before the
                    // callback so the tick lands with the press, not after navigation.
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(IndicatorShape)
                .background(indicator, IndicatorShape)
                .padding(horizontal = 16.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The outline and filled drawables are different vectors; the crossfade keeps
            // the swap in step with the indicator's colour instead of blinking in one frame.
            Crossfade(
                targetState = selected,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "nav-icon-fill",
            ) { isFilled ->
                Symbol(
                    name = tab.symbol,
                    contentDescription = null,
                    tint = iconTint,
                    size = 22.dp,
                    filled = isFilled,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // The visible label doubles as the tab's accessible name — TalkBack merges it with
        // the `selectable` role into "<label>, tab, selected".
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
