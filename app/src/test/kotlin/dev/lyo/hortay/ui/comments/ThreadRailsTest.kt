package dev.lyo.hortay.ui.comments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage for [threadRails] — which connector-rail slices each comment row paints for a
 * depth-first pre-order thread.
 */
class ThreadRailsTest {

    @Test
    fun `empty thread yields no rails`() {
        assertEquals(emptyList<RailSegments>(), threadRails(emptyList()))
    }

    @Test
    fun `flat thread draws nothing`() {
        threadRails(listOf(0, 0, 0)).forEach { assertTrue(it.isEmpty) }
    }

    @Test
    fun `parent starts a rail that elbows into its only reply`() {
        val rails = threadRails(listOf(0, 1, 0))
        assertTrue(rails[0].startsOwnRail)
        assertEquals(-1, rails[0].elbowColumn)
        assertEquals(0, rails[1].elbowColumn)
        assertFalse(rails[1].elbowContinues)
        assertFalse(rails[1].startsOwnRail)
        assertTrue(rails[2].isEmpty)
    }

    @Test
    fun `rail continues past a reply's subtree to the next sibling reply`() {
        // A, B (reply to A), C (reply to B), D (reply to A), E.
        val rails = threadRails(listOf(0, 1, 2, 1, 0))
        // B: hooks into A's rail, which carries on down to D; B starts its own rail for C.
        assertEquals(0, rails[1].elbowColumn)
        assertTrue(rails[1].elbowContinues)
        assertTrue(rails[1].startsOwnRail)
        // C: hooks into B's rail (column 1), the last reply there; A's rail crosses C's row.
        assertEquals(1, rails[2].elbowColumn)
        assertFalse(rails[2].elbowContinues)
        assertTrue(rails[2].passesThrough(0))
        assertFalse(rails[2].passesThrough(1))
        // D: A's last reply, the rail stops at its elbow.
        assertEquals(0, rails[3].elbowColumn)
        assertFalse(rails[3].elbowContinues)
        assertEquals(0, rails[3].passThroughMask)
        assertTrue(rails[4].isEmpty)
    }

    @Test
    fun `ancestor rail does not cross rows after its last reply`() {
        // A, B (reply to A), C (reply to B), D (reply to B), E.
        val rails = threadRails(listOf(0, 1, 2, 2, 0))
        assertFalse(rails[1].elbowContinues)
        assertTrue(rails[2].elbowContinues)
        assertFalse(rails[2].passesThrough(0))
        assertFalse(rails[3].elbowContinues)
        assertFalse(rails[3].passesThrough(0))
    }

    @Test
    fun `capped depth replies hook into the same column as their on-screen siblings`() {
        // Visual depths after the indent cap: a depth-3 reply renders at depth 2.
        val rails = threadRails(listOf(0, 1, 2, 2, 2))
        assertTrue(rails[1].startsOwnRail)
        assertFalse(rails[2].startsOwnRail)
        assertEquals(1, rails[2].elbowColumn)
        assertTrue(rails[2].elbowContinues)
        assertTrue(rails[3].elbowContinues)
        assertFalse(rails[4].elbowContinues)
    }

    @Test
    fun `row breaking pre-order gets no elbow`() {
        val rails = threadRails(listOf(0, 2))
        assertEquals(-1, rails[1].elbowColumn)
        assertFalse(rails[0].startsOwnRail)
    }

    @Test
    fun `leading reply without a parent row gets no elbow`() {
        val rails = threadRails(listOf(1, 1))
        assertEquals(-1, rails[0].elbowColumn)
        assertEquals(0, rails[1].elbowColumn)
    }
}
