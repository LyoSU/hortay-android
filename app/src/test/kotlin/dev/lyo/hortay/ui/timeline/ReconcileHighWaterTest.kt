package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [reconcileHighWater]: new channels are seeded at their newest date, channels that left the
 * feed lose their mark (so a channel unfollowed and followed again isn't judged against a
 * stale mark that turns its re-seeded history into "new posts"), and an empty feed prunes
 * nothing.
 */
class ReconcileHighWaterTest {

    @Test
    fun `a new channel is seeded at its newest post`() {
        val live = listOf(post(1L, 1L, 100L), post(1L, 2L, 300L), post(2L, 3L, 50L))
        assertEquals(mapOf(1L to 300L, 2L to 50L), reconcileHighWater(mapOf(2L to 50L), live))
    }

    @Test
    fun `a channel that left the feed loses its mark and is re-seeded when it returns`() {
        val marks = mapOf(1L to 100L, 2L to 50L)
        val afterUnfollow = reconcileHighWater(marks, listOf(post(2L, 3L, 50L)))
        assertEquals(mapOf(2L to 50L), afterUnfollow)

        // Re-followed: its seeded history is newer than the old mark, but none of it is pending.
        val afterUndo = reconcileHighWater(afterUnfollow!!, listOf(post(1L, 9L, 900L), post(2L, 3L, 50L)))
        assertEquals(mapOf(1L to 900L, 2L to 50L), afterUndo)
    }

    @Test
    fun `nothing changes when every channel is known and present`() {
        assertNull(reconcileHighWater(mapOf(1L to 100L), listOf(post(1L, 1L, 200L))))
    }

    @Test
    fun `an empty feed prunes nothing`() {
        assertNull(reconcileHighWater(mapOf(1L to 100L), emptyList()))
    }

    private fun post(chatId: Long, id: Long, date: Long) = testPost(id = id, chatId = chatId, date = date)
}
