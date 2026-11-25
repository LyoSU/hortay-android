package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

class DissolveFieldTest {

    @Test
    fun `motion time is zero before the front, eases in, then runs linearly`() {
        assertEquals(0f, dissolveMotionTime(0.2f, 0.5f))
        assertEquals(0f, dissolveMotionTime(0.5f, 0.5f))
        val half = DISSOLVE_RAMP_S / 2f
        assertEquals(half * half / (2f * DISSOLVE_RAMP_S), dissolveMotionTime(0.5f + half, 0.5f), 1e-6f)
        assertEquals(1f - DISSOLVE_RAMP_S / 2f, dissolveMotionTime(1.5f, 0.5f), 1e-6f)
        // Continuous at the end of the ramp and monotonic throughout.
        val atRampEnd = dissolveMotionTime(0.5f + DISSOLVE_RAMP_S, 0.5f)
        assertEquals(DISSOLVE_RAMP_S / 2f, atRampEnd, 1e-6f)
        var previous = 0f
        var t = 0f
        while (t < 3f) {
            val m = dissolveMotionTime(t, 0.5f)
            assertTrue(m >= previous)
            previous = m
            t += 0.01f
        }
    }

    @Test
    fun `a cell is opaque until late in its life and gone at its end time`() {
        val life = 1f
        assertEquals(1f, dissolveAlpha(life, 0f))
        assertEquals(1f, dissolveAlpha(life, 0.3f))
        assertTrue(dissolveAlpha(life, 0.7f) in 0f..1f)
        assertEquals(0f, dissolveAlpha(life, life / DISSOLVE_DECAY))
        val start = 0.2f
        val end = dissolveEndTime(start, life)
        assertEquals(0f, dissolveAlpha(life, dissolveMotionTime(end, start)), 1e-5f)
        assertTrue(dissolveAlpha(life, dissolveMotionTime(end - 0.05f, start)) > 0f)
    }

    @Test
    fun `the sweep reaches the leading edge first and the trailing edge last`() {
        assertTrue(dissolveStart(0f, 0f) < 0f)
        assertTrue(dissolveStart(0f, 1f) < dissolveStart(1f, 0f))
        assertEquals(DISSOLVE_SWEEP_S, dissolveStart(1f, 0f) - dissolveStart(0f, 0f), 1e-6f)
    }

    @Test
    fun `the whole effect lasts roughly one and a half seconds of wall time`() {
        val wallSeconds = DISSOLVE_MAX_END_S / DISSOLVE_TIME_SCALE
        assertTrue(wallSeconds in 0.9f..1.8f, "was $wallSeconds")
    }

    @Test
    fun `grid stays within the cell budget and the minimum cell size`() {
        val dense = dissolveGrid(1080f, 600f, minCellPx = 4f, maxCells = 12_000)
        assertTrue(dense.count <= 12_000)
        assertTrue(1080f / dense.cols >= 4f * 0.9f)
        assertTrue(600f / dense.rows >= 4f * 0.9f)
        val small = dissolveGrid(40f, 20f, minCellPx = 4f, maxCells = 12_000)
        assertEquals(10, small.cols)
        assertEquals(5, small.rows)
        val capped = dissolveGrid(1000f, 1000f, minCellPx = 1f, maxCells = 100)
        assertTrue(capped.count <= 100)
        assertTrue(capped.count >= 64, "too coarse: ${capped.count}")
        assertEquals(0, dissolveGrid(0f, 100f, 4f, 100).count)
        assertEquals(0, dissolveGrid(100f, 100f, 4f, 0).count)
    }

    @Test
    fun `quad indices describe two triangles per quad`() {
        val idx = dissolveQuadIndices(2)
        assertEquals(listOf<Short>(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7), idx.toList())
        // The last addressable vertex wraps to a negative short; Skia reads it unsigned.
        val full = dissolveQuadIndices(DISSOLVE_MAX_QUADS)
        assertEquals(65535, full.last().toInt() and 0xFFFF)
        assertThrows(IllegalArgumentException::class.java) { dissolveQuadIndices(DISSOLVE_MAX_QUADS + 1) }
    }

    private fun field(rtl: Boolean = false, texScale: Float = 1f) = DissolveField(
        left = 10f,
        top = 20f,
        width = 100f,
        height = 50f,
        texScale = texScale,
        grid = DissolveGrid(cols = 10, rows = 5),
        dp = 2f,
        rtl = rtl,
        seed = 42,
        backingColor = 0x00F5F0FA,
    )

