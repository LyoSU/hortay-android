package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpoilerRevealStateTest {

    @Test
    fun `a fresh text is covered`() {
        val state = SpoilerRevealState()
        assertEquals(SPOILER_COVERED, state.revealStart())
        assertFalse(0 in state.revealedGroups)
        assertFalse(7 in state.revealedGroups)
    }

    @Test
    fun `reveal runs the ripple, then stays revealed after it settles`() {
        val state = SpoilerRevealState()

        assertTrue(state.reveal(nowMs = 1_000L))
        assertEquals(1_000L, state.revealStart())
        assertTrue(0 in state.revealedGroups)
        assertTrue(42 in state.revealedGroups)

        state.settle()
        assertEquals(SPOILER_SETTLED, state.revealStart())
        assertTrue(0 in state.revealedGroups)
        assertTrue(42 in state.revealedGroups)
    }

    @Test
    fun `a second tap neither restarts the ripple nor re-covers`() {
        val state = SpoilerRevealState()
        state.reveal(nowMs = 1_000L)

        assertFalse(state.reveal(nowMs = 1_200L))
        assertEquals(1_000L, state.revealStart())

        state.settle()
        assertFalse(state.reveal(nowMs = 2_000L))
        assertEquals(SPOILER_SETTLED, state.revealStart())
    }

    @Test
    fun `the remember key changes exactly once, on reveal`() {
        val state = SpoilerRevealState()
        val keys = mutableListOf<Any>(state.revealedGroups)
        state.reveal(nowMs = 1_000L)
        keys += state.revealedGroups
        state.reveal(nowMs = 1_100L)
        keys += state.revealedGroups
        state.settle()
        keys += state.revealedGroups

        assertNotEquals(keys[0], keys[1])
        assertEquals(keys[1], keys[2])
        assertEquals(keys[1], keys[3])
        assertEquals(2, keys.distinct().size)
    }
}
