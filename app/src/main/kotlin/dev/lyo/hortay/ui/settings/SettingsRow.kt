@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    grouped: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shapes = if (grouped) {
        settingsGroupedShapes()
    } else {
        ListItemDefaults.segmentedShapes(
            index = index,
            count = count,
            defaultShapes = ListItemDefaults.shapes(),
        )
    }
    SegmentedListItem(
        onClick = onClick ?: {},
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = symbol, danger = tint == MaterialTheme.colorScheme.error)
        },
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
 * One Settings section as a single block: the rows sit [ListItemDefaults.SegmentedGap] apart and
 * the block's outline rounds only the outer corners, so toggles, choice cards and drill-in rows
 * of one section read as one grouped surface instead of a mix of cards and bare rows.
 *
 * The outline is a clip on the block, not per-row corner maths: sections are built from
 * mode-conditional rows (guest vs TDLib, API level, dark theme), and an (index, count) pair
 * would have to be recomputed for every combination. Rows inside pass `grouped = true` (or use
 * [settingsGroupedShapes] / [SettingsGroupInnerShape]) so their own corners stay small.
 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(SettingsGroupShape),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}

/** Outer outline of a [SettingsGroup]. */
internal val SettingsGroupShape = RoundedCornerShape(24.dp)

/** Corner of a non-list block (choice card) inside a [SettingsGroup] — matches the rows' seams. */
internal val SettingsGroupInnerShape = RoundedCornerShape(4.dp)

/** Row shapes inside a [SettingsGroup]: the segmented "middle" shape, whatever the position. */
@Composable
internal fun settingsGroupedShapes() = ListItemDefaults.segmentedShapes(
    index = 1,
    count = 3,
    defaultShapes = ListItemDefaults.shapes(),
)

/** Every Settings row is a tile on the page, so a section is visibly a group of surfaces. */
@Composable
internal fun settingsRowColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
)

/**
 * Leading glyph of a Settings row in a round tonal tile. [active] lights the tile for a switch
 * that is on, so the state reads at a glance down the icon column; [danger] is the sign-out row.
 */
@Composable
internal fun SettingsIconTile(
    symbol: String,
    active: Boolean = false,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        danger -> scheme.errorContainer
        active -> scheme.primaryContainer
        else -> scheme.surfaceContainerHighest
    }
    val content = when {
        danger -> scheme.onErrorContainer
        active -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(40.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(name = symbol, tint = content, size = 22.dp)
    }
}

/**
 * M3E grouped-list section header. `labelLarge` SemiBold in `onSurfaceVariant`
 * reads as a quiet list-section delimiter on the clean canvas. Primary tint was
 * dropped deliberately: on a near-white background a coloured header registers as
 * a tappable link, and primary is reserved for interactive text (doctrine §5 —
 * colour semantics). Padding lifts the label off the row below so each section
 * reads as its own block.
 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * Title slot for a Settings [dev.lyo.hortay.ui.components.HortayTopBar] built through its slot
 * overload. Same single-line treatment as the bar's own String title.
 */
@Composable
internal fun SettingsTopBarTitle(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Subtitle slot for a Settings [dev.lyo.hortay.ui.components.HortayTopBar]. Unlike the bar's
 * String subtitle (pinned to one line) it may wrap to a second line, so a long locale string at
 * font scale 2.0 stays readable instead of ending in an ellipsis; the flexible large bar grows
 * its expanded height to fit, and a subtitle that fits one line lays out exactly as before.
 */
@Composable
internal fun SettingsTopBarSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
