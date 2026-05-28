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

    @Test fun `visibleFraction clamps divisor to viewport — tall item fully filling viewport reads 1f`() {
        // 1500-px item entirely spans the 1000-px viewport from -250 to 1250. Old formula
        // returned 1000/1500 = 0.667 (visible / item-size); the new formula clamps the
        // divisor to min(item-size, viewport) so a post that completely fills the
        // viewport always reads 1.0 regardless of how far it overflows top/bottom.
        assertEquals(1f, visibleFraction(itemStart = -250, itemEnd = 1250, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction tall item — 28dp divider above eats 28 of viewport, still acks`() {
        // The boundary-jump bug: a 3000-px post landed with its top 28 px below the
        // viewport top (because the UnreadBoundaryRow sits above it). Visible portion
        // is 1772 px of an 1800-px viewport. Old formula: 1772/3000 = 0.591 — JUST
        // below the 0.6 ack threshold → never marked read → counter never decrements.
        // New formula: divisor = min(3000, 1800) = 1800 → 1772/1800 ≈ 0.984 → ack.
        val fraction = visibleFraction(itemStart = 28, itemEnd = 28 + 3000, vStart = 0, vEnd = 1800)
        assertEquals(1772f / 1800f, fraction, 0.001f)
        assert(fraction >= 0.6f) { "tall post with 28dp divider above must clear the 0.6 dwell-ack threshold" }
    }

    @Test fun `visibleFraction tall item — 50 percent of viewport occupied still fails the threshold`() {
        // Sanity check: clamping the divisor doesn't accidentally lower the bar so far
        // that a half-scrolled tall post acks. A 3000-px post with only 900 px in the
        // 1800-px viewport reads 900/1800 = 0.5 — still below 0.6.
        val fraction = visibleFraction(itemStart = -2100, itemEnd = 900, vStart = 0, vEnd = 1800)
        assertEquals(0.5f, fraction, 0.001f)
        assert(fraction < 0.6f) { "tall post only half-filling the viewport must NOT cross the dwell-ack threshold" }
    }

    @Test fun `visibleFraction caps at 1f for over-spilling tall item`() {
        // Extremely tall item (5000 px) entirely covers the 1000-px viewport. Without
        // the `coerceAtMost(1f)` guard the formula would return 1000/1000 = 1.0 exactly,
        // but the guard is the contract: the function's range is documented as [0f, 1f].
        assertEquals(1f, visibleFraction(itemStart = -2000, itemEnd = 3000, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction short item behaviour unchanged — 60 percent visible reads as 0_6`() {
        // Regression guard for short posts: the formula must NOT change for items that
        // fit inside the viewport. A 500-px post with 300 px visible still reads 0.6.
        assertEquals(0.6f, visibleFraction(itemStart = 0, itemEnd = 500, vStart = 200, vEnd = 1800), 0.001f)
    }

    @Test fun `visibleFraction guards zero-size item — returns 0f`() {
        assertEquals(0f, visibleFraction(itemStart = 100, itemEnd = 100, vStart = 0, vEnd = 1000), 0.001f)
    }

    @Test fun `visibleFraction guards zero-size viewport — returns 0f`() {
        // Hypothetical: a fold-on-scroll viewport collapsed to zero. The function
        // must not divide by zero — return 0f and let the caller skip.
        assertEquals(0f, visibleFraction(itemStart = 0, itemEnd = 500, vStart = 100, vEnd = 100), 0.001f)
    }
}
