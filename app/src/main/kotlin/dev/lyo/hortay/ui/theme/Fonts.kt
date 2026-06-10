package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.lyo.hortay.R

/**
 * Two display + body families, BUNDLED as variable TTFs in `res/font/` — no runtime
 * provider, no network, no Play Services dependency.
 *
 * Inter for body / labels — neutral, sturdy at small sizes, the pragmatic UI workhorse.
 * Plus Jakarta Sans for display / headlines — distinct geometric character so the brand
 * surfaces (top app bar, large titles) read differently from the dense reading content.
 *
 * Why bundled, not the Google Fonts provider (the previous approach): the provider pulls
 * the typefaces at runtime via Play Services. On cold/offline start — or any provider
 * hiccup — the app painted system Roboto first, then reflowed the ENTIRE layout when the
 * real fonts arrived (full-screen FOUT), and on Play-Services-less devices the brand type
 * never loaded at all. Bundling makes first-frame rendering deterministic with zero reflow.
 *
 * Why variable fonts with PINNED weights (the old KDoc objected to bundled fonts): the
 * earlier attempt used Roboto Flex *variable* and let its default weight axis ride, which
 * sat visually below 400 and made body copy read light against the periwinkle palette.
 * That objection no longer applies — each [Font] entry below pins its exact
 * [FontVariation.weight], so the renderer instances the requested weight rather than the
 * axis default. One variable file per family carries every weight Type.kt asks for
 * (Inter Normal/Medium/SemiBold/Bold; Plus Jakarta Sans SemiBold/Bold/ExtraBold), keeping
 * the APK cost to a single ~860 KB + ~170 KB pair instead of seven static cuts.
 */

@OptIn(ExperimentalTextApi::class)
private fun interFont(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun plusJakartaSansFont(weight: FontWeight) = Font(
    R.font.plus_jakarta_sans_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Brand display family — geometric, distinctive, used for app name and large headlines. */
val DisplayFontFamily = FontFamily(
    plusJakartaSansFont(FontWeight.SemiBold),
    plusJakartaSansFont(FontWeight.Bold),
    plusJakartaSansFont(FontWeight.ExtraBold),
)

/** Body family — neutral, optimised for long-form reading. */
val BodyFontFamily = FontFamily(
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold),
)
