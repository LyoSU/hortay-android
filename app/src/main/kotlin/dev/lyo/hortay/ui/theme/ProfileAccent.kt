package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.lyo.hortay.data.ProfileAccentResolver

/**
 * Resolver for per-user Telegram profile accent colours. Seeded once at
 * [dev.lyo.hortay.MainActivity] from `AppGraph.profileAccent`; defaults to the
 * always-fallback resolver so guest mode, auth and previews render the brand cover.
 *
 * Why a CompositionLocal and not a parameter: the resolver is consumed on two
 * unrelated surfaces (Settings hero, user-profile sheet) and would otherwise thread
 * through several composables. Same mitigation as `LocalReadCursors` / `LocalMediaCache`.
 */
val LocalProfileAccent = staticCompositionLocalOf<ProfileAccentResolver> {
    ProfileAccentResolver.Empty
}

/** Darken a colour by [fraction] toward black, keeping alpha — for a subtle second stop. */
private fun Color.darken(fraction: Float): Color =
    Color(red * (1f - fraction), green * (1f - fraction), blue * (1f - fraction), alpha)

/**
 * Cover brush for a profile header — a **visible** vertical two-tone gradient of the user's
 * own profile colours (top → bottom), never fading to white.
 *
 * Telegram's `ProfileActivity` uses a RadialGradient, but that is calibrated for a tall,
 * full-screen header where the transition spans the visible area. Our cover is wide and
 * short (≈360×84 dp); a radial with Telegram's `0.75 × diagonal` radius barely changes
 * across such a band and reads as a flat block. A vertical two-tone keeps the gradient
 * clearly visible in a compact cover while still using the user's two colours.
 *
 *   - two background colours → `backgroundColors[0]` (top) → `backgroundColors[1]` (bottom);
 *   - a single colour → that colour with a slightly darker top stop, so it isn't dead-flat;
 *   - no accent set / registry miss → soft brand cover (`primaryContainer → secondaryContainer`).
 *
 * `storyColors` are deliberately unused here — in Telegram they only paint the active-story
 * ring, which we don't draw for a user with no stories.
 */
@Composable
fun profileCoverBrush(accentId: Int): Brush {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val argb = resolver.backgroundArgb(accentId, dark)
    val colors = when {
        argb != null && argb.size >= 2 -> listOf(Color(argb[0]), Color(argb[1]))
        argb != null && argb.size == 1 -> {
            val c = Color(argb[0])
            listOf(c.darken(0.14f), c)
        }
        else -> listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        )
    }
    return Brush.verticalGradient(colors)
}
