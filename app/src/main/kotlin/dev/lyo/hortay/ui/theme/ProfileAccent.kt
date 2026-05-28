package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.lyo.hortay.data.ProfileAccentResolver

/**
 * Resolver for per-user Telegram profile accent colours. Seeded once at
 * [dev.lyo.hortay.MainActivity] from `AppGraph.profileAccent`; defaults to the
 * always-fallback resolver so guest mode, auth and previews render the brand gradient.
 *
 * Why a CompositionLocal and not a parameter: the resolver is consumed on two
 * unrelated surfaces (Settings hero, user-profile sheet) and would otherwise thread
 * through several composables. Same mitigation as `LocalReadCursors` / `LocalMediaCache`.
 */
val LocalProfileAccent = staticCompositionLocalOf<ProfileAccentResolver> {
    ProfileAccentResolver.Empty
}

/**
 * Brand fallback gradient for a profile hero when the user has no accent colour set.
 * A soft `primaryContainer → surface` vertical fade — the same direction the accent
 * brush uses, so set/unset heroes read as the same component.
 */
@Composable
@ReadOnlyComposable
fun brandHeroFallbackBrush(): Brush = Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceContainerLow,
    ),
)

private fun IntArray.toBrush(): Brush {
    val stops = map { Color(it) }
    // A single TDLib colour still needs two stops for a gradient; duplicate it so the
    // band reads as a flat-ish wash rather than throwing on a one-element gradient.
    val colors = if (stops.size == 1) listOf(stops[0], stops[0]) else stops
    return Brush.verticalGradient(colors)
}

/**
 * Background-band brush for a profile hero. Returns the user's own accent gradient when
 * set, otherwise [brandHeroFallbackBrush]. `accentId == -1` (unset) → fallback.
 */
@Composable
fun profileAccentBrush(accentId: Int): Brush {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val fallback = brandHeroFallbackBrush()
    val argb = resolver.backgroundArgb(accentId, dark)
    return argb?.toBrush() ?: fallback
}

/**
 * Ring brush for the avatar. Returns the user's accent ring gradient when set, else a
 * flat `primary` ring so the avatar always carries a subtle outline.
 */
@Composable
fun profileRingBrush(accentId: Int): Brush {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val argb = resolver.ringArgb(accentId, dark)
    return argb?.toBrush() ?: Brush.verticalGradient(listOf(primary, primary))
}
