package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.R

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
 * Horizontally-scrollable strip of text tabs with an accent underline under the selected
 * one — the Threads / Bluesky / X feed-switcher idiom. A filled pill per folder read as a
 * row of buttons competing with the posts below; plain labels with one underline keep the
 * chrome quiet and make the active scope obvious.
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
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
    val haptics = LocalHapticFeedback.current
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder-fg",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder-indicator",
    )
    val indicatorColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    // J2: light selection tick when switching TO a different tab —
                    // re-tapping the active one stays silent (no scope change).
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // B4: emoji-only folder labels (Telegram folders carry their own emoji in
        // the name) otherwise render taller than text labels — the emoji glyph's
        // intrinsic line-height exceeds the text cap-to-baseline box. A FIXED
        // lineHeight + trimmed line-height edges + no font padding pins every tab to
        // the same content height regardless of whether the label is text or emoji.
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(
                lineHeight = 20.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(indicatorColor.copy(alpha = indicatorAlpha)),
        )
    }
}
