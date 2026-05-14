package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PendingScrollResolverTest {

    private fun post(
        chatId: Long,
        id: Long,
        album: List<Long> = emptyList(),
    ): TimelinePost = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = 0L,
        senderName = "ch",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("body")),
        views = 0,
        date = 0L,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = album,
    )

    @Test
    fun `returns index of direct match`() {
        val items = listOf(
            FeedItem.Single(post(1L, 100L)),
            FeedItem.Single(post(1L, 200L)),
            FeedItem.Single(post(1L, 300L)),
        )
        assertEquals(1, resolveTargetIndex(items, 1L, 200L))
    }

    @Test
    fun `returns index of album member match`() {
        val items = listOf(
            FeedItem.Single(post(1L, 100L)),
            FeedItem.Single(post(1L, 200L, album = listOf(200L, 201L, 202L))),
        )
        assertEquals(1, resolveTargetIndex(items, 1L, 202L))
    }

    @Test
    fun `returns minus one when target missing`() {
        val items = listOf(FeedItem.Single(post(1L, 100L)))
        assertEquals(-1, resolveTargetIndex(items, 1L, 999L))
    }

    @Test
    fun `returns minus one for different chatId`() {
        val items = listOf(FeedItem.Single(post(1L, 100L)))
        assertEquals(-1, resolveTargetIndex(items, 2L, 100L))
    }
}
