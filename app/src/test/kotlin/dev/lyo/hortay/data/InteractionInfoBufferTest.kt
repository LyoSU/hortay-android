package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.InteractionInfoBuffer
import dev.lyo.hortay.data.posts.applyInteractionInfo
import kotlinx.collections.immutable.persistentListOf
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteractionInfoBufferTest {

    private fun info(
        views: Int = 0,
        forwards: Int = 0,
        reactions: TdApi.MessageReactions? = null,
        replies: Int? = null,
    ) = TdApi.MessageInteractionInfo().apply {
        viewCount = views
        forwardCount = forwards
        this.reactions = reactions
        replyInfo = replies?.let { n -> TdApi.MessageReplyInfo().apply { replyCount = n } }
    }

    @Test
    fun `a null-reactions heartbeat does not erase a buffered reaction change`() {
        val buffer = InteractionInfoBuffer()
        val reactions = TdApi.MessageReactions()
        buffer.offer(1L, 10L, info(views = 5, reactions = reactions, replies = 3))
        buffer.offer(1L, 10L, info(views = 4, forwards = 0, reactions = null, replies = null))

        val merged = buffer.drain().getValue(1L to 10L)
        assertSame(reactions, merged.reactions)
        assertEquals(3, merged.replyInfo?.replyCount)
        assertEquals(5, merged.viewCount, "views never go down")
    }

    @Test
    fun `a zero forward count keeps the buffered value`() {
        val buffer = InteractionInfoBuffer()
        buffer.offer(1L, 10L, info(forwards = 7))
        buffer.offer(1L, 10L, info(forwards = 0))

        assertEquals(7, buffer.drain().getValue(1L to 10L).forwardCount)
    }

    @Test
    fun `only the first offer of a window asks for a flush`() {
        val buffer = InteractionInfoBuffer()
        assertTrue(buffer.offer(1L, 10L, info(views = 1)))
        assertFalse(buffer.offer(1L, 11L, info(views = 1)))

        assertEquals(2, buffer.drain().size)
        assertTrue(buffer.offer(1L, 12L, info(views = 1)), "after a drain the next offer opens a new window")
        assertEquals(setOf(1L to 12L), buffer.drain().keys)
    }

    @Test
    fun `an update for a non-anchor album member lands on the merged card`() {
        val album = post(id = 100L, views = 50, albumMessageIds = listOf(100L, 101L, 102L))
        val other = post(id = 200L, views = 9)

        val result = applyInteractionInfo(
            persistentListOf(album, other),
            mapOf((-1001L to 102L) to info(views = 70, replies = 4)),
        )

        assertEquals(70, result[0].views)
        assertEquals(4, result[0].commentCount)
        assertSame(other, result[1], "untouched cards keep their identity")
    }

    @Test
    fun `a lagging member never downgrades views and null fields keep the card's values`() {
        val card = post(id = 100L, views = 80, commentCount = 6, albumMessageIds = listOf(100L, 101L))

        val result = applyInteractionInfo(
            persistentListOf(card),
            mapOf((-1001L to 101L) to info(views = 60)),
        )

        assertEquals(80, result[0].views)
        assertEquals(6, result[0].commentCount)
        assertSame(card.reactions, result[0].reactions)
    }

    private fun post(
        id: Long,
        views: Int = 0,
        commentCount: Int? = null,
        albumMessageIds: List<Long> = emptyList(),
    ): TimelinePost = TimelinePost(
        id = id, chatId = -1001L, mediaAlbumId = 0L,
        senderName = "C", senderHandle = null, avatarThumb = null, avatarFileId = null,
        content = PostContent.Text(FormattedText.Empty), views = views, date = 0L, editDate = 0L,
        forwardOrigin = null, authorSignature = null, reply = null,
        reactions = Reactions(0, emptyList()), commentCount = commentCount,
        albumMessageIds = albumMessageIds, parentId = null,
    )
}
