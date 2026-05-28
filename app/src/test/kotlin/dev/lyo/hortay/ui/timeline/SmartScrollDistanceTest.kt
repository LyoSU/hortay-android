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

    // --- topAlignDelta: pixel nudge for scrollBy that lands a row's TOP edge on the
    //     VISIBLE viewport top, in both layout directions ---
    //
    // delta = currentTopEdge - desiredTopEdge, fed to scrollBy (positive = scroll toward
    // the END = item offsets decrease in BOTH modes).
    //   - forward: top edge = rowOffset;            desired = viewportStartOffset + beforeContentPadding
    //   - reverse: top edge = rowOffset + rowSize;  desired = viewportEndOffset - afterContentPadding
    //
    // On-device reverseLayout feed (Samsung SM, density 2.625) — the ground-truth the
    // rewrite was anchored to: viewportStartOffset = -290, viewportEndOffset = 1786,
    // beforeContentPadding = 290 (NavBar bottom), afterContentPadding = 23 (top 8 dp).
    // before/after SWAP under reverseLayout, so the visible top = 1786 - 23 = 1763.

    @Test
    fun `topAlignDelta forward nudges row offset onto the content-top padding`() {
        // Row 120 px down, visible top at 24 → scroll down (forward) by 96.
        assertEquals(96f, topAlignDelta(rowOffset = 120, rowSize = 400, viewportStartOffset = 0, viewportEndOffset = 1800, beforeContentPadding = 24, afterContentPadding = 0, reverseLayout = false))
        // Row exactly at the visible top → no scroll.
        assertEquals(0f, topAlignDelta(rowOffset = 24, rowSize = 400, viewportStartOffset = 0, viewportEndOffset = 1800, beforeContentPadding = 24, afterContentPadding = 0, reverseLayout = false))
        // Row above the visible top → negative delta scrolls backward to reveal it.
        assertEquals(-50f, topAlignDelta(rowOffset = -26, rowSize = 400, viewportStartOffset = 0, viewportEndOffset = 1800, beforeContentPadding = 24, afterContentPadding = 0, reverseLayout = false))
    }

    @Test
    fun `topAlignDelta reverse short divider freshly brought to the bottom start`() {
        // scrollToItem(divider) lands it at offset 0 (reverse start = visual bottom); the
        // 71 px divider's top edge is 71. Visible top = 1763. Nudge = 71 - 1763 = -1692
        // (negative scrollBy = backward = reverse content moves up to lift it to the top).
        assertEquals(-1692f, topAlignDelta(rowOffset = 0, rowSize = 71, viewportStartOffset = -290, viewportEndOffset = 1786, beforeContentPadding = 290, afterContentPadding = 23, reverseLayout = true))
    }

    @Test
    fun `topAlignDelta reverse divider already at the visible top needs no scroll`() {
        // top edge = 1692 + 71 = 1763 = visible top → delta 0.
        assertEquals(0f, topAlignDelta(rowOffset = 1692, rowSize = 71, viewportStartOffset = -290, viewportEndOffset = 1786, beforeContentPadding = 290, afterContentPadding = 23, reverseLayout = true))
    }

    @Test
    fun `topAlignDelta reverse tall post taller than viewport still top-aligns`() {
        // The exact failing case: a 2250 px post (taller than the 1763 px visible area)
        // landed by the old formula with its top edge at 1982 — above the visible top, so
        // the divider and the post's start were clipped off above. delta = 1982 - 1763 =
        // 219 scrolls it DOWN so the top edge sits at 1763; the tail overflows off-bottom.
        assertEquals(219f, topAlignDelta(rowOffset = -268, rowSize = 2250, viewportStartOffset = -290, viewportEndOffset = 1786, beforeContentPadding = 290, afterContentPadding = 23, reverseLayout = true))
    }

    @Test
    fun `topAlignDelta reverse uses only after-padding, forward uses only before-padding`() {
        // Reverse top edge is driven by (vEnd - after); beforeContentPadding is irrelevant.
        assertEquals((150 - (1786 - 23)).toFloat(), topAlignDelta(rowOffset = 100, rowSize = 50, viewportStartOffset = -290, viewportEndOffset = 1786, beforeContentPadding = 290, afterContentPadding = 23, reverseLayout = true))
        // Forward top edge is driven by (vStart + before); afterContentPadding is irrelevant.
        assertEquals((500 - 24).toFloat(), topAlignDelta(rowOffset = 500, rowSize = 50, viewportStartOffset = 0, viewportEndOffset = 1800, beforeContentPadding = 24, afterContentPadding = 99, reverseLayout = false))
    }
}
