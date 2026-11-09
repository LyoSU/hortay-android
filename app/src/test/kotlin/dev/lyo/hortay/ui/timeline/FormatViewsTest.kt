package dev.lyo.hortay.ui.timeline

import java.util.Locale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class FormatViewsTest {
    @Test
    fun compactsThousandsAndMillionsWithoutTrailingZero() {
        assertEquals("999", formatViews(999))
        assertEquals("1K", formatViews(1_000))
        assertEquals("1.5K", formatViews(1_500))
        assertEquals("1M", formatViews(1_000_000))
        assertEquals("2.3M", formatViews(2_300_000))
    }

    @Test
    fun ignoresDefaultLocaleDecimalSeparator() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))
            assertEquals("1.5K", formatViews(1_500))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
