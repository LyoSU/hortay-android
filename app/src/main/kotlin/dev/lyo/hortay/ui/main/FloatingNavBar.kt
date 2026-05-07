package dev.lyo.hortay.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol

/**
 * MD3 Expressive floating navigation bar.
 *
 * A pill-shaped Surface that hovers over the content with horizontal + bottom margin,
 * tonal elevation and a soft shadow. Each tab is a stadium with the canonical M3
 * Expressive **three-state corner-radius morph** — the same vocabulary as the folder
 * filter chips and the reaction chips, so all three "selectable affordance" surfaces
 * speak one motion language:
 *
 *   - Rest: 14 dp corners (soft rounded square).
 *   - Pressed: 6 dp corners (compressed-under-thumb tactile cue).
 *   - Selected: 24 dp corners (fully rounded pill).
 *
 * Polygon (Cookie7) morph was tried first but Cookie/Burst polygons are designed for
 * 1:1 elements — at the nav-tab's slight horizontal aspect they almost-fit, but the
 * inconsistency with FolderChip's stadium pattern read as two different idioms for
 * the same "active mode" cue. Stadium with corner morph stays canonical at any
 * aspect and matches the rest of the app.
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Three-state corner-radius morph — canonical M3 Expressive vocabulary.
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isPressed -> 6.dp
            selected -> 24.dp
            else -> 14.dp
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
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
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Filled variant on the selected tab — canonical M3 Expressive cue: the
        // active destination is heavier than its peers, mirroring how the
        // corner-radius morph and tonal container also intensify together.
        // `filled` falls back to the outline when no `_filled` drawable is
        // bundled (see Symbol.kt).
        Symbol(
            name = tab.symbol,
            contentDescription = stringResource(tab.labelRes),
            tint = content,
            size = 24.dp,
            filled = selected,
        )
    }
}
