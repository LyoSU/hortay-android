package dev.lyo.hortay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Brand seed — periwinkle / lavender. The full Material 3 tonal palette below is generated
 * from this seed via Google's algorithm and locked into the spec; we don't drift from these
 * values without bumping the design-system version.
 *
 * Cool whites with a lavender undertone everywhere — never warm beige, never neutral grey.
 */
val Seed = Color(0xFF5A5BA8)

// Light scheme
val LightPrimary = Color(0xFF5A5BA8)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE3E1FF)
val LightOnPrimaryContainer = Color(0xFF15155F)

val LightSecondary = Color(0xFF5C5D72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE1E0F4)
val LightOnSecondaryContainer = Color(0xFF181A2D)

val LightTertiary = Color(0xFF4F6486)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFD7E2FF)
val LightOnTertiaryContainer = Color(0xFF001D35)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFCFAFF)
val LightOnBackground = Color(0xFF1B1B23)
val LightSurface = Color(0xFFFCFAFF)
val LightOnSurface = Color(0xFF1B1B23)
val LightSurfaceVariant = Color(0xFFE5E1F1)
val LightOnSurfaceVariant = Color(0xFF47465A)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF6F3FE)
val LightSurfaceContainer = Color(0xFFF0EDF8)
val LightSurfaceContainerHigh = Color(0xFFEAE7F2)
val LightSurfaceContainerHighest = Color(0xFFE5E2ED)

val LightOutline = Color(0xFF78768B)
val LightOutlineVariant = Color(0xFFC9C5D7)

val LightInverseSurface = Color(0xFF303038)
val LightInverseOnSurface = Color(0xFFF2EFF7)
val LightInversePrimary = Color(0xFFC2C0FF)

// Dark scheme
val DarkPrimary = Color(0xFFC2C0FF)
val DarkOnPrimary = Color(0xFF2A2A5C)
val DarkPrimaryContainer = Color(0xFF41429A)
val DarkOnPrimaryContainer = Color(0xFFE3E1FF)

val DarkSecondary = Color(0xFFC5C4DC)
val DarkOnSecondary = Color(0xFF2D2E42)
val DarkSecondaryContainer = Color(0xFF444559)
val DarkOnSecondaryContainer = Color(0xFFE1E0F4)

val DarkTertiary = Color(0xFFB6C7EC)
val DarkOnTertiary = Color(0xFF1F324E)
val DarkTertiaryContainer = Color(0xFF354A6D)
val DarkOnTertiaryContainer = Color(0xFFD7E2FF)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF131318)
val DarkOnBackground = Color(0xFFE5E1F0)
val DarkSurface = Color(0xFF131318)
val DarkOnSurface = Color(0xFFE5E1F0)
val DarkSurfaceVariant = Color(0xFF47465A)
val DarkOnSurfaceVariant = Color(0xFFC9C5D7)

val DarkSurfaceContainerLowest = Color(0xFF0D0D14)
val DarkSurfaceContainerLow = Color(0xFF1B1B22)
val DarkSurfaceContainer = Color(0xFF1F1F27)
val DarkSurfaceContainerHigh = Color(0xFF2A2A32)
val DarkSurfaceContainerHighest = Color(0xFF34343C)

val DarkOutline = Color(0xFF918FA4)
val DarkOutlineVariant = Color(0xFF47465A)

val DarkInverseSurface = Color(0xFFE5E1F0)
val DarkInverseOnSurface = Color(0xFF303038)
val DarkInversePrimary = Color(0xFF5A5BA8)

/**
 * Telegram Premium gold. One shared swatch for both light and dark themes — Telegram
 * itself doesn't recolour the premium star per theme; the gold reads as a brand mark,
 * not as part of the M3 tonal scheme. Tuned to sit between Telegram-Android's
 * `R.color.premium_gradient1/2` (#FBD55E → #E3A33F).
 */
val PremiumGold = Color(0xFFE9B33B)

/**
 * Traffic-light "status" tones (reachable / mid-latency / high-latency), used by proxy-health
 * signalling (see [dev.lyo.hortay.ui.settings.ProxyScreen]). These read the same regardless of
 * which M3 dynamic palette is active — a green dot must mean "reachable" whether the seed is
 * Hortay's periwinkle or a wallpaper-derived hue — so they're a small satellite set rather than
 * mapped onto [androidx.compose.material3.ColorScheme] roles, following the M3 "extended colors"
 * pattern for signal meanings the base scheme has no room for.
 *
 * Light instance sits at roughly M3 tone-40 (dark enough that [LightStatusColors.success] reaches
 * ≥4.5:1 contrast against [LightSurface], required because it's also used as body text, not just
 * a dot). Dark instance sits at roughly tone-80 (light enough to read on the near-black dark
 * surface without going neon).
 */
@Immutable
data class HortayStatusColors(
    val success: Color,
    val caution: Color,
    val degraded: Color,
)

val LightStatusColors = HortayStatusColors(
    success = Color(0xFF2E7D32),
    caution = Color(0xFF8F6C00),
    degraded = Color(0xFF9C4400),
)

val DarkStatusColors = HortayStatusColors(
    success = Color(0xFF6DD58C),
    caution = Color(0xFFFFCB66),
    degraded = Color(0xFFFFB68C),
)
