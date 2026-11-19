package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.collections.immutable.ImmutableList

/** One option of [SettingsSingleChoice]: the stored [value], its visible [label], an optional leading glyph. */
@Immutable
internal data class SettingsChoice<T>(
    val value: T,
    val label: String,
    val symbol: String? = null,
)

/**
 * Two-to-three-option single choice for Settings (theme mode, feed order, proxy type).
 *
 * Renders as a segmented button row when every label fits one line inside its equal-width
 * segment, and falls back to a vertical radio list when any label would not — at large font
 * scale or with long locale strings (de / uk / ru) a segment otherwise ellipsizes its label
 * ("Сист…" at font scale 2.0). The decision is made before layout by measuring each label
 * in the segment's label style, so it never flips for a frame and does not depend on which
 * option is currently selected: every label is checked with the 18 dp icon slot reserved,
 * because M3 adds the check icon to whichever segment becomes selected and would squeeze it.
 * When the labels fit only without that check icon, the row is kept and the icon is dropped.
 *
 * Both shapes keep one selected state and the same semantics: the segmented row is a
 * selectable group of `Role.RadioButton` segments, the stacked list is a `selectableGroup`
 * of `selectable(role = Role.RadioButton)` rows with a [RadioButton] as the visual marker.
 * A segment-tick haptic acknowledges an actual change in either shape.
 */
@Composable
internal fun <T> SettingsSingleChoice(
    options: ImmutableList<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val select: (T) -> Unit = { value ->
        if (value != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelect(value)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val segmentWidthPx = constraints.maxWidth / options.size.coerceAtLeast(1)
        val widestLabelPx = remember(options, labelStyle, density) {
            options.maxOfOrNull { option ->
                measurer.measure(text = option.label, style = labelStyle, softWrap = false, maxLines = 1).size.width
            } ?: 0
        }
        val paddingPx = with(density) { SEGMENT_PADDING.roundToPx() }
        val iconPx = with(density) { SEGMENT_ICON_SLOT.roundToPx() }
        val hasSymbols = options.any { it.symbol != null }
        val fitsWithIcon = widestLabelPx + paddingPx + iconPx <= segmentWidthPx
        // The check icon is redundant with the selected fill; dropping it keeps short labels
        // ("Системна") in one segmented row on narrow phones instead of the radio list.
        val fitsOneRow = fitsWithIcon || (!hasSymbols && widestLabelPx + paddingPx <= segmentWidthPx)
        if (fitsOneRow) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = selected == option.value,
                        onClick = { select(option.value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        icon = {
                            val symbol = option.symbol
                            if (symbol != null) {
                                Symbol(name = symbol, size = 18.dp)
                            } else if (fitsWithIcon) {
                                SegmentedButtonDefaults.Icon(selected == option.value)
                            }
                        },
                        label = {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                options.forEach { option ->
                    val isSelected = selected == option.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { select(option.value) },
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Same row idiom as the language picker's options (LanguageOption).
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        val symbol = option.symbol
                        if (symbol != null) {
                            Symbol(
                                name = symbol,
                                size = 18.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontal space an M3 [SegmentedButton] spends around its label: 12 dp content padding on
 * each side, plus the 18 dp icon and its 8 dp spacing when an icon is shown. Mirrors the M3
 * segmented-button tokens so the fit check matches what the segment really lays out.
 */
private val SEGMENT_PADDING = (12 + 12).dp
private val SEGMENT_ICON_SLOT = (18 + 8).dp
