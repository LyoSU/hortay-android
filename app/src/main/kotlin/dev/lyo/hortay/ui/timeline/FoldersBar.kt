package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape

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
 * Horizontally-scrollable strip of M3 Expressive folder chips. Each chip morphs from
 * `Square` (rest) to `Cookie7Sided` (selected) on the spatial spring spec. Cookie7 was
 * picked specifically for the folder row because:
 *   1. Different polygon than the reaction chip's Cookie9 — visually distinct ridges
 *      so the eye reads "filter active" vs "reaction active" as separate idioms even
 *      when both surfaces are on screen.
 *   2. Asymmetric (odd side count) — feels organic and "tab-like", not a perfect
 *      gear that would compete with iconography elsewhere on the chrome.
 *
 * Telegram folders carry their own emoji inside the name's FormattedText, so we render
 * the plain text without re-decorating — the official client does the same in its
 * compact-chip variant.
 */
@Composable
fun FoldersBar(
    selected: FilterScope,
    folders: List<FolderTab>,
    showArchive: Boolean,
    onSelected: (FilterScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
    val morphProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "folder-morph",
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
    val shape = MorphShape(HortayExpressive.FolderMorph, morphProgress)
    // Polygon clips both the visual silhouette and the click ripple — single
    // expressive cue. Chip is sized via padding so the label sits in the
    // polygon's inscribed area (Cookie7's narrow vertical dimension is ~85% of
    // bounding height, label height ~20 dp inside 40 dp chip clears comfortably).
    Row(
        modifier = Modifier
            .clip(shape)
            .background(container, shape)
            .clickable(onClick = onClick)
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