    @Test
    fun `before the front arrives the cells tile the picture exactly`() {
        val f = field()
        f.update(-1f)
        for (r in 0 until 5) {
            for (c in 0 until 10) {
                val v = (r * 10 + c) * 8
                assertEquals(10f + c * 10f, f.verts[v], 1e-4f)
                assertEquals(20f + r * 10f, f.verts[v + 1], 1e-4f)
                assertEquals(10f + (c + 1) * 10f, f.verts[v + 4], 1e-4f)
                assertEquals(20f + (r + 1) * 10f, f.verts[v + 5], 1e-4f)
                assertEquals(255, f.alphaOf(r * 10 + c))
            }
        }
    }

    @Test
    fun `texture coordinates follow the capture scale`() {
        val f = field(texScale = 0.5f)
        // Cell (row 1, col 2): picture-local (20, 10)..(30, 20) at half scale.
        val t = (1 * 10 + 2) * 8
        assertEquals(10f, f.texs[t], 1e-4f)
        assertEquals(5f, f.texs[t + 1], 1e-4f)
        assertEquals(15f, f.texs[t + 4], 1e-4f)
        assertEquals(10f, f.texs[t + 5], 1e-4f)
    }

    @Test
    fun `the leading column moves first, mirrored in RTL`() {
        val ltr = field()
        ltr.update(0.05f)
        assertTrue(moved(ltr, row = 2, col = 0))
        assertFalse(moved(ltr, row = 2, col = 9))
        val rtl = field(rtl = true)
        rtl.update(0.05f)
        assertTrue(moved(rtl, row = 2, col = 9))
        assertFalse(moved(rtl, row = 2, col = 0))
    }

    @Test
    fun `every cell is gone once the end time passes`() {
        val f = field()
        assertTrue(f.endTime <= DISSOLVE_MAX_END_S + 1e-4f)
        f.update(f.endTime + 0.01f)
        for (i in 0 until f.count) assertEquals(0, f.alphaOf(i))
        f.update(f.endTime * 0.6f)
        assertTrue((0 until f.count).any { f.alphaOf(it) in 1..254 }, "no cell mid-fade")
    }

    @Test
    fun `particles drift upward on average`() {
        val cols = 40
        val rows = 20
        val f = DissolveField(0f, 0f, 400f, 200f, 1f, DissolveGrid(cols, rows), dp = 2f, rtl = false, seed = 7, backingColor = 0)
        f.update(1.0f)
        var dy = 0f
        var n = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                if (f.alphaOf(i) == 0) continue
                val v = i * 8
                val cy = (f.verts[v + 1] + f.verts[v + 5]) / 2f
                dy += cy - (r + 0.5f) * 10f
                n++
            }
        }
        assertTrue(n > 0)
        assertTrue(dy / n < 0f, "mean dy ${dy / n}")
    }

    @Test
    fun `at-rest cells are backed opaquely and released cells are not`() {
        val f = field()
        f.update(-1f)
        assertEquals(f.count, f.restCount)
        assertEquals(0xFFF5F0FA.toInt(), f.backColors[0])
        for (i in 0 until f.count) {
            assertTrue(f.isAtRest(i))
            // The backing quad sits exactly on the cell.
            for (k in 0 until 8) assertEquals(f.verts[i * 8 + k], f.backVerts[i * 8 + k], 1e-4f)
        }
        f.update(0.05f)
        val leading = 2 * 10
        val trailing = 2 * 10 + 9
        assertFalse(f.isAtRest(leading))
        assertEquals(f.backVerts[leading * 8], f.backVerts[leading * 8 + 2])
        assertTrue(f.isAtRest(trailing))
        assertTrue(f.restCount in 1 until f.count)
        f.update(f.endTime)
        assertEquals(0, f.restCount)
    }

    @Test
    fun `released cells shrink as they fly`() {
        val f = field()
        f.update(0.6f)
        val i = 2 * 10
        val v = i * 8
        assertTrue(f.alphaOf(i) > 0)
        assertTrue(f.verts[v + 2] - f.verts[v] < 10f * 0.9f, "width ${f.verts[v + 2] - f.verts[v]}")
    }

    @Test
    fun `fast sine tracks the real one`() {
        var x = -20f
        while (x < 20f) {
            assertTrue(abs(fastSin(x) - sin(x)) < 0.002f, "x=$x")
            x += 0.013f
        }
    }

    private fun moved(f: DissolveField, row: Int, col: Int): Boolean {
        val v = (row * 10 + col) * 8
        val restLeft = 10f + col * 10f
        val restTop = 20f + row * 10f
        return abs(f.verts[v] - restLeft) > 1e-4f || abs(f.verts[v + 1] - restTop) > 1e-4f
    }
}
