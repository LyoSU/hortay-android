package dev.lyo.hortay.data.rich

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the shared list-marker numeral converters ([romanNumeral] / [alphaOrdinal]): the
 * ordinary values both list renderers derive, and — load-bearing — the untrusted-input cap that
 * keeps a colossal server-sent ordinal from building a multi-megabyte string (ANR / OOM).
 */
class RichListMarkerTest {

    @Test
    fun `roman numerals for common ordinals`() {
        assertEquals("I", romanNumeral(1))
        assertEquals("IV", romanNumeral(4))
        assertEquals("IX", romanNumeral(9))
        assertEquals("XLII", romanNumeral(42))
        assertEquals("MCMLXXXIV", romanNumeral(1984))
        assertEquals("MMMCMXCIX", romanNumeral(ROMAN_MAX)) // 3999, the classical ceiling
    }

    @Test
    fun `roman numeral degrades to decimal outside the classical range`() {
        // Non-positive and above the ceiling both fall back to the decimal string.
        assertEquals("0", romanNumeral(0))
        assertEquals("-5", romanNumeral(-5))
        assertEquals("4000", romanNumeral(ROMAN_MAX + 1))
        assertEquals("5000", romanNumeral(5000))
    }

    @Test
    fun `roman numeral caps allocation for a hostile ordinal`() {
        // The defense: an unbounded server value must NOT expand to ~value/1000 'M' glyphs.
        // Both the huge positive and Int.MAX_VALUE degrade to their bounded decimal form.
        val huge = romanNumeral(2_000_000_000)
        assertEquals("2000000000", huge)
        assertTrue(huge.length <= 11, "marker must stay bounded, was ${huge.length} chars")
        assertEquals(Int.MAX_VALUE.toString(), romanNumeral(Int.MAX_VALUE))
    }

    @Test
    fun `alphabetic ordinal is bijective base-26`() {
        assertEquals("a", alphaOrdinal(1, upper = false))
        assertEquals("z", alphaOrdinal(26, upper = false))
        assertEquals("aa", alphaOrdinal(27, upper = false))
        assertEquals("A", alphaOrdinal(1, upper = true))
        assertEquals("AA", alphaOrdinal(27, upper = true))
    }

    @Test
    fun `alphabetic ordinal degrades to decimal for non-positive values and stays bounded`() {
        assertEquals("0", alphaOrdinal(0, upper = false))
        assertEquals("-1", alphaOrdinal(-1, upper = true))
        // Logarithmic growth — even Int.MAX_VALUE is a handful of letters, no cap needed.
        assertTrue(alphaOrdinal(Int.MAX_VALUE, upper = false).length <= 8)
    }
}
