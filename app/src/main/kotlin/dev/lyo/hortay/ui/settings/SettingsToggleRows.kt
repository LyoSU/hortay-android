@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import dev.lyo.hortay.data.QUICK_REACTION_CHOICES
import dev.lyo.hortay.ui.timeline.ReactionEmojiGlyph
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.EmojiStyle
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadingTextSize
import dev.lyo.hortay.data.ThemeMode
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.theme.scaledForReading
import kotlinx.collections.immutable.persistentListOf

/**
 * Feed-order preference as one card: a two-option segmented control with an icon per
 * option and, below it, a one-line explanation of the selected option. The previous
 * pair of rows marked the choice only by tinting a small leading icon, which on device
 * read as two identical rows — the segmented control makes the active choice obvious
 * and switching it one tap.
 */
@Composable
internal fun FeedOrderRows(
    current: FeedOrder,
    onSelect: (FeedOrder) -> Unit,
) {
    val oldestLabel = stringResource(R.string.settings_feed_order_oldest_title)
    val newestLabel = stringResource(R.string.settings_feed_order_newest_title)
    val options = remember(oldestLabel, newestLabel) {
        persistentListOf(
            SettingsChoice(FeedOrder.OldestUnreadFirst, oldestLabel, symbol = "arrow_upward"),
            SettingsChoice(FeedOrder.Newest, newestLabel, symbol = "arrow_downward"),
        )
    }
    Surface(
        shape = SettingsGroupInnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            SettingsSingleChoice(options = options, selected = current, onSelect = onSelect)
            Text(
                text = stringResource(
                    if (current == FeedOrder.Newest) R.string.settings_feed_order_newest_subtitle
                    else R.string.settings_feed_order_oldest_subtitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
            )
        }
    }
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
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "play_circle", active = enabled)
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
 * Standalone [SegmentedListItem] for "show archived channels in All"
 * ([dev.lyo.hortay.data.SettingsStore.archiveInAllFeed]). A feed-scope choice,
 * independent of how the list scrolls or plays media, so its own single-row card.
 */
