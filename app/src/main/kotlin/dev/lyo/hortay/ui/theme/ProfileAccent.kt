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

/**
 * Cover brush for a profile hero — **one continuous vertical wash over the whole hero**:
 * the user's accent colour(s) at the top, softening into the card/sheet surface by the lower
 * third. There is no hard "colour block / white block" seam — the name sits on a near-neutral
 * background (legible) and the hero blends seamlessly into the content below.
 *
 *   - two background colours → `backgroundColors[0]` (top) → `backgroundColors[1]` (mid) → surface;
 *   - a single colour → that colour top+mid → surface;
 *   - no accent set / registry miss → brand wash (`primaryContainer → secondaryContainer → surface`).
 *
 * `storyColors` are deliberately unused — in Telegram they only paint the active-story ring,
 * which we don't draw for a user with no stories.
 */
@Composable
fun profileCoverBrush(accentId: Int): Brush {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val end = MaterialTheme.colorScheme.surfaceContainerLow
    val argb = resolver.backgroundArgb(accentId, dark)
    val (top, mid) = when {
        argb != null && argb.size >= 2 -> Color(argb[0]) to Color(argb[1])
        argb != null && argb.size == 1 -> {
            val c = Color(argb[0])
            c to c
        }
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.secondaryContainer
    }
    return Brush.verticalGradient(
        0.0f to top,
        0.45f to mid,
        1.0f to end,
    )
}
