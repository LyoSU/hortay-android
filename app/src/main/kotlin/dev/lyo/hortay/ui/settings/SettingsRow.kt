@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Single Settings row backed by [SegmentedListItem] (M3 Expressive). Position in a
 * grouped section is communicated through (`index`, `count`) — the M3
 * `ListItemDefaults.segmentedShapes` factory derives the per-row corner radii
 * (single → fully rounded; top/middle/bottom → outer-rounded seam) and the
 * pressed-state morph shape from one source of truth. The previous custom
 * `RowPosition` enum + manual `RoundedCornerShape` hierarchy traded clarity for
 * shape-token drift: every adjustment had to be applied in two places (the
 * shape() builder and the `Arrangement.spacedBy`). The segmented API owns both.
 *
 * Static info rows (no `onClick`) still render through this helper — they pass
 * an empty click lambda so the visual stays consistent with actionable rows;
 * the row is just a no-op when tapped. Rationale: a separate non-clickable
 * code path would diverge over time from the clickable look, and "looks
 * tappable but does nothing" tests the same as TG-Android's version row.
 */
@Composable
internal fun SettingsRow(
    symbol: String,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = false,
    index: Int = 0,
    count: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick ?: {},
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, tint = tint, size = 22.dp) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (chevron) {
            {
                Symbol(
                    name = "chevron_right",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 20.dp,
                )
            }
        } else null,
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        },
    )
}

/**
 * M3E grouped-list section header. titleSmall SemiBold reads as a list-section
 * delimiter rather than a chip-style label; the primary tint keeps the brand
 * accent the original design leaned on. Padding lifts the label off the row
 * below so each section reads as its own block.
 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}
