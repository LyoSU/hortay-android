package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AudioPlaybackHelpersTest {

    @Test
    fun `speed cycles 1x to 1_5x to 2x and wraps back to 1x`() {
        assertEquals(1.5f, nextPlaybackSpeed(1f))
        assertEquals(2f, nextPlaybackSpeed(1.5f))
        assertEquals(1f, nextPlaybackSpeed(2f))
    }

    @Test
    fun `an unknown speed restarts the cycle at 1x`() {
        assertEquals(1f, nextPlaybackSpeed(1.25f))
        assertEquals(1f, nextPlaybackSpeed(0f))
    }

    @Test
    fun `a speed off by float noise still advances`() {
        assertEquals(2f, nextPlaybackSpeed(1.5000001f))
    }

    @Test
    fun `speed label drops a zero fraction and follows the locale separator`() {
        assertEquals("1", formatPlaybackSpeed(1f, Locale.US))
        assertEquals("1.5", formatPlaybackSpeed(1.5f, Locale.US))
        assertEquals("2", formatPlaybackSpeed(2f, Locale.US))
        assertEquals("1,5", formatPlaybackSpeed(1.5f, Locale.forLanguageTag("uk")))
        assertEquals("1,5", formatPlaybackSpeed(1.5f, Locale.GERMANY))
    }

    @Test
    fun `mini-player shows only for a loaded track whose rows are all off screen`() {
        assertFalse(isMiniPlayerNeeded(activeKey = null, mountedKeys = emptySet()))
        assertFalse(isMiniPlayerNeeded(activeKey = null, mountedKeys = setOf(7)))
        assertTrue(isMiniPlayerNeeded(activeKey = 7, mountedKeys = emptySet()))
        assertTrue(isMiniPlayerNeeded(activeKey = 7, mountedKeys = setOf(3, 9)))
        assertFalse(isMiniPlayerNeeded(activeKey = 7, mountedKeys = setOf(3, 7)))
    }
}
