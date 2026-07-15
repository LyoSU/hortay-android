package dev.lyo.hortay.ui.rich

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers [quoteAccentRole] — the pure tonal-shade rotation for nested block quotes. Each nesting
 * level shifts the accent so a quote-inside-a-quote reads as a new layer; the cycle repeats every
 * three levels so nesting can never run out of shades.
 */
class RichQuoteShadeTest {

    @Test
    fun `top-level quote uses the primary accent`() {
        assertEquals(QuoteAccentRole.Primary, quoteAccentRole(0))
    }

    @Test
    fun `each nesting level rotates the accent`() {
        assertEquals(QuoteAccentRole.Tertiary, quoteAccentRole(1))
        assertEquals(QuoteAccentRole.Secondary, quoteAccentRole(2))
    }

    @Test
    fun `the shade cycle repeats every three levels`() {
        assertEquals(QuoteAccentRole.Primary, quoteAccentRole(3))
        assertEquals(QuoteAccentRole.Tertiary, quoteAccentRole(4))
        assertEquals(QuoteAccentRole.Secondary, quoteAccentRole(5))
    }

    @Test
    fun `a negative depth is clamped to the top-level shade`() {
        assertEquals(QuoteAccentRole.Primary, quoteAccentRole(-1))
    }
}
