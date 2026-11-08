package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuoteRangeTest {

    @Test
    fun `an exact quote maps to its span`() {
        val text = "Air raid alert in Kyiv. All clear at 14:05."
        assertEquals(24 until 43, quoteRange(text, "All clear at 14:05."))
    }

    @Test
    fun `a quote with trailing differences falls back to its opening words`() {
        val text = "The new release ships tomorrow morning for everyone."
        val range = quoteRange(text, "The new release ships tomorrow morning!")
        assertEquals(0, range?.first)
    }

    @Test
    fun `a quote that is not in the text has no span`() {
        assertNull(quoteRange("Something else entirely", "missing fragment here"))
        assertNull(quoteRange("short", ""))
    }
}
