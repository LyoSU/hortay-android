package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.theme.rememberPressedSelectedCornerRadius

/**
 * Top-level scope a feed item belongs to. Mirrors how Telegram itself splits chats: the
 * main timeline, any custom folder the user has, plus the dedicated archive list.
 */
@Immutable
sealed interface FilterScope {
    data object All : FilterScope
    data class Folder(val id: Int, val title: String) : FilterScope
    data object Archive : FilterScope
}

/**
 * Horizontally-scrollable strip of M3 Expressive folder chips. Each chip is a
 * stadium-shape (`RoundedCornerShape`) that morphs corner radius from 16 dp at rest
 * to 28 dp on selection — the canonical M3 Expressive `FilterChip` motion. Cookie /
 * Burst polygons were considered but rejected: those shapes are designed for 1:1
 * elements (FAB, IconButton, avatar) and non-uniformly stretch their character
 * ridges into ovals when applied to wide chips with variable-width text. Stadium
 * with corner-radius morph reads as expressive without distorting at any aspect.
 *
 * Telegram folders carry their own emoji inside the name's FormattedText, so we
 * render the plain text without re-decorating — the official client does the same
 * in its compact-chip variant.
 */
@Composable
fun FoldersBar(
    selected: FilterScope,
    folders: List<FolderTab>,
    showArchive: Boolean,
    onSelected: (FilterScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FolderChip(
            label = stringResource(R.string.folder_all),
            selected = selected is FilterScope.All,
            onClick = { onSelected(FilterScope.All) },
        )
        folders.forEach { tab ->
            val isSelected = (selected as? FilterScope.Folder)?.id == tab.id
            FolderChip(
                label = tab.title,
                selected = isSelected,
                onClick = { onSelected(FilterScope.Folder(tab.id, tab.title)) },
            )
        }
        if (showArchive) {
            FolderChip(
                label = stringResource(R.string.folder_archive),
                selected = selected is FilterScope.Archive,
                onClick = { onSelected(FilterScope.Archive) },
            )
        }
    }
}

@Immutable
data class FolderTab(val id: Int, val title: String)

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Three-state corner-radius morph — canonical M3 Expressive FilterChip /
    // ButtonGroup vocabulary. 16 dp rest → 8 dp pressed (squish) → 28 dp selected (pill).
    // ButtonGroupDefaults.PressedShape in the 1.5 source is exactly this 8 dp corner.
    val interactionSource = remember { MutableInteractionSource() }
    val cornerRadius by rememberPressedSelectedCornerRadius(
        interactionSource = interactionSource,
        selected = selected,
        rest = 16.dp,
        pressed = 8.dp,
        selectedRadius = 28.dp,
        label = "folder-corner",
    )
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder-bg",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder-fg",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(container, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}
