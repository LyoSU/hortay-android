@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Two-row [SegmentedListItem] group for the feed-order preference. Selection is
 * shown by tinting the active row's leading icon — no [androidx.compose.material3.RadioButton]
 * on the trailing slot, which would import additional metaphor (mutually-exclusive
 * choices in a dialog) on top of an already-affordant row pair. Matches the rest of
 * the settings vocabulary where rows are tappable surfaces, not radio-style picks.
 */
@Composable
internal fun FeedOrderRows(
    current: FeedOrder,
    onSelect: (FeedOrder) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        FeedOrderRow(
            symbol = "arrow_upward",
            title = stringResource(R.string.settings_feed_order_oldest_title),
            subtitle = stringResource(R.string.settings_feed_order_oldest_subtitle),
            isSelected = current == FeedOrder.OldestUnreadFirst,
            index = 0,
            count = 2,
            onClick = { onSelect(FeedOrder.OldestUnreadFirst) },
        )
        FeedOrderRow(
            symbol = "arrow_downward",
            title = stringResource(R.string.settings_feed_order_newest_title),
            subtitle = stringResource(R.string.settings_feed_order_newest_subtitle),
            isSelected = current == FeedOrder.Newest,
            index = 1,
            count = 2,
            onClick = { onSelect(FeedOrder.Newest) },
        )
    }
}

@Composable
private fun FeedOrderRow(
    symbol: String,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    val leadingTint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "feed-order-tint",
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(name = symbol, tint = leadingTint, size = 22.dp)
            }
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = null,
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for the snap-scroll toggle. Independent of
 * [FeedOrderRows] in the layout — snap is a presentation mode (how fling
 * behaves) orthogonal to ordering (what's shown). Single-row segment shape
 * (`segmentedShapes(0, 1)`) so it reads as its own card.
 */
@Composable
internal fun SnapScrollRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "play_circle",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_snap_scroll_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_snap_scroll_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for the inline-video-autoplay toggle. Independent
 * of [SnapScrollRow] in the layout — autoplay is a media-playback policy
 * orthogonal to how the list scrolls. Single-row segment so it reads as its own
 * card.
 *
 * Note: autoplay is also gated by "is the file already on disk?" — toggling this
 * row on doesn't override the user's [dev.lyo.hortay.data.AutoDownloadStore] policy;
 * videos that weren't pulled by auto-download still show their static poster +
 * play badge until the user opens them. The row's subtitle calls this out so users
 * don't think the toggle is broken when their videos sit still on a roaming plan.
 */
@Composable
internal fun InlineAutoplayRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "smart_display",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_inline_autoplay_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_inline_autoplay_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for invisible-reading mode. When ON, the user
 * is not presented as online to their Telegram contacts while reading Hortay —
 * [dev.lyo.hortay.data.TdLifecycleBridge] omits the `SetOption("online", true)`
 * from its foreground activation step, which is the single signal TDLib uses to
 * drive `account.updateStatus` (per Aliaksei Levin in `tdlib/td#3144`). Independent
 * of [SnapScrollRow] and [InlineAutoplayRow] in the layout — this is a privacy
 * concern orthogonal to feed presentation — so it sits in its own single-row
 * segment (`segmentedShapes(0, 1)`) inside the Privacy section.
 *
 * The icon flips between `visibility_off` (ON — actively hiding) and
 * `visibility` (OFF — showing as online) so a quick glance at the row tells
 * the user the current state without reading the title.
 */
@Composable
internal fun HideOnlineStatusRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = if (enabled) "visibility_off" else "visibility",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_hide_online_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_hide_online_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for the dynamic-colour preference. ON =
 * Material You wallpaper-derived palette; OFF = the fixed Hortay brand
 * (periwinkle) scheme. Only rendered on Android 12+ — the caller gates the
 * whole row behind `Build.VERSION.SDK_INT >= S`, because below that the
 * platform has no dynamic-colour API and the brand scheme is the only option,
 * so a toggle would be a dead control.
 *
 * The `palette` glyph fills (onPrimaryContainer over primaryContainer) when ON
 * so a glance reads the active state, matching [SnapScrollRow] /
 * [InlineAutoplayRow]. Single-row segment (`segmentedShapes(0, 1)`) — it sits
 * alone in the Appearance section.
 */
@Composable
internal fun DynamicColorRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "palette",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_dynamic_color_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_dynamic_color_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
