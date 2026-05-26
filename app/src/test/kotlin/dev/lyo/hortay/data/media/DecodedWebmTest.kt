package dev.lyo.hortay.data.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DecodedWebmTest {
    @Test fun loopsAcrossDuration() {
        val d = intArrayOf(100, 100, 100) // 300ms loop
        assertEquals(0, frameIndexFor(d, 0))
        assertEquals(1, frameIndexFor(d, 150))
        assertEquals(2, frameIndexFor(d, 250))
        assertEquals(0, frameIndexFor(d, 300))
        assertEquals(1, frameIndexFor(d, 450))
    }
    @Test fun singleFrameAlwaysZero() = assertEquals(0, frameIndexFor(intArrayOf(100), 99999))
    @Test fun emptyIsZero() = assertEquals(0, frameIndexFor(intArrayOf(), 5))
}
