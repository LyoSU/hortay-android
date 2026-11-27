package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.subscriptions.FollowedChannel
import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelSummaryTest {

    private fun followed(
        chatId: Long,
        title: String = "F$chatId",
        username: String? = "f$chatId",
        readCursor: Long = 0L,
        addedAtMs: Long = 0L,
        status: FollowedChannel.Status = FollowedChannel.Status.Ok,
    ) = FollowedChannel(chatId, username, title, addedAtMs, readCursor, lastSeenMessageId = 0L, status = status)

    @Test
    fun `followed channel without posts still gets a row, after the posted ones`() {
        val posts = listOf(testPost(id = 5, chatId = -1L, date = 1_000))
        val rows = channelSummaries(posts, mapOf(-2L to followed(-2L, title = "Quiet")))
        assertEquals(listOf(-1L, -2L), rows.map { it.chatId })
        val quiet = rows[1]
        assertEquals("Quiet", quiet.title)
        assertTrue(quiet.isFollowed)
        assertEquals(0L, quiet.lastPostDate)
    }

    @Test
    fun `followed row without a stored title falls back to its handle`() {
        val rows = channelSummaries(emptyList(), mapOf(-2L to followed(-2L, title = "", username = "news")))
        assertEquals("@news", rows.single().title)
    }

    @Test
    fun `joined channels are not marked followed and keep no post ids`() {
        val rows = channelSummaries(listOf(testPost(id = 5, chatId = -1L)), emptyMap())
        assertNull(rows.single().followedStatus)
        assertTrue(rows.single().postIds.isEmpty())
    }

    @Test
    fun `followed unread counts posts above the higher of both cursors, albums once`() {
        val posts = listOf(
            testPost(id = 10, chatId = -2L, date = 1),
            testPost(id = 20, chatId = -2L, date = 2, albumMessageIds = listOf(20, 21, 22)),
            testPost(id = 30, chatId = -2L, date = 3),
        )
        val row = channelSummaries(posts, mapOf(-2L to followed(-2L, readCursor = 10))).single()
        assertEquals(2, followedUnreadCount(row, cursor = null))
        assertEquals(1, followedUnreadCount(row, cursor = 22))
        // A partly read album stays unread.
        assertEquals(2, followedUnreadCount(row, cursor = 21))
        assertEquals(0, followedUnreadCount(row, cursor = 30))
    }

    @Test
    fun `only an unavailable followed channel changes the subtitle`() {
        val rows = channelSummaries(
            listOf(testPost(id = 1, chatId = -3L)),
            mapOf(
                -2L to followed(-2L, status = FollowedChannel.Status.Unavailable),
                -3L to followed(-3L),
            ),
        ).associateBy { it.chatId }
        assertTrue(showsUnavailableLine(rows.getValue(-2L)))
        assertFalse(showsUnavailableLine(rows.getValue(-3L)))
    }
}
