package dev.lyo.hortay.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape

/**
 * MD3 Expressive floating navigation bar.
 *
 * A pill-shaped Surface that hovers over the content with horizontal + bottom margin,
 * tonal elevation and a soft shadow. Active item shape morphs from `Square` to
 * `Cookie7Sided` on selection (the same morph the folder filter uses, intentionally —
 * a single "active state" gesture across the navigational chrome reads as one
 * visual idiom).
 *
 * The morph runs on `motionScheme.fastSpatialSpec()` (spring-based, low damping) so
 * the active indicator overshoots slightly — the canonical expressive feedback for
 * "you just changed mode". Color crossfades on `fastEffectsSpec()` separately because
 * spatial vs effects animations have intentionally different physics in M3 Expressive.
 *
 * Material's `HorizontalFloatingToolbar` was considered but lays out around a single
 * primary FAB plus secondary actions — wrong primitive for a 4-equal-tab bottom nav.
 * `NavigationBar` was rejected because it docks to the screen edge and breaks the
 * "floating chrome over scroll content" feel that's now Hortay's signature.
 */
@Composable
fun FloatingNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
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
    }
}

@Composable
private fun NavTabButton(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Shape morph from Square (rest) -> Cookie7Sided (selected). Spatial spring keeps
    // the morph feeling "alive" — visible bounce on the leading edge of the transition,
    // settled before the user taps the next tab.
    val morphProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "nav-morph",
    )
    // Color crossfade is intentionally a separate animation channel: the M3 Expressive
    // motion scheme distinguishes spatial (size/shape/offset) and effects (color/alpha)
    // because mixing physics is what makes Material 2 transitions feel cheap.
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

    val shape = MorphShape(HortayExpressive.FolderMorph, morphProgress)

    // Clip + background both follow the polygon — ripple now hugs the same Cookie
    // silhouette the user sees, the canonical M3 Expressive feedback. The 24 dp
    // icon at centre lives well inside Cookie7's inscribed circle (~85% of the
    // bounding box), so the indentations don't reach the glyph.
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(container, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Filled variant on the selected tab — canonical M3 Expressive cue: the
        // active destination is heavier than its peers, mirroring how the polygon
        // morph and tonal container also intensify together. `filled` falls back
        // to the outline when no `_filled` drawable is bundled (see Symbol.kt).
        Symbol(
            name = tab.symbol,
            contentDescription = stringResource(tab.labelRes),
            tint = content,
            size = 24.dp,
            filled = selected,
        )
    }
}
