package dev.lyo.hortay.ui.rich

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [mosaicLayout] — the pure collage geometry: per-count templates, gap math, RTL
 * mirroring, derived corner rounding, and the "+N" overflow tile.
 */
class RichMosaicLayoutTest {

    private val width = 400f
    private val gap = 4f

    private fun layout(count: Int, rtl: Boolean = false) =
        mosaicLayout(count = count, width = width, gap = gap, isRtl = rtl)

    @Test
    fun `single item fills the whole box with every corner rounded`() {
        val l = layout(1)
        assertEquals(1, l.cells.size)
        val c = l.cells.single()
        assertEquals(0f, c.left)
        assertEquals(0f, c.top)
        assertEquals(width, c.right)
        assertEquals(width / 1.5f, c.bottom)
        assertTrue(c.roundTopLeft && c.roundTopRight && c.roundBottomLeft && c.roundBottomRight)
        assertEquals(0, c.overflow)
    }

    @Test
    fun `two items split the width in half with a full gap between them`() {
        val l = layout(2)
        assertEquals(2, l.cells.size)
        val (a, b) = l.cells
        // Outer edges flush; inner seam inset gap/2 on each side.
        assertEquals(0f, a.left)
        assertEquals(width / 2f - gap / 2f, a.right)
        assertEquals(width / 2f + gap / 2f, b.left)
        assertEquals(width, b.right)
        // Both span the full height; no vertical seam.
        assertEquals(0f, a.top)
        assertEquals(l.height, a.bottom)
        // The seam between the two tiles is exactly `gap`.
        assertEquals(gap, b.left - a.right, 1e-4f)
    }

    @Test
    fun `two items round only their outer corners`() {
        val (a, b) = layout(2).cells
        assertTrue(a.roundTopLeft && a.roundBottomLeft)
        assertFalse(a.roundTopRight || a.roundBottomRight)
        assertTrue(b.roundTopRight && b.roundBottomRight)
        assertFalse(b.roundTopLeft || b.roundBottomLeft)
    }

    @Test
    fun `three items place one tall start cell and two stacked end cells`() {
        val l = layout(3)
        assertEquals(3, l.cells.size)
        val (big, topEnd, bottomEnd) = l.cells
        // Big cell: left half, full height.
        assertEquals(0f, big.left)
        assertEquals(0f, big.top)
        assertEquals(l.height, big.bottom)
        assertTrue(big.roundTopLeft && big.roundBottomLeft)
        assertFalse(big.roundTopRight || big.roundBottomRight)
        // Two stacked end cells share the right half; only the far corners round.
        assertTrue(topEnd.roundTopRight)
        assertFalse(topEnd.roundBottomRight || topEnd.roundTopLeft || topEnd.roundBottomLeft)
        assertTrue(bottomEnd.roundBottomRight)
        assertFalse(bottomEnd.roundTopRight || bottomEnd.roundTopLeft || bottomEnd.roundBottomLeft)
        // Vertical seam between the two end cells is exactly `gap`.
        assertEquals(gap, bottomEnd.top - topEnd.bottom, 1e-4f)
    }

    @Test
    fun `four items form a square 2x2 grid, each rounding one distinct corner`() {
        val l = layout(4)
        assertEquals(4, l.cells.size)
        assertEquals(width, l.height, 1e-4f) // aspect 1.0
        val (tl, tr, bl, br) = l.cells
        assertTrue(tl.roundTopLeft)
        assertFalse(tl.roundTopRight || tl.roundBottomLeft || tl.roundBottomRight)
        assertTrue(tr.roundTopRight)
        assertTrue(bl.roundBottomLeft)
        assertTrue(br.roundBottomRight)
        // No overflow at exactly four.
        l.cells.forEach { assertEquals(0, it.overflow) }
        // Both seams are exactly `gap`.
        assertEquals(gap, tr.left - tl.right, 1e-4f)
        assertEquals(gap, bl.top - tl.bottom, 1e-4f)
    }

    @Test
    fun `five items show four tiles with a plus-one overflow on the last`() {
        val l = layout(5)
        assertEquals(4, l.cells.size)
        val overflowCell = l.cells.last()
        assertEquals(3, overflowCell.sourceIndex)
        assertEquals(1, overflowCell.overflow)
        assertEquals(MOSAIC_VISIBLE_CAP, overflowCell.viewerIndex())
        // The three leading tiles carry no scrim and open at their own index.
        l.cells.dropLast(1).forEachIndexed { i, c ->
            assertEquals(0, c.overflow)
            assertEquals(i, c.viewerIndex())
        }
    }

    @Test
    fun `six items overflow shows plus-two`() {
        val l = layout(6)
        assertEquals(4, l.cells.size)
        assertEquals(2, l.cells.last().overflow)
        assertEquals(MOSAIC_VISIBLE_CAP, l.cells.last().viewerIndex())
    }

    @Test
    fun `rtl mirrors cell x-positions but keeps reading order`() {
        val ltr = layout(2)
        val rtl = layout(2, rtl = true)
        // Item 0 sits on the left in LTR, on the right in RTL.
        assertEquals(0f, ltr.cells[0].left)
        assertEquals(width, rtl.cells[0].right)
        assertEquals(0f, rtl.cells[1].left)
        // Widths are preserved under the mirror.
        assertEquals(ltr.cells[0].widthPx, rtl.cells[0].widthPx, 1e-4f)
    }

    @Test
    fun `rtl flips which physical corners round`() {
        val (a, _) = layout(2, rtl = true).cells
        // Item 0 now hugs the right edge, so its right corners round instead of its left.
        assertTrue(a.roundTopRight && a.roundBottomRight)
        assertFalse(a.roundTopLeft || a.roundBottomLeft)
    }

    @Test
    fun `rtl three-item layout puts the tall cell on the right`() {
        val l = layout(3, rtl = true)
        val big = l.cells[0]
        assertEquals(width, big.right)
        assertEquals(l.height, big.bottom)
        assertTrue(big.roundTopRight && big.roundBottomRight)
    }
}
