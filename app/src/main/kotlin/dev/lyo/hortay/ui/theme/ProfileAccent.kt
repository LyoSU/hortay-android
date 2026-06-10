package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

/**
 * Fraction each accent stop is blended toward the active scheme's `surface`. ~27% softens the
 * saturated Telegram-peer purples so the hero harmonises with the periwinkle app instead of
 * fighting it (clean-canvas doctrine), while keeping enough of the peer accent for identity.
 * Lerping toward `surface` (not a fixed light grey) means the blend tracks the dark scheme too.
 */
private const val COVER_SURFACE_BLEND = 0.27f

/** The cover's two colours [top, bottom] — the user's accent shades blended toward `surface`,
 *  or the brand fallback (already scheme-derived, blended for visual parity). */
@Composable
private fun coverColors(accentId: Int): Pair<Color, Color> {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surface
    val argb = resolver.backgroundArgb(accentId, dark)
    val (rawTop, rawBottom) = when {
        argb != null && argb.size >= 2 -> Color(argb[0]) to Color(argb[1])
        argb != null && argb.size == 1 -> {
            val c = Color(argb[0])
            c to c
        }
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.secondaryContainer
    }
    return lerp(rawTop, surface, COVER_SURFACE_BLEND) to lerp(rawBottom, surface, COVER_SURFACE_BLEND)
}

/**
 * Cover brush for a profile hero — a vertical gradient of the user's two profile colours (or the
 * brand fallback), each stop blended ~27% toward the active scheme's `surface` so the saturated
 * peer purples sit calmly on the clean canvas (and the dark scheme) instead of clashing. Legible
 * text rides on top via [profileOnCoverColor], whose contrast is recomputed against the SAME
 * blended shades. Matches how Telegram fills its profile header with the peer colours, toned down.
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
 * light one, picked from the cover's BLENDED bottom shade (where the name sits) by perceived
 * luminance. Recomputed against the post-blend colour, not the raw accent, so the contrast call
 * stays correct after [COVER_SURFACE_BLEND] lightens (light scheme) or darkens (dark scheme) the
 * cover. This is the "name is black or white, done properly" rule Telegram applies.
 */
@Composable
fun profileOnCoverColor(accentId: Int): Color {
    val (_, bottom) = coverColors(accentId)
    return if (bottom.luminance() < 0.5f) Color.White else Color(0xDE000000)
}
