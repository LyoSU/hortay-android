package dev.lyo.hortay.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Multiplier for READING text only — post bodies (feed, channel, comments) and rich-article
 * paragraphs / headings. Chrome, labels and counters never read it. Provided by [HortayTheme]
 * from [dev.lyo.hortay.data.ReadingTextSize.scale]; `1f` outside the theme (previews, tests).
 *
 * Static local: it changes only when the user picks another size in Settings, and then every
 * reader of it SHOULD recompose — a dynamic local would only add per-read tracking cost on the
 * hottest composable in the app (the post body).
 */
val LocalReadingScale = staticCompositionLocalOf { 1f }

/**
 * [this] with font size and line height multiplied by [scale] together, so the line rhythm
 * stays proportional. Unspecified units are left alone (multiplying `TextUnit.Unspecified`
 * throws), and `1f` returns the same instance so the default size allocates nothing.
 */
fun TextStyle.scaledForReading(scale: Float): TextStyle {
    if (scale == 1f) return this
    return copy(
        fontSize = fontSize.scaledBy(scale),
        lineHeight = lineHeight.scaledBy(scale),
    )
}

private fun TextUnit.scaledBy(scale: Float): TextUnit = if (isSpecified) this * scale else this

/**
 * Re-provides the theme's typography with `bodyLarge` — the post-body style every reading
 * block (plain text, media captions, the rich feed preview's clamp) resolves — scaled by
 * [LocalReadingScale]. Wrap ONLY reading content in it: everything else inside keeps the
 * stock scale because only `bodyLarge` changes, and [LocalTextStyle] is restored to the outer
 * value so un-styled `Text`s inside don't silently grow.
 *
 * A no-op at the default size, so the common path adds no provider at all.
 */
@Composable
fun ProvideReadingTypography(content: @Composable () -> Unit) {
    val scale = LocalReadingScale.current
    if (scale == 1f) {
        content()
        return
    }
    val typography = MaterialTheme.typography
    val outerTextStyle = LocalTextStyle.current
    val scaled = remember(typography, scale) {
        typography.copy(bodyLarge = typography.bodyLarge.scaledForReading(scale))
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = scaled,
    ) {
        CompositionLocalProvider(LocalTextStyle provides outerTextStyle, content = content)
    }
}
