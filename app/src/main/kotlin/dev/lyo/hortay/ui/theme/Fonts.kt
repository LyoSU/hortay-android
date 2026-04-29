package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import dev.lyo.hortay.R

/**
 * Two display + body families pulled at runtime from Google Fonts via Play Services.
 *
 * Inter for body / labels — neutral, sturdy at small sizes, the pragmatic UI workhorse.
 * Plus Jakarta Sans for display / headlines — distinct geometric character so the brand
 * surfaces (top app bar, large titles) read differently from the dense reading content.
 *
 * Bundled fonts were tried (Roboto Flex variable) but rendered too light against the
 * periwinkle palette — the variable font's default weight axis sits below 400 visually,
 * which made body copy hard to scan. Reverted to these two well-tested static families.
 */
private val Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = GoogleFont("Inter")
private val PlusJakartaSans = GoogleFont("Plus Jakarta Sans")

/** Brand display family — geometric, distinctive, used for app name and large headlines. */
val DisplayFontFamily = FontFamily(
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.Bold, style = FontStyle.Normal),
    Font(googleFont = PlusJakartaSans, fontProvider = Provider, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
)

/** Body family — neutral, optimised for long-form reading. */
val BodyFontFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.SemiBold),
    Font(googleFont = Inter, fontProvider = Provider, weight = FontWeight.Bold),
)
