package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val haptics = LocalHapticFeedback.current
    val cornerRadius by rememberPressedSelectedCornerRadius(
        interactionSource = interactionSource,
        selected = selected,
        rest = 16.dp,
        pressed = 8.dp,
        selectedRadius = 28.dp,
        label = "folder-corner",
    )
    // Fill discipline: only the SELECTED chip carries a tonal fill. Resting chips
    // are bare text — a `surfaceContainerLow` pill under every folder made the
    // strip read as a row of buttons competing with the feed below, and on the
    // lavender canvas the rest/selected values were nearly indistinguishable.
    // Bare-at-rest / tonal-when-active matches the floating navbar's vocabulary,
    // so the two chrome strips bracket the feed with one consistent rule.
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
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
                indication = LocalIndication.current,
                onClick = {
                    // J2: light selection tick when switching TO a different tab —
                    // re-tapping the active chip stays silent (no scope change).
                    if (!selected) {
                        haptics.performHapticFeedback(
                            HapticFeedbackType.ContextClick,
                        )
                    }
                    onClick()
                },
            )
            // 14×9 keeps the chip at a compact ~36 dp visual height (the row's
            // 8 dp vertical padding still lands the touch target near 48 dp) —
            // the previous 18×12 read as full-size buttons rather than filters.
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // B4: emoji-only folder labels (Telegram folders carry their own emoji in
        // the name) otherwise render taller than text labels — the emoji glyph's
        // intrinsic line-height exceeds the text cap-to-baseline box, so a 📢 chip
        // sat noticeably taller than "Усі". Clamping to a FIXED lineHeight + trimmed
        // line-height edges + no font padding pins every chip to the same content
        // height regardless of whether the label is text, emoji, or a mix.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                lineHeight = 20.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = content,
            maxLines = 1,
        )
    }
}
