package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class SpoilerRippleTest {

    @Test
    fun `ripple reaches the farthest corner from the tap`() {
        assertEquals(hypot(100f, 50f), SpoilerRipple.farthestCorner(0f, 0f, 0f, 0f, 100f, 50f), 1e-4f)
        assertEquals(hypot(50f, 25f), SpoilerRipple.farthestCorner(50f, 25f, 0f, 0f, 100f, 50f), 1e-4f)
        assertEquals(hypot(90f, 40f), SpoilerRipple.farthestCorner(10f, 10f, 0f, 0f, 100f, 50f), 1e-4f)
    }

    @Test
    fun `duration follows Telegram's clamp of 0_3 ms per px`() {
        assertEquals(250L, SpoilerRipple.durationMs(100f))
        assertEquals(300L, SpoilerRipple.durationMs(1000f))
        assertEquals(550L, SpoilerRipple.durationMs(5000f))
    }

    @Test
    fun `fraction is clamped and handles a stale clock`() {
        assertEquals(0f, SpoilerRipple.fraction(nowMs = 900, startMs = 1000, durationMs = 400))
        assertEquals(0.5f, SpoilerRipple.fraction(nowMs = 1200, startMs = 1000, durationMs = 400))
        assertEquals(1f, SpoilerRipple.fraction(nowMs = 9000, startMs = 1000, durationMs = 400))
        assertEquals(1f, SpoilerRipple.fraction(nowMs = 0, startMs = 0, durationMs = 0))
    }

    @Test
    fun `the feathered band has cleared the farthest corner when the ripple ends`() {
        val max = 300f
        val outer = SpoilerRipple.radius(1f, max)
        assertEquals(max, outer * (1f - SpoilerRipple.RIPPLE_FEATHER), 1e-3f)
        assertEquals(0f, SpoilerRipple.radius(0f, max))
    }

    @Test
    fun `easings start at 0 and end at 1`() {
        assertEquals(0f, SpoilerRipple.EaseInQuad.transform(0f))
        assertEquals(1f, SpoilerRipple.EaseInQuad.transform(1f))
        assertEquals(0.25f, SpoilerRipple.EaseInQuad.transform(0.5f))
        assertEquals(0f, SpoilerRipple.EaseBoth.transform(0f), 1e-3f)
        assertEquals(1f, SpoilerRipple.EaseBoth.transform(1f), 1e-3f)
        assertTrue(SpoilerRipple.EaseBoth.transform(0.5f) in 0.45f..0.55f)
    }
}
