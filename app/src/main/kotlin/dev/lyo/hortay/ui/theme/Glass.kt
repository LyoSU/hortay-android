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
 */
@Composable
fun rememberGlassStyle(): HazeBlurStyle {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    return remember(surface) {
        HazeBlurStyle {
            blurRadius(24.dp)
            noiseFactor(0.08f)
            backgroundColor(surface)
            colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = 0.72f))))
        }
    }
}
