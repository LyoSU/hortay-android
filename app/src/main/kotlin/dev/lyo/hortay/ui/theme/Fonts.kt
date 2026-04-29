package dev.lyo.hortay.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.lyo.hortay.R

/**
 * Three bundled font families per the design spec:
 *   - **Roboto Flex** (variable) — display, headlines, all UI body, post text. The single
 *     variable font carries Normal / Medium / SemiBold / Bold via the `wght` axis, so we
 *     declare each weight pointing to the same TTF and Compose picks the slice it needs.
 *   - **Roboto Serif** (variable) — reading mode body only (`ReadingTypography`).
 *   - **Roboto Mono** (variable) — code blocks, version strings, technical metadata.
 *
 * Bundled in `res/font/` instead of GoogleFonts provider — first frame renders correctly
 * with no Play Services dance, no first-launch tofu. Adds ~5.5 MB combined; an icon-system
 * scope of cost we accept for guaranteed correctness.
 */
val RobotoFlex = FontFamily(
    Font(R.font.roboto_flex, FontWeight.Normal),
    Font(R.font.roboto_flex, FontWeight.Medium),
    Font(R.font.roboto_flex, FontWeight.SemiBold),
    Font(R.font.roboto_flex, FontWeight.Bold),
)

val RobotoSerif = FontFamily(
    Font(R.font.roboto_serif, FontWeight.Normal),
    Font(R.font.roboto_serif, FontWeight.Medium),
    Font(R.font.roboto_serif, FontWeight.Bold),
)

val RobotoMono = FontFamily(
    Font(R.font.roboto_mono, FontWeight.Normal),
    Font(R.font.roboto_mono, FontWeight.Medium),
)
