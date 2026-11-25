package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpoilerGeometryTest {

    @Test
    fun `slices on one line merge into one box, lines stay separate and ordered`() {
        val merged = mergeSpoilerLineRects(
            listOf(
                SpoilerLineRect(line = 1, left = 0f, top = 20f, right = 40f, bottom = 40f, rtl = false),
                SpoilerLineRect(line = 0, left = 60f, top = 0f, right = 120f, bottom = 20f, rtl = false),
                SpoilerLineRect(line = 0, left = 130f, top = 0f, right = 180f, bottom = 21f, rtl = false),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(SpoilerLineRect(0, 60f, 0f, 180f, 21f, false), merged[0])
        assertEquals(SpoilerLineRect(1, 0f, 20f, 40f, 40f, false), merged[1])
    }

    @Test
    fun `nothing to merge yields nothing`() {
        assertTrue(mergeSpoilerLineRects(emptyList()).isEmpty())
    }

    @Test
    fun `a single-line run is rounded at both ends`() {
        assertArrayEquals(floatArrayOf(4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f), spoilerRunRadii(true, true, rtl = false, radius = 4f))
    }

    @Test
    fun `a wrapped LTR run rounds its left start and right end only`() {
        assertArrayEquals(floatArrayOf(4f, 4f, 0f, 0f, 0f, 0f, 4f, 4f), spoilerRunRadii(true, false, rtl = false, radius = 4f))
        assertArrayEquals(floatArrayOf(0f, 0f, 4f, 4f, 4f, 4f, 0f, 0f), spoilerRunRadii(false, true, rtl = false, radius = 4f))
        assertArrayEquals(FloatArray(8), spoilerRunRadii(false, false, rtl = false, radius = 4f))
    }

    @Test
    fun `a wrapped RTL run starts on the right`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 4f, 4f, 4f, 4f, 0f, 0f), spoilerRunRadii(true, false, rtl = true, radius = 4f))
        assertArrayEquals(floatArrayOf(4f, 4f, 0f, 0f, 0f, 0f, 4f, 4f), spoilerRunRadii(false, true, rtl = true, radius = 4f))
    }
}
