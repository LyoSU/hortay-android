package dev.lyo.hortay.ui.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartScrollTest {

    @Test fun `visibleFraction returns 1f when item fully inside viewport`() {
        assertEquals(1f, visibleFraction(itemStart = 100, itemEnd = 500, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction returns 0f when item fully above viewport`() {
        assertEquals(0f, visibleFraction(itemStart = -500, itemEnd = -100, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction returns 0f when item fully below viewport`() {
        assertEquals(0f, visibleFraction(itemStart = 1100, itemEnd = 1500, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction clips top — half occluded above`() {
        assertEquals(0.5f, visibleFraction(itemStart = 0, itemEnd = 200, vStart = 100, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction clips bottom — half occluded below`() {
        assertEquals(0.5f, visibleFraction(itemStart = 800, itemEnd = 1000, vStart = 0, vEnd = 900), 0.001f)
    }

    @Test fun `visibleFraction handles taller-than-viewport — returns visible portion over item size`() {
        assertEquals(2f / 3f, visibleFraction(itemStart = -250, itemEnd = 1250, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction guards zero-size item — returns 0f`() {
        assertEquals(0f, visibleFraction(itemStart = 100, itemEnd = 100, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `scrollOffsetForBoundary forward layout returns 0 — divider top at viewport top`() {
        assertEquals(0, scrollOffsetForBoundary(viewport = 1800, dividerSize = 40, reverseLayout = false))
    }

    @Test fun `scrollOffsetForBoundary reverseLayout returns dividerSize minus viewport`() {
        assertEquals(40 - 1800, scrollOffsetForBoundary(viewport = 1800, dividerSize = 40, reverseLayout = true))
    }

    @Test fun `scrollOffsetForBoundary clamps when divider somehow exceeds viewport`() {
        assertEquals(0, scrollOffsetForBoundary(viewport = 100, dividerSize = 200, reverseLayout = false))
        assertEquals(0, scrollOffsetForBoundary(viewport = 100, dividerSize = 200, reverseLayout = true))
    }
}
