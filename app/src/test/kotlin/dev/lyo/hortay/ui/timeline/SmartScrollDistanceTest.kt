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

    // --- topAnchoredScrollOffset: "land the row top at the VISIBLE viewport top" ---
    //
    // Formula: itemSize - mainAxisAvailableSize - beforeContentPadding (reverseLayout)
    // → places the row at visual y = afterContentPadding (= visible-area top) in the
    // layout container, NOT at y=0 of the layout (which would be inside the
    // afterContentPadding strip in reverseLayout).
    //
    // Real-world setup for the timeline LazyColumn:
    //   contentPadding = PaddingValues(top = 8.dp, bottom = ~80.dp)
    //   reverseLayout = true (Newest at the bottom)
    //   → beforeContentPadding = bottomPadding ≈ 240 px (NavBar reservation)
    //   → afterContentPadding = topPadding ≈ 24 px
    //   → mainAxisAvailableSize = visible-area height ≈ 1800 px

    @Test
    fun `topAnchored forward returns 0 regardless of item size or padding`() {
        // Forward layout: scrollToItem(idx, 0) already top-aligns; no extra offset.
        assertEquals(0, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 40, reverseLayout = false))
        assertEquals(0, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 0, itemSize = 1200, reverseLayout = false))
        assertEquals(0, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 3000, reverseLayout = false))
    }

    @Test
    fun `topAnchored reverseLayout short divider with NavBar padding`() {
        // 28 dp boundary divider (~84 px), 80 dp NavBar (~240 px before-padding):
        // scrollOffset = 84 - 1800 - 240 = -1956. After the measure pass's backward-
        // composition loop, the divider lands at scroll-axis 1956, visual y =
        // (1800 + 240 + afterContentPadding) - 1956 - 84 = afterContentPadding. ✓
        assertEquals(84 - 1800 - 240, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 84, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout short divider with no padding falls back to itemSize - viewport`() {
        // When beforeContentPadding=0 the formula collapses to the original
        // itemSize - mainAxisAvailableSize — the c64b4c4 form.
        assertEquals(40 - 1800, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 0, itemSize = 40, reverseLayout = true))
        assertEquals(1200 - 1800, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 0, itemSize = 1200, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout tall post overflow with padding`() {
        // 3000 px post in 1800 px visible area, 240 px NavBar:
        // scrollOffset = 3000 - 1800 - 240 = 960. Formula is positive → backward-
        // composition loop skipped. Post's visual top reaches `afterContentPadding`;
        // the bottom overflows off-screen below the visible area.
        assertEquals(960, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 3000, reverseLayout = true))
        assertEquals(360, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 2400, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout tall post no padding falls back to itemSize - viewport`() {
        assertEquals(3000 - 1800, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 0, itemSize = 3000, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout exact-fit with no padding returns zero`() {
        // Item exactly fills the visible area and no NavBar → scrollOffset = 0.
        assertEquals(0, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 0, itemSize = 1800, reverseLayout = true))
    }

    @Test
    fun `topAnchored reverseLayout exact-fit with NavBar padding`() {
        // Exact-fit + padding: scrollOffset = 1800 - 1800 - 240 = -240. The
        // backward-composition loop consumes the -240 across the beforeContentPadding
        // zone so the item still lands top-aligned with the visible area.
        assertEquals(-240, topAnchoredScrollOffset(mainAxisAvailableSize = 1800, beforeContentPadding = 240, itemSize = 1800, reverseLayout = true))
    }
}
