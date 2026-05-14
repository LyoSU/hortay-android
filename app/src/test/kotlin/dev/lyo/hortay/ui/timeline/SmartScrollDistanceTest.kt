package dev.lyo.hortay.ui.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartScrollDistanceTest {

    @Test
    fun `chooses Instant when distance exceeds threshold`() {
        assertEquals(ScrollKind.Instant, scrollKindFor(currentIndex = 0, target = 100, threshold = 8))
    }

    @Test
    fun `chooses Animated when distance equals threshold`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 0, target = 8, threshold = 8))
    }

    @Test
    fun `chooses Animated when within threshold`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 10, target = 12, threshold = 8))
    }

    @Test
    fun `distance is symmetric — scrolling up`() {
        assertEquals(ScrollKind.Instant, scrollKindFor(currentIndex = 200, target = 50, threshold = 8))
    }

    @Test
    fun `same index is Animated noop`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 5, target = 5, threshold = 8))
    }
}
