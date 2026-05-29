package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/** The cover's two colours [top, bottom] — the user's accent shades, or the brand fallback. */
@Composable
private fun coverColors(accentId: Int): Pair<Color, Color> {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val argb = resolver.backgroundArgb(accentId, dark)
    return when {
        argb != null && argb.size >= 2 -> Color(argb[0]) to Color(argb[1])
        argb != null && argb.size == 1 -> {
            val c = Color(argb[0])
            c to c
        }
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.secondaryContainer
    }
}

/**
 * Cover brush for a profile hero — a clean vertical gradient of the user's two profile colours
 * (or the brand fallback). The colours stay fully saturated; we do NOT blend toward the surface,
 * which produced muddy in-between tones. The hero is the colour; legible text rides on top via
 * [profileOnCoverColor]. Matches how Telegram fills its profile header with the peer colours.
 *
 * `storyColors` are unused — Telegram only paints them as the active-story ring, which we don't
 * draw for a user with no stories.
 */
@Composable
fun profileCoverBrush(accentId: Int): Brush {
    val (top, bottom) = coverColors(accentId)
    return Brush.verticalGradient(listOf(top, bottom))
}

/**
 * Foreground colour for text/icons sitting ON the cover — white on a dark cover, near-black on a
 * light one, picked from the cover's bottom shade (where the name sits) by perceived luminance.
 * This is the "name is black or white, done properly" contrast rule Telegram applies, instead of
 * a fixed dark colour that reads as muddy on a saturated background.
 */
@Composable
fun profileOnCoverColor(accentId: Int): Color {
    val (_, bottom) = coverColors(accentId)
    return if (bottom.luminance() < 0.5f) Color.White else Color(0xDE000000)
}
