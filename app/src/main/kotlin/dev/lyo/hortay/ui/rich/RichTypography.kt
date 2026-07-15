package dev.lyo.hortay.ui.rich

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.ui.theme.BodyFontFamily
import dev.lyo.hortay.ui.theme.DisplayFontFamily

/**
 * A dedicated, theme-stable type scale for the rich-message reader — deliberately NOT an alias
 * of `MaterialTheme.typography`. A rich document must read as one editorial article ("a small
 * native article inside a Telegram post"), which needs a heading ladder and paragraph measure
 * pinned to fixed points rather than borrowing the feed-card scale (which is tuned so the
 * sender name out-weighs the body — the opposite hierarchy from long-form reading).
 *
 * Families reuse the app's bundled variable fonts ([DisplayFontFamily] = Plus Jakarta Sans for
 * headings, [BodyFontFamily] = Inter for body / footers); only the sizes, weights and
 * line-heights are pinned here. Colours are NOT baked in — every renderer copies the palette
 * colour from the theme at the call site, so the scale stays theme-independent (usable from
 * unit tests and previews without a `MaterialTheme`).
 */
internal object RichType {
    /** `pageBlockSectionHeading` size 1 — the document title. */
    val h1 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp)
    val h2 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp)
    val h3 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val h4 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    val h5 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val h6 = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)

    /** Body paragraph — the reading measure. */
    val paragraph = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp)

    /** Footer / caption-credit — muted, small. Colour applied by the renderer. */
    val footer = TextStyle(fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

    /** Monospace code body inside `pageBlockPreformatted` / `pageBlockMathematicalExpression`. */
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
}

/**
 * Type style for a `pageBlockSectionHeading` of TDLib [size] (1 = largest .. 6 = smallest),
 * clamped to the [RichType] heading ladder.
 */
internal fun richHeadingStyle(size: Int): TextStyle = when (size.coerceIn(1, 6)) {
    1 -> RichType.h1
    2 -> RichType.h2
    3 -> RichType.h3
    4 -> RichType.h4
    5 -> RichType.h5
    else -> RichType.h6
}

/** Fallback sibling gap when no asymmetric rule applies — the paragraph rhythm. */
internal val RICH_BLOCK_GAP: Dp = 12.dp

/**
 * Asymmetric vertical rhythm between two sibling top-level blocks — a PURE function so the
 * spacing contract is unit-testable ([dev.lyo.hortay.ui.rich.RichBlockSpacingTest]) and free of
 * a `MaterialTheme`.
 *
 * The design goal is that a heading visually "belongs" to what follows it: generous air BEFORE
 * a heading (it opens a new section) and tight space AFTER one (it binds to its first
 * paragraph). Rules, in priority order:
 *  1. after a heading → 8 dp (the heading hugs the block it introduces);
 *  2. before a heading → 22 dp (a new section gets breathing room above its title);
 *  3. a large-section boundary (media / table / details / quote adjacent to anything) → 16 dp;
 *  4. everything else (paragraph ↔ paragraph and text-ish siblings) → 12 dp ([RICH_BLOCK_GAP]).
 *
 * Only ever called BETWEEN two siblings, so the first block has no leading gap and the last no
 * trailing one — outer padding is structurally absent.
 */
internal fun blockSpacingBetween(above: RichBlock, below: RichBlock): Dp = when {
    above is RichBlock.SectionHeading -> 8.dp
    below is RichBlock.SectionHeading -> 22.dp
    above.isLargeSection() || below.isLargeSection() -> 16.dp
    else -> RICH_BLOCK_GAP
}

/**
 * A "large section" — a block that reads as its own visual unit (media, a table, a collapsible
 * details section, or a quote) and therefore earns a wider gap from adjacent text.
 */
private fun RichBlock.isLargeSection(): Boolean = when (this) {
    is RichBlock.Photo,
    is RichBlock.Video,
    is RichBlock.Animation,
    is RichBlock.Audio,
    is RichBlock.VoiceNote,
    is RichBlock.Collage,
    is RichBlock.Slideshow,
    is RichBlock.MapPreview,
    is RichBlock.Table,
    is RichBlock.Details,
    is RichBlock.BlockQuote,
    is RichBlock.PullQuote,
    -> true
    else -> false
}
