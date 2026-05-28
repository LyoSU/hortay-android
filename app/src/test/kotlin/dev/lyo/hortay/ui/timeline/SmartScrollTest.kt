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

    // scrollToTopAligned / scrollToBoundary use a corrective `scrollBy(delta)` approach
    // based on the row's measured y-position rather than scrollOffset arithmetic. The
    // delta is `item.offset - viewportStartOffset`, which is layout-direction-agnostic;
    // no pure function to unit-test here (LazyListState's offset values come from a
    // real measure pass, not a math formula). Behavior is verified via manual smoke
    // against the running app — see CHANGELOG.
}
