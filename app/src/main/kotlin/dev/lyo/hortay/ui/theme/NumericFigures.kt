package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.TextStyle

/**
 * Single source of truth for the **tabular-figures** feed-feedback doctrine rule 3
 * ("nothing jumps under the finger"). Any counter that updates live — view counts,
 * comment/forward counts, reaction counts, unread badges, the "N new posts" number —
 * must use a fixed-advance numeric glyph so a `9 → 10` tick does not re-lay-out its
 * neighbours.
 *
 * Inter and Plus Jakarta Sans both ship the OpenType `tnum` feature; opting in here is
 * a zero-cost width lock — proportional figures stay the default everywhere else (body
 * copy reads better with proportional digits).
 *
 * Usage at the call site:
 * ```
 * Text(
 *     text = formatViews(count),
 *     style = MaterialTheme.typography.labelMedium.tabularFigures(),
 * )
 * ```
 *
 * Applied to a `TextStyle` rather than offered as a standalone composable so it composes
 * with the existing per-call-site style (`labelMedium`, `labelSmall`, badge styles) with
 * no extra layout node.
 */
fun TextStyle.tabularFigures(): TextStyle =
    copy(fontFeatureSettings = listOfNotNull(fontFeatureSettings, "tnum").joinToString(", "))
