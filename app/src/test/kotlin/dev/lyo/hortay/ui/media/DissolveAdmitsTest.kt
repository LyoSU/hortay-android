package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DissolveAdmitsTest {

    @Test
    fun `a visible row dissolves while there is room`() {
        assertTrue(dissolveAdmits(enabled = true, running = 0, visibleWidth = 300f, visibleHeight = 200f, minVisiblePx = 12f))
        assertTrue(dissolveAdmits(true, MAX_CONCURRENT - 1, 300f, 200f, 12f))
    }

    @Test
    fun `reduced motion never dissolves`() {
        assertFalse(dissolveAdmits(enabled = false, running = 0, visibleWidth = 300f, visibleHeight = 200f, minVisiblePx = 12f))
    }

    @Test
    fun `a burst past the cap disappears plainly`() {
        assertFalse(dissolveAdmits(true, MAX_CONCURRENT, 300f, 200f, 12f))
        assertFalse(dissolveAdmits(true, MAX_CONCURRENT + 5, 300f, 200f, 12f))
    }

    @Test
    fun `a row that is off screen or a sliver does not dissolve`() {
        assertFalse(dissolveAdmits(true, 0, 0f, 0f, 12f))
        assertFalse(dissolveAdmits(true, 0, 300f, 5f, 12f))
        assertFalse(dissolveAdmits(true, 0, 5f, 300f, 12f))
        assertTrue(dissolveAdmits(true, 0, 300f, 12f, 12f))
    }

    @Test
    fun `captures stay full resolution until they get large`() {
        assertEquals(1f, dissolveCaptureScale(1080f, 1000f))
        val scale = dissolveCaptureScale(1080f, 2400f)
        assertTrue(scale < 1f)
        assertEquals(1_500_000f, 1080f * scale * 2400f * scale, 1f)
    }
}
