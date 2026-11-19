package dev.lyo.hortay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Largest font scale that floating app chrome (bottom navigation, the feed's floating split
 * button) renders its labels at. Matches the ceiling Material navigation components settle on:
 * enough to read clearly, small enough that four tab labels fit side by side on a phone and a
 * floating control does not grow over the post it hovers above.
 */
const val CHROME_MAX_FONT_SCALE = 1.3f

/**
 * Caps the font scale seen by [content] at [maxFontScale], leaving screen density untouched.
 *
 * Only for compact chrome whose labels are short, repeated on every screen and backed by an
 * accessible name — at font scale 2.0 the bottom-nav labels ellipsized into each other and the
 * floating "new posts" button covered the channel name of the post under it. Reading content
 * (posts, comments, settings rows, dialogs) must NEVER be wrapped in this: the user's font
 * scale is a hard accessibility requirement there.
 *
 * At or below the cap this is a pass-through — no new [Density] is provided, so the system's
 * (possibly non-linear) font scaling is untouched and nothing changes at font scale 1.0.
 */
@Composable
fun CappedFontScale(
    maxFontScale: Float = CHROME_MAX_FONT_SCALE,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    if (density.fontScale <= maxFontScale) {
        content()
        return
    }
    val capped = remember(density.density, maxFontScale) {
        Density(density = density.density, fontScale = maxFontScale)
    }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}
