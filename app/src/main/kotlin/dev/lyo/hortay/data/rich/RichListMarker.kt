package dev.lyo.hortay.data.rich

/**
 * Pure ordinal-marker converters for rich-message list items, shared by the plain-text projector
 * ([RichPlainText]) and the Compose list renderer (`ui.rich.RichBlocks`), so the numeral math lives
 * (and is tested) in exactly one place.
 *
 * Untrusted-input defense: a `pageBlockListItem.value` is server-controlled and arrives unbounded,
 * so an ordered list could carry a colossal ordinal (e.g. an instant view of a page with
 * `<ol start=2000000000>`). [romanNumeral] would otherwise append ~value/1000 `M` glyphs — a
 * multi-megabyte string built on the main thread during composition / projection (ANR + OOM). Both
 * converters cap the work: Roman numerals are only defined up to [ROMAN_MAX] (3999) so anything
 * above it (or ≤ 0) degrades to the plain decimal string (bounded to ~11 chars), and the alphabetic
 * ordinal grows logarithmically. Same threat class the mapper guards with
 * [RichPlainText.MAX_DEPTH] — kept here so the guarantee travels with the numeral logic.
 */

/** Classical upper bound for Roman numerals; above it there is no standard form, so we show digits. */
internal const val ROMAN_MAX = 3999

/**
 * Uppercase Roman numeral for [value] (1..[ROMAN_MAX]); any value outside that range — including a
 * non-positive or absurdly large server ordinal — falls back to its decimal string, which both
 * matches the "no standard Roman form" convention and bounds the allocation.
 */
internal fun romanNumeral(value: Int): String {
    if (value <= 0 || value > ROMAN_MAX) return value.toString()
    val symbols = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )
    val sb = StringBuilder()
    var n = value
    for ((magnitude, symbol) in symbols) {
        while (n >= magnitude) {
            sb.append(symbol)
            n -= magnitude
        }
    }
    return sb.toString()
}

/** Bijective base-26 alphabetic ordinal: 1 -> a, 26 -> z, 27 -> aa. Non-positive values fall back
 *  to the decimal string. Grows logarithmically, so it needs no explicit cap. */
internal fun alphaOrdinal(value: Int, upper: Boolean): String {
    if (value <= 0) return value.toString()
    val sb = StringBuilder()
    var n = value
    while (n > 0) {
        n--
        sb.append('a' + (n % 26))
        n /= 26
    }
    val s = sb.reverse().toString()
    return if (upper) s.uppercase() else s
}
