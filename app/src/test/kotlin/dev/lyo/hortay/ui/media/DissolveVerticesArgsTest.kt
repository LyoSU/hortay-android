package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DissolveVerticesArgsTest {

    private val maxIndices = dissolveQuadIndices(DISSOLVE_MAX_QUADS)

    private fun valid(
        quads: Int,
        verts: FloatArray = FloatArray(quads * 8),
        vertOffset: Int = 0,
        texs: FloatArray? = FloatArray(quads * 8),
        texOffset: Int = 0,
        colors: IntArray? = IntArray(quads * 4),
        colorOffset: Int = 0,
        indices: ShortArray = maxIndices,
        indexOffset: Int = 0,
        indexCount: Int = quads * 6,
        vertexCount: Int = quads * 8,
    ) = dissolveVerticesArgsValid(
        vertexCount, verts, vertOffset, texs, texOffset, colors, colorOffset, indices, indexOffset, indexCount,
    )

    @Test
    fun `the engine's single call shapes are valid up to the quad limit`() {
        assertTrue(valid(1))
        assertTrue(valid(12_000))
        assertTrue(valid(DISSOLVE_MAX_QUADS))
        // Backing pass: no texture coordinates.
        assertTrue(valid(DISSOLVE_MAX_QUADS, texs = null))
    }

    @Test
    fun `a null array must come with offset 0`() {
        assertFalse(valid(16, texs = null, texOffset = 128))
        assertFalse(valid(16, colors = null, colorOffset = 64))
        assertTrue(valid(16, colors = null))
    }

    @Test
    fun `the former two-chunk case of 32768 cells is rejected unless split correctly`() {
        val cells = 2 * DISSOLVE_MAX_QUADS
        val verts = FloatArray(cells * 8)
        val colors = IntArray(cells * 4)
        // One call over all of it would need 131072 vertices: beyond 16-bit indices.
        assertFalse(
            dissolveVerticesArgsValid(cells * 8, verts, 0, null, 0, colors, 0, maxIndices, 0, maxIndices.size),
        )
        // The crashing second chunk: null texs at the chunk's float offset.
        val chunk = DISSOLVE_MAX_QUADS
        assertFalse(
            dissolveVerticesArgsValid(
                chunk * 8, verts, chunk * 8, null, chunk * 8, colors, chunk * 4, maxIndices, 0, chunk * 6,
            ),
        )
        // The same chunk with offsets that exist on non-null arrays only is well-formed.
        assertTrue(
            dissolveVerticesArgsValid(
                chunk * 8, verts, chunk * 8, null, 0, colors, chunk * 4, maxIndices, 0, chunk * 6,
            ),
        )
    }

    @Test
    fun `short arrays and wrong counts are rejected`() {
        assertFalse(valid(16, verts = FloatArray(16 * 8 - 1)))
        assertFalse(valid(16, texs = FloatArray(16 * 8 - 2)))
        assertFalse(valid(16, colors = IntArray(16 * 4 - 1)))
        assertFalse(valid(16, vertexCount = 16 * 8 + 1))
        assertFalse(valid(16, vertexCount = 0))
        assertFalse(valid(16, indexCount = 16 * 6 + 1))
        assertFalse(valid(16, indices = ShortArray(16 * 6 - 3), indexCount = 16 * 6))
        assertFalse(valid(16, verts = FloatArray(16 * 8), vertOffset = 8))
    }

    @Test
    fun `an index naming a vertex past the call's vertices is rejected`() {
        // Indices built for 16 quads, drawn with only 15 quads' worth of vertices.
        val idx = dissolveQuadIndices(16)
        assertFalse(valid(15, indices = idx, indexCount = 16 * 6))
        assertTrue(valid(15, indices = idx, indexCount = 15 * 6))
    }
}
