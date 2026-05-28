package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi

/**
 * TDLib delivers profile accent colours as 0xRRGGBB ints (no alpha). The UI needs
 * opaque ARGB, so OR in a full alpha byte. Top-level so the conversion lives in one
 * place for both the background gradient and the ring.
 *
 * The mask guards against any non-zero high byte; TDLib sends 0xRRGGBB but we don't rely on it.
 */
internal fun Int.rgbToOpaqueArgb(): Int = 0xFF000000.toInt() or (this and 0x00FFFFFF)

/**
 * Background-gradient colours for the profile photo backdrop, as opaque ARGB.
 * Picks the dark- or light-theme variant; empty when TDLib supplied none (the
 * caller then renders the brand fallback brush).
 */
internal fun TdApi.ProfileAccentColor.backgroundArgb(dark: Boolean): IntArray {
    val variant = if (dark) darkThemeColors else lightThemeColors
    return (variant?.backgroundColors ?: IntArray(0)).map { it.rgbToOpaqueArgb() }.toIntArray()
}

/**
 * Avatar-ring colours, as opaque ARGB. Prefers `storyColors` (the gradient Telegram
 * draws around the photo); falls back to `paletteColors` when the user's accent has
 * no story gradient. Empty when neither is set.
 */
internal fun TdApi.ProfileAccentColor.ringArgb(dark: Boolean): IntArray {
    val variant = if (dark) darkThemeColors else lightThemeColors
    val story = variant?.storyColors ?: IntArray(0)
    val source = if (story.isNotEmpty()) story else (variant?.paletteColors ?: IntArray(0))
    return source.map { it.rgbToOpaqueArgb() }.toIntArray()
}