@Composable
internal fun ArchiveInAllFeedRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "dynamic_feed", active = enabled)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_archive_in_all_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_archive_in_all_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Settings → Feed row for [dev.lyo.hortay.data.SettingsStore.quickReaction]: the switch turns
 * the double-tap gesture on and off, and while it is on the row offers the reaction it
 * leaves. [current] null = off. Turning it back on starts from the default reaction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickReactionRow(
    current: String?,
    onEnabledChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val enabled = current != null
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onEnabledChange(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "favorite", active = enabled)
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.settings_quick_reaction_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (enabled) {
                    FlowRow(
                        modifier = Modifier.padding(top = HortaySpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(HortaySpacing.xs),
                        maxItemsInEachRow = QUICK_REACTION_CHOICES.size / 2,
                    ) {
                        QUICK_REACTION_CHOICES.forEach { emoji ->
                            val selected = emoji == current
                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent,
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { if (!selected) onPick(emoji) },
                                    )
                                    // The cell grows with the glyph: at font scale 2.0 a fixed
                                    // 44 dp circle left the emoji touching each other.
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReactionEmojiGlyph(emoji = emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        },
        content = {
            Text(
                text = stringResource(R.string.settings_quick_reaction_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/** Settings → Feed toggle for [dev.lyo.hortay.data.SettingsStore.groupFeed]. */
@Composable
internal fun GroupFeedRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "forward", active = enabled)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_group_feed_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_group_feed_title),
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
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "smart_display", active = enabled)
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
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = if (enabled) "visibility_off" else "visibility", active = enabled)
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
 * The `palette` glyph tints `primary` when ON so a glance reads the active state,
 * matching [SnapScrollRow] / [InlineAutoplayRow]. Single-row segment
 * (`segmentedShapes(0, 1)`) — its own card in the Appearance section.
 */
@Composable
internal fun DynamicColorRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "palette", active = enabled)
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

/**
 * Theme-mode preference ([ThemeMode]) in the same card idiom as [FeedOrderRows]: a segmented
 * control (System / Light / Dark) with a one-line explanation of the selected option below it.
 * A segment tick haptic acknowledges an actual change, as on the feed-order switch.
 */
@Composable
internal fun ThemeModeRows(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val systemLabel = stringResource(R.string.settings_theme_mode_system)
    val lightLabel = stringResource(R.string.settings_theme_mode_light)
    val darkLabel = stringResource(R.string.settings_theme_mode_dark)
    val options = remember(systemLabel, lightLabel, darkLabel) {
        persistentListOf(
            SettingsChoice(ThemeMode.System, systemLabel),
            SettingsChoice(ThemeMode.Light, lightLabel),
            SettingsChoice(ThemeMode.Dark, darkLabel),
        )
    }
    Surface(
        shape = SettingsGroupInnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(HortaySpacing.md)) {
            SettingsSingleChoice(options = options, selected = current, onSelect = onSelect)
            Text(
                text = stringResource(
                    when (current) {
                        ThemeMode.System -> R.string.settings_theme_mode_system_subtitle
                        ThemeMode.Light -> R.string.settings_theme_mode_light_subtitle
                        ThemeMode.Dark -> R.string.settings_theme_mode_dark_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HortaySpacing.xs, end = HortaySpacing.xs, top = 10.dp),
            )
        }
    }
}

/**
 * Reaction emoji style ([EmojiStyle]) in the same card idiom as [ThemeModeRows]: a title, a
 * System / Telegram segmented control, and a one-line explanation. TDLib mode only — guest mode
 * has no session to resolve Telegram's artwork from, so the caller hides the card there.
 */
@Composable
internal fun EmojiStyleRows(
    current: EmojiStyle,
    onSelect: (EmojiStyle) -> Unit,
) {
    val systemLabel = stringResource(R.string.settings_emoji_style_system)
    val telegramLabel = stringResource(R.string.settings_emoji_style_telegram)
    val options = remember(systemLabel, telegramLabel) {
        persistentListOf(
            SettingsChoice(EmojiStyle.System, systemLabel),
            SettingsChoice(EmojiStyle.Telegram, telegramLabel),
        )
    }
    Surface(
        shape = SettingsGroupInnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(HortaySpacing.md)) {
            Text(
                text = stringResource(R.string.settings_emoji_style_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = HortaySpacing.xs, end = HortaySpacing.xs, bottom = 10.dp),
            )
            SettingsSingleChoice(options = options, selected = current, onSelect = onSelect)
            Text(
                text = stringResource(R.string.settings_emoji_style_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HortaySpacing.xs, end = HortaySpacing.xs, top = 10.dp),
            )
        }
    }
}

/**
 * Pure-black dark theme ([dev.lyo.hortay.data.SettingsStore.setPureBlack]). The caller shows it
 * only while the app is rendering dark — in a light theme it would be a dead control. Same single-row card shape as the other toggles.
 */
@Composable
internal fun PureBlackRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "smartphone", active = enabled)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_pure_black_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_pure_black_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Reading text size ([ReadingTextSize]) as one card: a title with the selected size's name, a
 * four-step segmented control labelled with a letter drawn at each step's size (four localized
 * names would not fit one row on a phone, so each segment carries its name as its accessibility
 * label instead), and a live sample line set in the post-body style at the selected scale — the
 * user sees the result before leaving Settings. Same card + segmented idiom as [FeedOrderRows].
 */
@Composable
internal fun TextSizeRows(
    current: ReadingTextSize,
    onSelect: (ReadingTextSize) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val glyph = stringResource(R.string.settings_text_size_glyph)
    Surface(
        shape = SettingsGroupInnerShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(HortaySpacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HortaySpacing.xs, end = HortaySpacing.xs, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Title takes the slack and wraps, so at large font scale the selected size's name
                // keeps its own space at the end instead of being squeezed off the row.
                Text(
                    text = stringResource(R.string.settings_text_size_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(current.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = HortaySpacing.sm),
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReadingTextSize.entries.forEachIndexed { i, size ->
                    val name = stringResource(size.labelRes())
                    SegmentedButton(
                        selected = current == size,
                        onClick = {
                            if (current != size) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(size)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, ReadingTextSize.entries.size),
                        // No check icon: the letter's size IS the label, and the icon would
                        // squeeze four segments on a phone.
                        icon = {},
                        label = {
                            Text(
                                text = glyph,
                                style = bodyStyle.scaledForReading(size.scale),
                                maxLines = 1,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = name },
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_text_size_sample),
                style = bodyStyle.scaledForReading(current.scale),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = HortaySpacing.xs, end = HortaySpacing.xs, top = HortaySpacing.md),
            )
        }
    }
}

private fun ReadingTextSize.labelRes(): Int = when (this) {
    ReadingTextSize.Small -> R.string.settings_text_size_small
    ReadingTextSize.Default -> R.string.settings_text_size_default
    ReadingTextSize.Large -> R.string.settings_text_size_large
    ReadingTextSize.ExtraLarge -> R.string.settings_text_size_extra_large
}

/**
 * Guest mode: whether the posts read here count in the channels' view counters
 * ([dev.lyo.hortay.data.SettingsStore.countWebViews]). The subtitle says plainly what it
 * costs — Telegram learns which posts were read.
 */
@Composable
internal fun CountWebViewsRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = settingsGroupedShapes()
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "visibility", active = enabled)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_count_web_views_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_count_web_views_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
