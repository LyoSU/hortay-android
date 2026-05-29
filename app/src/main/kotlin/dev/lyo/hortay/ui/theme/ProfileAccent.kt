package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import dev.lyo.hortay.data.ProfileAccentResolver
import kotlin.math.hypot

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
 * Radial cover brush for a profile header, faithful to how the official Telegram
 * Android client paints `profileAccentColorId` (`ProfileActivity.TopView` +
 * `PeerColorActivity` use a `RadialGradient`, NOT a linear fade-to-surface):
 *
 *   - centre: horizontally centred, vertically ~40 % down the cover (near the avatar);
 *   - radius: 0.75 × the cover's diagonal, CLAMP tile mode;
 *   - stops: centre → edge = `backgroundColors[1]` → `backgroundColors[0]`.
 *
 * No fade to white — the colour is a solid block; separation from the body below is
 * structural (the cover simply ends and surface begins). The avatar straddles the seam.
 * `storyColors` are deliberately NOT used here — in Telegram they only paint the
 * active-story ring, which we don't draw for a user with no stories.
 */
private fun radialCover(colors: List<Color>): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradientShader(
        center = Offset(size.width / 2f, size.height * 0.40f),
        radius = 0.75f * hypot(size.width, size.height),
        colors = colors,
        colorStops = listOf(0f, 1f),
        tileMode = TileMode.Clamp,
    )
}

/**
 * Cover brush for a profile hero. The user's own accent gradient when set (radial,
 * two background shades), otherwise a soft brand cover (`primaryContainer →
 * secondaryContainer`) — both branches stay fully coloured, never fading to white.
 * `accentId == -1` (unset) or a registry miss → brand fallback.
 */
@Composable
fun profileCoverBrush(accentId: Int): Brush {
    val resolver = LocalProfileAccent.current
    val dark = isSystemInDarkTheme()
    val argb = resolver.backgroundArgb(accentId, dark)
    val colors = if (argb != null && argb.isNotEmpty()) {
        // Telegram order: inner stop = backgroundColors[1] (last), outer = backgroundColors[0]
        // (first). A single-colour accent collapses both stops to a flat wash.
        val inner = Color(argb.last())
        val outer = Color(argb.first())
        listOf(inner, outer)
    } else {
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        )
    }
    return radialCover(colors)
}
