package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `a spoiler entirely past a maxLines clamp is skipped, not thrown on`() {
        // The shipped crash: caption clamped at 640 visible chars, spoiler starting at 730.
        assertNull(visibleLayoutSpan(730..760, textLength = 900, visibleEnd = 640))
    }

    @Test
    fun `a spoiler starting exactly at the visible end is skipped`() {
        assertNull(visibleLayoutSpan(640..700, textLength = 900, visibleEnd = 640))
    }

    @Test
    fun `a spoiler straddling the visible end is cut at it`() {
        assertEquals(600 until 640, visibleLayoutSpan(600..700, textLength = 900, visibleEnd = 640))
    }

    @Test
    fun `a range ending exactly at the visible end is kept whole`() {
        assertEquals(600 until 640, visibleLayoutSpan(600..639, textLength = 900, visibleEnd = 640))
    }

    @Test
    fun `a range past the text length is clamped or skipped`() {
        assertEquals(10 until 20, visibleLayoutSpan(10..50, textLength = 20, visibleEnd = 20))
        assertNull(visibleLayoutSpan(25..50, textLength = 20, visibleEnd = 20))
        // A visible end past the text (stale layout) is still bounded by the text.
        assertEquals(10 until 20, visibleLayoutSpan(10..50, textLength = 20, visibleEnd = 99))
    }

    @Test
    fun `empty text, empty and reversed ranges, and Int extremes never throw`() {
        assertNull(visibleLayoutSpan(0..5, textLength = 0, visibleEnd = 0))
        assertNull(visibleLayoutSpan(0..5, textLength = 20, visibleEnd = 0))
        assertNull(visibleLayoutSpan(IntRange(5, 4), textLength = 20, visibleEnd = 20))
        assertNull(visibleLayoutSpan(IntRange(8, 3), textLength = 20, visibleEnd = 20))
        assertEquals(0 until 3, visibleLayoutSpan(-4..2, textLength = 20, visibleEnd = 20))
        assertEquals(5 until 20, visibleLayoutSpan(5..Int.MAX_VALUE, textLength = 20, visibleEnd = 20))
        assertEquals(0 until 20, visibleLayoutSpan(Int.MIN_VALUE..Int.MAX_VALUE, textLength = 20, visibleEnd = 20))
        assertNull(visibleLayoutSpan(Int.MAX_VALUE..Int.MAX_VALUE, textLength = 20, visibleEnd = 20))
    }
}
