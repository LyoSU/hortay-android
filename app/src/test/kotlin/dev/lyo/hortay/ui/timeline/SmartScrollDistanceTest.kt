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

    // --- alignedScrollOffset: "land this post nicely" geometry ---

    @Test
    fun `post that fits is centred — forward`() {
        // gap = 1000 - 400 = 600, split evenly → -300.
        assertEquals(-300, alignedScrollOffset(viewport = 1000, itemSize = 400, reverseLayout = false))
    }

    @Test
    fun `post that fits is centred — reverse, identical to forward`() {
        // Small-post centring is symmetric, so reverseLayout must not change it.
        assertEquals(-300, alignedScrollOffset(viewport = 1000, itemSize = 400, reverseLayout = true))
    }

    @Test
    fun `exact-fit post anchors flush (no gap)`() {
        assertEquals(0, alignedScrollOffset(viewport = 1000, itemSize = 1000, reverseLayout = false))
        assertEquals(0, alignedScrollOffset(viewport = 1000, itemSize = 1000, reverseLayout = true))
    }

    @Test
    fun `tall post top-aligns to zero — forward`() {
        // Taller than the viewport: top at the layout start (= top) → offset 0,
        // tail overflows off the bottom. The old code returned +300 here and
        // clipped the top — the reported bug.
        assertEquals(0, alignedScrollOffset(viewport = 1000, itemSize = 1600, reverseLayout = false))
    }

    @Test
    fun `tall post top-aligns by overflow — reverse`() {
        // reverse layout starts at the bottom; push the item down by its overflow
        // (itemSize - viewport = 600) so the top edge reaches the viewport top.
        assertEquals(600, alignedScrollOffset(viewport = 1000, itemSize = 1600, reverseLayout = true))
    }

    @Test
    fun `very tall post — reverse overflow scales`() {
        assertEquals(1200, alignedScrollOffset(viewport = 800, itemSize = 2000, reverseLayout = true))
    }

    // --- topAnchoredScrollOffset: "land the row top at the viewport top" ---

    @Test
    fun `topAnchored forward returns 0 regardless of item size`() {
        // Forward layout: scrollToItem(idx, 0) already top-aligns; no extra offset.
        assertEquals(0, topAnchoredScrollOffset(viewport = 1800, itemSize = 40, reverseLayout = false))
        assertEquals(0, topAnchoredScrollOffset(viewport = 1800, itemSize = 1200, reverseLayout = false))
        assertEquals(0, topAnchoredScrollOffset(viewport = 1800, itemSize = 3000, reverseLayout = false))
    }

    @Test
    fun `topAnchored reverseLayout short item returns positive layout-end offset`() {
        // In reverseLayout, scrollToItem(idx, 0) BOTTOM-anchors the row: item.offset
        // = 0 maps to visual top y = layoutSize - 0 - itemSize, so the row sits at
        // the visual bottom. Passing scrollOffset = (viewport - itemSize) makes
        // item.offset = viewport - itemSize, and visual top y = layoutSize -
        // (viewport - itemSize) - itemSize = 0. For a 28 dp divider (84 px)
        // in an 1800 px viewport the offset is +1716. Compose's backward-
        // composition fill then drags lower-indexed items BELOW the divider
        // visually so the unread queue stacks under it.
        assertEquals(1800 - 40, topAnchoredScrollOffset(viewport = 1800, itemSize = 40, reverseLayout = true))
        assertEquals(1800 - 1200, topAnchoredScrollOffset(viewport = 1800, itemSize = 1200, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout tall item returns negative overflow offset`() {
        // Tall item (taller than viewport): NEGATIVE scrollOffset pulls the item
        // past the layout-end edge so its visual top reaches y=0; the bottom
        // overflows off-screen below. This is the canonical "show me the header
        // of this long post" landing. For a 3000 px post in an 1800 px viewport:
        // visual top y = 1800 - (-1200) - 3000 = 0. ✓
        assertEquals(1800 - 3000, topAnchoredScrollOffset(viewport = 1800, itemSize = 3000, reverseLayout = true))
        assertEquals(1800 - 2400, topAnchoredScrollOffset(viewport = 1800, itemSize = 2400, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout exact-fit returns zero`() {
        // Item exactly fills viewport: top already at top, no offset needed.
        assertEquals(0, topAnchoredScrollOffset(viewport = 1800, itemSize = 1800, reverseLayout = true))
    }
}
