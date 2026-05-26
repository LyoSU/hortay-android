package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaRevealTest {
    @Test
    fun `placeholder stays visible until content revealed`() {
        assertTrue(placeholderLingerVisible(revealed = false, elapsedSinceRevealedMs = 0, lingerMs = 280))
        assertTrue(placeholderLingerVisible(revealed = false, elapsedSinceRevealedMs = 10_000, lingerMs = 280))
    }

    @Test
    fun `placeholder lingers for lingerMs after reveal then drops`() {
        assertTrue(placeholderLingerVisible(revealed = true, elapsedSinceRevealedMs = 0, lingerMs = 280))
        assertTrue(placeholderLingerVisible(revealed = true, elapsedSinceRevealedMs = 279, lingerMs = 280))
        assertFalse(placeholderLingerVisible(revealed = true, elapsedSinceRevealedMs = 280, lingerMs = 280))
        assertFalse(placeholderLingerVisible(revealed = true, elapsedSinceRevealedMs = 500, lingerMs = 280))
    }
}
