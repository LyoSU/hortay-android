package dev.lyo.hortay.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.rememberPressedSelectedCornerRadius

/**
 * MD3 Expressive floating navigation bar.
 *
 * Outer container is M3 1.5's `HorizontalFloatingToolbar` — Google's documented
 * description: *"displays navigation and key actions in a Row"*. The same
 * primitive that powers contextual bottom action bars is also the canonical
 * floating container for 3–5 navigation destinations; the variation lives in
 * what you pass into the `content` slot. By delegating the surface to the
 * toolbar primitive we inherit the M3E container shape, default tonal /
 * shadow elevations, content padding, and (free) `FloatingToolbarScrollBehavior`
 * hook for future auto-hide-on-scroll behaviour — without having to maintain
 * a custom Surface + padding + shadow recipe.
 *
 * Per-tab affordance stays bespoke because the design tokens here are not
 * Material defaults: each tab is a stadium with the canonical three-state
 * corner-radius morph (rest 14 dp → pressed 6 dp → selected 24 dp), matching
 * the folder filter chips and the reaction chips. One "selectable affordance"
 * vocabulary across the app. The filled-vs-outline icon swap rides the same
 * `fastEffectsSpec()` spring as the colour transitions so the three motion
 * channels (corner spatial / colour effects / icon-swap effects) land together.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        // Tight content padding: each NavTabButton already supplies its own
        // vertical breathing room (10 dp) plus min-height 48 dp for a comfortable
        // touch target, so the toolbar's default padding would only inflate the
        // bar. Horizontal 8 dp matches the previous custom Surface inset.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        content = {
            NavTab.entries.forEach { tab ->
                NavTabButton(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
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
    // Three-state corner-radius morph — canonical M3 Expressive vocabulary.
    // 14 dp rest → 6 dp pressed (squish) → 24 dp selected (pill).
    val cornerRadius by rememberPressedSelectedCornerRadius(
        interactionSource = interactionSource,
        selected = selected,
        rest = 14.dp,
        pressed = 6.dp,
        selectedRadius = 24.dp,
        label = "nav-corner",
    )
    // Spatial vs effects channels: corner radius is spatial (spring), colour is
    // effects (faster crossfade) — separating the two physics is what makes M3
    // Expressive transitions feel deliberate vs Material 2 tween-soup.
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-bg",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "nav-fg",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(container, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    // Discrete destination switch (or home re-tap → scroll-to-top) —
                    // one ContextClick per deliberate tab press. Fires before the
                    // callback so the tick lands with the press, not after navigation.
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Filled variant on the selected tab — canonical M3 Expressive cue: the
        // active destination is heavier than its peers, mirroring how the
        // corner-radius morph and tonal container also intensify together.
        // `filled` falls back to the outline when no `_filled` drawable is
        // bundled (see Symbol.kt).
        //
        // Crossfade wrapping prevents the "blink" when the user taps a tab: the
        // outline and filled drawables are different vector resources, so without
        // a crossfade the Icon swaps painters in one frame while the corner +
        // colour are still animating — out-of-sync motion read as a glitch.
        // `Crossfade` rides the same `fastEffectsSpec` as the colour animations,
        // so all three (corner spatial, colour effects, icon-swap effects) land
        // together.
        Crossfade(
            targetState = selected,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "nav-icon-fill",
        ) { isFilled ->
            Symbol(
                name = tab.symbol,
                contentDescription = stringResource(tab.labelRes),
                tint = content,
                size = 24.dp,
                filled = isFilled,
            )
        }
    }
}
