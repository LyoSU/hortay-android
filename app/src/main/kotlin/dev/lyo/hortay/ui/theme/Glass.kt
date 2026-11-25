package dev.lyo.hortay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect

/**
 * The backdrop the floating chrome (nav bar) blurs. Provided by the tab scaffold that marks
 * its content as the haze source; null where no source is set up, in which case glass
 * surfaces fall back to an opaque container.
 */
val LocalGlassBackdrop = compositionLocalOf<HazeState?> { null }

/**
 * Frosted-glass material for floating chrome: a 24 dp blur of whatever scrolls underneath,
 * tinted with the surface colour so text on top keeps its contrast in both themes. On
 * Android 11 and older Haze draws the tint alone as a translucent scrim.
 *
 * One style for every glass surface: the audio mini-player sits directly above the nav bar,
 * so two tints would show as two different materials stacked on each other.
 */
@Composable
fun rememberGlassStyle(): HazeBlurStyle {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    return remember(surface) {
        HazeBlurStyle {
            blurRadius(24.dp)
            noiseFactor(0.08f)
            backgroundColor(surface)
            colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = GLASS_TINT_ALPHA))))
        }
    }
}

/**
 * Surface tint over the blurred backdrop. Both glass surfaces carry text (nav labels, the
 * mini-player's track line). Worst case is unblurred-looking content of the opposite
 * luminance (a black photo in light theme, a white text block in dark): at 0.86 the mixed
 * backdrop stays above ~0.6 relative luminance in light and below ~0.05 in dark, so
 * `onSurfaceVariant` text keeps >= 5:1 (4.5:1 needs >= ~0.77). The blur only improves this.
 * The earlier 0.72 let a dark photo or a dense paragraph pull nav labels below 4.5:1.
 */
private const val GLASS_TINT_ALPHA = 0.86f
