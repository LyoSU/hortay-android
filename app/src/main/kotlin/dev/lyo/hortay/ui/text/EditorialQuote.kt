package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The ONE editorial block-quote container shared by regular posts ([dev.lyo.hortay.ui.text]'s
 * `BlockBox`) and rich messages ([dev.lyo.hortay.ui.rich]'s `RichBlockQuote`). It owns the whole
 * visual identity of a quote — a very light accent tint, a rounded-cap accent bar down the start
 * edge, a rounded container, and the vertical arrangement of body + optional credit byline — and
 * NOTHING else: collapse-with-chevron (regular posts) and nested block recursion (rich) stay with
 * their callers, layered in via [onClick] / [overlay] / [content].
 *
 * Telegram's editorial idiom: no border, no elevation, NO decorative quote glyph. [depth] rotates
 * the accent through the tonal palette (see [quoteAccentRole]) so a quote-inside-a-quote reads as a
 * new layer rather than another identical frame; regular-post quotes have no nesting concept and
 * take the default top-level shade.
 *
 * The frame hugs its content unless the caller's [modifier] widens it (rich passes `fillMaxWidth`;
 * a regular post quote hugs so a short quote reads as a pulled-in block, not a full-width band).
 */
@Composable
internal fun EditorialQuoteFrame(
    modifier: Modifier = Modifier,
    depth: Int = 0,
    contentPadding: PaddingValues = PaddingValues(EDITORIAL_QUOTE_PADDING),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    credit: (@Composable () -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = when (quoteAccentRole(depth)) {
        QuoteAccentRole.Primary -> MaterialTheme.colorScheme.primary
        QuoteAccentRole.Tertiary -> MaterialTheme.colorScheme.tertiary
        QuoteAccentRole.Secondary -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(EDITORIAL_QUOTE_RADIUS))
            .background(accent.copy(alpha = 0.06f))
            .drawBehind {
                val barWidth = EDITORIAL_QUOTE_BAR.toPx()
                drawRoundRect(
                    color = accent,
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
            // Applied INSIDE the clip so the toggle ripple follows the rounded silhouette. Null for
            // rich quotes (they don't collapse) — only a collapsible regular-post quote wires it.
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
            credit?.invoke()
        }
        overlay?.invoke(this)
    }
}

private val EDITORIAL_QUOTE_BAR = 3.dp
private val EDITORIAL_QUOTE_RADIUS = 10.dp

/** Uniform inset inside [EditorialQuoteFrame]. `internal` so a regular post's clamp math
 *  ([dev.lyo.hortay.ui.text] `ClampedPost`) can snap its line cut onto the quote's real text grid. */
internal val EDITORIAL_QUOTE_PADDING = 12.dp

/** Which tonal accent a block quote at [depth] (0 = top-level) paints its bar + tint with.
 *  Nested quotes rotate the hue every level instead of stacking identical accent frames, so a
 *  quote-inside-a-quote reads as a distinct layer; the cycle repeats every three levels, which
 *  caps the visual nesting to three recognisable shades. A PURE function so the mapping is
 *  unit-testable ([dev.lyo.hortay.ui.rich.RichQuoteShadeTest]) without a `MaterialTheme`. */
internal enum class QuoteAccentRole { Primary, Tertiary, Secondary }

internal fun quoteAccentRole(depth: Int): QuoteAccentRole = when (depth.coerceAtLeast(0) % 3) {
    0 -> QuoteAccentRole.Primary
    1 -> QuoteAccentRole.Tertiary
    else -> QuoteAccentRole.Secondary
}
