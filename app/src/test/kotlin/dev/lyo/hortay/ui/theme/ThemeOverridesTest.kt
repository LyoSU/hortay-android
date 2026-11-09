package dev.lyo.hortay.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Covers the pure theme transforms: the pure-black scheme override and the reading-size scaling. */
class ThemeOverridesTest {

    private val dark = darkColorScheme(
        background = Color(0xFF131318),
        surface = Color(0xFF131318),
        surfaceContainerLowest = Color(0xFF0D0D14),
        surfaceContainerLow = Color(0xFF1B1B22),
        surfaceContainer = Color(0xFF1F1F27),
        surfaceContainerHigh = Color(0xFF2A2A32),
        surfaceContainerHighest = Color(0xFF34343C),
    )

    @Test
    fun `pure black blackens the canvas only`() {
        val black = dark.withPureBlack()
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceContainerLowest)
    }

    @Test
    fun `pure black keeps the rest of the container ladder and the accents`() {
        val black = dark.withPureBlack()
        assertEquals(dark.surfaceContainerLow, black.surfaceContainerLow)
        assertEquals(dark.surfaceContainer, black.surfaceContainer)
        assertEquals(dark.surfaceContainerHigh, black.surfaceContainerHigh)
        assertEquals(dark.surfaceContainerHighest, black.surfaceContainerHighest)
        assertEquals(dark.primary, black.primary)
        assertEquals(dark.onSurface, black.onSurface)
    }

    @Test
    fun `reading scale multiplies font size and line height together`() {
        val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
        val large = body.scaledForReading(1.2f)
        assertEquals(18f, large.fontSize.value, 0.001f)
        assertEquals(26.4f, large.lineHeight.value, 0.001f)
    }

    @Test
    fun `identity scale returns the same style`() {
        val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
        assertSame(body, body.scaledForReading(1f))
    }

    @Test
    fun `unspecified units survive scaling`() {
        val style = TextStyle(fontSize = 15.sp)
        val scaled = style.scaledForReading(1.25f)
        assertEquals(18.75f, scaled.fontSize.value, 0.001f)
        assertEquals(TextUnit.Unspecified, scaled.lineHeight)
    }
}
