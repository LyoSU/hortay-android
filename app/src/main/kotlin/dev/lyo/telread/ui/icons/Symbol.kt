package dev.lyo.telread.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.telread.R

/**
 * Material Symbols (Rounded) — Google's current icon system, replacing the legacy
 * "Material Icons" set the [androidx.compose.material.icons.rounded] package ships.
 *
 * Resolved as a downloadable Google Font, so the first launch fetches the variable
 * font once (~1MB) and every subsequent process reads the system-wide font cache —
 * the same cache the official Telegram and any other Material 3 app populate.
 *
 * Glyphs are looked up by ligature: `Symbol("home")` renders the home icon. Names
 * mirror the snake_case identifiers Google publishes at fonts.google.com/icons —
 * see that catalogue for the full set.
 *
 * Variable font axes (FILL, GRADE, opsz, weight) are not exposed by Compose's
 * GoogleFont API, so we get the default Outlined Default style. That's the same
 * style Material 3 specs prescribe everywhere except a few fill-state affordances.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val symbolFont = GoogleFont("Material Symbols Rounded")

private val materialSymbolsFamily = FontFamily(
    Font(googleFont = symbolFont, fontProvider = provider, weight = FontWeight.W400),
)

/**
 * Render a Material Symbol glyph at [size]. Default 24dp matches Material 3's icon
 * recommendation; bumping [size] scales both glyph and click hit-region.
 *
 * The glyph is laid out inside a centred [Box] so the wrapping cell is a clean
 * size×size square — variable text baselines would otherwise leave inconsistent
 * vertical drift between siblings in a Row.
 */
@Composable
fun Symbol(
    name: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier
    Box(
        modifier = modifier.size(size).then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = TextStyle(
                fontFamily = materialSymbolsFamily,
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() },
                color = tint,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}
