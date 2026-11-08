package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DropOverlappingRangesTest {

    private fun drop(vararg ranges: IntRange): List<IntRange> =
        dropOverlappingRanges(ranges.toList(), start = { it.first }, end = { it.last + 1 })

    @Test
    fun `an overlapping range is dropped but later ranges survive`() {
        // [0,4) consumed; [2,5) overlaps it; [6,8) and [8,10) must still be substituted.
        assertEquals(listOf(0..3, 6..7, 8..9), drop(0..3, 2..4, 6..7, 8..9))
    }

    @Test
    fun `adjacent ranges are all kept`() {
        assertEquals(listOf(0..1, 2..3, 4..5), drop(0..1, 2..3, 4..5))
    }

    @Test
    fun `duplicate starts keep the first`() {
        assertEquals(listOf(0..1, 5..6), drop(0..1, 0..3, 5..6))
    }
}
