package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SpoilerParticleSimTest {

    @Test
    fun `alpha levels round to the nearest bucket and drop invisible particles`() {
        assertEquals(-1, alphaLevel(0f))
        assertEquals(-1, alphaLevel(0.05f))
        assertEquals(0, alphaLevel(1f / SPOILER_ALPHA_LEVELS))
        assertEquals(SPOILER_ALPHA_LEVELS - 1, alphaLevel(1f))
        assertEquals(SPOILER_ALPHA_LEVELS - 1, alphaLevel(1.4f))
        assertEquals(1f, levelAlpha(SPOILER_ALPHA_LEVELS - 1))
    }

    @Test
    fun `fade envelope ramps in and out and is flat in the middle`() {
        assertEquals(0f, fadeEnvelope(0f, 2f, 0.2f))
        assertEquals(0.5f, fadeEnvelope(0.1f, 2f, 0.2f), 1e-6f)
        assertEquals(1f, fadeEnvelope(1f, 2f, 0.2f))
        assertEquals(0.5f, fadeEnvelope(1.9f, 2f, 0.2f), 1e-5f)
        assertEquals(0f, fadeEnvelope(2.5f, 2f, 0.2f))
    }

    @Test
    fun `text particles stay inside the tile and drift at Telegram speeds`() {
        val sim = TextParticleSim(tileSizePx = 300f, count = 500, speedMinPx = 8f, speedMaxPx = 20f, seed = 7)
        val beforeX = sim.x.copyOf()
        val beforeY = sim.y.copyOf()
        val dt = 0.033f
        sim.step(dt)
        var moved = 0
        for (i in 0 until sim.count) {
            assertTrue(sim.x[i] in 0f..300f && sim.y[i] in 0f..300f)
            var dx = abs(sim.x[i] - beforeX[i])
            var dy = abs(sim.y[i] - beforeY[i])
            if (dx > 150f) dx = 300f - dx
            if (dy > 150f) dy = 300f - dy
            val d = sqrt(dx * dx + dy * dy)
            // Respawned particles jump anywhere; the rest move at most maxSpeed * dt.
            if (d <= 20f * dt + 1e-3f) moved++
        }
        assertTrue(moved > sim.count * 0.9, "most particles drift continuously, got $moved")
    }

    @Test
    fun `text particles use Telegram's three alphas at most`() {
        val sim = TextParticleSim(tileSizePx = 200f, count = 300, speedMinPx = 8f, speedMaxPx = 20f, seed = 3)
        repeat(10) { sim.step(0.1f) }
        for (i in 0 until sim.count) assertTrue(sim.alphaOf(i) in 0f..1f)
    }

    @Test
    fun `media particles respect the velocity cap and wrap inside the tile`() {
        val tile = 400f
        val ref = 900f
        val sim = MediaParticleSim(tileSizePx = tile, count = 400, refSizePx = ref, seed = 11)
        val maxStep = MediaParticleSim.MEDIA_MAX_VELOCITY / 100f * ref * 0.033f
        repeat(120) {
            val bx = sim.x.copyOf()
            val by = sim.y.copyOf()
            sim.step(0.033f)
            for (i in 0 until sim.count) {
                assertTrue(sim.x[i] >= 0f && sim.x[i] < tile)
                assertTrue(sim.y[i] >= 0f && sim.y[i] < tile)
                assertTrue(sim.alphaOf(i) in 0f..1f)
                var dx = abs(sim.x[i] - bx[i])
                var dy = abs(sim.y[i] - by[i])
                if (dx > tile / 2) dx = tile - dx
                if (dy > tile / 2) dy = tile - dy
                // Either within the cap, or a respawn (velocity reset, new spot).
                val d = sqrt(dx * dx + dy * dy)
                if (d > maxStep + 1e-2f) {
                    assertEquals(0f, sim.alphaOf(i), 1e-6f)
                }
            }
        }
    }

    @Test
    fun `curl field is periodic over one tile`() {
        val phase = FloatArray(CURL_TERMS) { it * 0.7f }
        val a = FloatArray(2)
        val b = FloatArray(2)
        for ((u, v) in listOf(0.1f to 0.2f, 0.73f to 0.05f, 0.5f to 0.99f)) {
            curl(u, v, 1.3f, phase, a)
            curl(u + 1f, v - 1f, 1.3f, phase, b)
            assertEquals(a[0], b[0], 1e-3f)
            assertEquals(a[1], b[1], 1e-3f)
        }
    }

    @Test
    fun `curl field is divergence free`() {
        val phase = FloatArray(CURL_TERMS) { it * 1.1f }
        val px = FloatArray(2)
        val mx = FloatArray(2)
        val py = FloatArray(2)
        val my = FloatArray(2)
        val h = 1e-3f
        for ((u, v) in listOf(0.3f to 0.4f, 0.81f to 0.66f)) {
            curl(u + h, v, 0.5f, phase, px)
            curl(u - h, v, 0.5f, phase, mx)
            curl(u, v + h, 0.5f, phase, py)
            curl(u, v - h, 0.5f, phase, my)
            val div = (px[0] - mx[0]) / (2 * h) + (py[1] - my[1]) / (2 * h)
            assertEquals(0f, div, 0.5f)
        }
    }

    @Test
    fun `fill writes wrapped copies for particles near the tile edges`() {
        val sim = object : SpoilerParticleSim(tileSizePx = 100f, count = 3, seed = 1) {
            override fun step(dtSec: Float) = Unit
            override fun alphaOf(i: Int): Float = 1f
        }
        sim.x[0] = 50f; sim.y[0] = 50f // middle: one point
        sim.x[1] = 0.5f; sim.y[1] = 50f // left edge: + right copy
        sim.x[2] = 99.5f; sim.y[2] = 99.5f // corner: + x, y and diagonal copies
        val batches = Array(SPOILER_ALPHA_LEVELS) { FloatArray(2) }
        val counts = IntArray(SPOILER_ALPHA_LEVELS)
        sim.fill(batches, counts, halfStrokePx = 1f)
        val top = SPOILER_ALPHA_LEVELS - 1
        assertEquals((1 + 2 + 4) * 2, counts[top])
        val pts = batches[top].copyOf(counts[top]).toList().chunked(2).map { it[0] to it[1] }.toSet()
        assertTrue((100.5f to 50f) in pts)
        assertTrue((-0.5f to 99.5f) in pts)
        assertTrue((99.5f to -0.5f) in pts)
        assertTrue((-0.5f to -0.5f) in pts)
        for (l in 0 until top) assertEquals(0, counts[l])
    }
}
