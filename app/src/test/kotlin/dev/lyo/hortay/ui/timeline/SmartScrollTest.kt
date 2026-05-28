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

    @Test fun `topAlignedScrollOffset forward layout returns 0`() {
        // Forward layout: scrollOffset = 0 puts the item's top at viewport top
        // regardless of item size.
        assertEquals(0, topAlignedScrollOffset(viewport = 1800, itemSize = 40, reverseLayout = false))
        assertEquals(0, topAlignedScrollOffset(viewport = 1800, itemSize = 1200, reverseLayout = false))
        assertEquals(0, topAlignedScrollOffset(viewport = 1800, itemSize = 3000, reverseLayout = false))
    }

    @Test fun `topAlignedScrollOffset short item in reverseLayout returns itemSize minus viewport`() {
        // Negative value pulls the item's bottom up to (viewport - itemSize) above
        // the layout start = itemSize below the viewport top. Item top at viewport top.
        assertEquals(40 - 1800, topAlignedScrollOffset(viewport = 1800, itemSize = 40, reverseLayout = true))
        assertEquals(1200 - 1800, topAlignedScrollOffset(viewport = 1800, itemSize = 1200, reverseLayout = true))
    }

    @Test fun `topAlignedScrollOffset tall item in reverseLayout returns positive offset to keep header visible`() {
        // The user-facing bug fix: a tall post in reverseLayout would otherwise
        // bottom-anchor with scrollOffset=0 and clip the header off-screen above.
        // Positive offset shifts the item past the layout start so its TOP comes
        // up to viewport top; the bottom overflows below.
        assertEquals(3000 - 1800, topAlignedScrollOffset(viewport = 1800, itemSize = 3000, reverseLayout = true))
        assertEquals(2400 - 1800, topAlignedScrollOffset(viewport = 1800, itemSize = 2400, reverseLayout = true))
    }
}
