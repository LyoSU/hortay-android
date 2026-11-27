package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostChainTest {

    private fun post(
        chatId: Long,
        id: Long,
        replyTo: Pair<Long, Long>? = null,
        isQuote: Boolean = false,
        mediaAlbumId: Long = 0L,
        albumIds: List<Long> = emptyList(),
        parentId: Long? = null,
        isDeleted: Boolean = false,
    ): TimelinePost = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = mediaAlbumId,
        senderName = "ch$chatId",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("body")),
        views = 0,
        date = id * 1000L,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = replyTo?.let { (chat, msg) ->
            ReplyPreview(
                authorName = "ch$chat",
                excerpt = "parent",
                isQuote = isQuote,
                replyToChatId = chat,
                replyToMessageId = msg,
            )
        },
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = albumIds,
        parentId = parentId,
        isDeleted = isDeleted,
    )

    /** Data order is newest first, as every feed hands it over. */
    private fun chains(vararg newestFirst: TimelinePost, reverse: Boolean): List<PostChain> =
        withPostChains(newestFirst.map { FeedItem.Post(it) }, reverseLayout = reverse)
            .map { (it as FeedItem.Post).chain }

    @Test
    fun `newest on top - siblings under one parent link, the topmost keeps its quote`() {
        val parent = post(chatId = 1, id = 10)
        val r1 = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val r2 = post(chatId = 1, id = 12, replyTo = 1L to 10L)
        val r3 = post(chatId = 1, id = 13, replyTo = 1L to 10L)

        // Drawn top to bottom: r3, r2, r1, parent. r2 and r1 sit under a sibling; the parent
        // has no reply and does not join r1 (r1 sits above it).
        assertEquals(
            listOf(PostChain.Start, PostChain.Middle, PostChain.End, PostChain.None),
            chains(r3, r2, r1, parent, reverse = false),
        )
    }

    @Test
    fun `newest at the bottom - the parent heads the thread and every reply drops its quote`() {
        val parent = post(chatId = 1, id = 10)
        val r1 = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val r2 = post(chatId = 1, id = 12, replyTo = 1L to 10L)

        // Drawn top to bottom: parent, r1, r2 (data stays newest first).
        assertEquals(
            listOf(PostChain.End, PostChain.Middle, PostChain.Start),
            chains(r2, r1, parent, reverse = true),
        )
    }

    @Test
    fun `newest at the bottom - a reply to the reply above chains`() {
        val parent = post(chatId = 1, id = 10)
        val r1 = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val r2 = post(chatId = 1, id = 12, replyTo = 1L to 11L)

        assertEquals(
            listOf(PostChain.End, PostChain.Middle, PostChain.Start),
            chains(r2, r1, parent, reverse = true),
        )
    }

    @Test
    fun `a post of another channel in between breaks the chain`() {
        val parent = post(chatId = 1, id = 10)
        val r1 = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val other = post(chatId = 2, id = 50)
        val r2 = post(chatId = 1, id = 12, replyTo = 1L to 10L)

        assertEquals(
            listOf(PostChain.None, PostChain.None, PostChain.End, PostChain.Start),
            chains(r2, other, r1, parent, reverse = true),
        )
    }

    @Test
    fun `the unread divider between two rows breaks the chain`() {
        val parent = post(chatId = 1, id = 10)
        val r1 = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val rows = listOf(FeedItem.Post(r1), FeedItem.Boundary(epoch = 0L), FeedItem.Post(parent))

        val result = withPostChains(rows, reverseLayout = true)

        assertTrue(result.filterIsInstance<FeedItem.Post>().all { it.chain == PostChain.None })
        assertTrue(result[1] is FeedItem.Boundary)
    }

    @Test
    fun `cross-chat replies never chain`() {
        val parent = post(chatId = 1, id = 10)
        // Replies to chat 1's post 10, posted in chat 2: another channel's post.
        val foreign = post(chatId = 2, id = 10, replyTo = 1L to 10L)
        val foreignSibling = post(chatId = 2, id = 11, replyTo = 1L to 10L)

        assertEquals(listOf(PostChain.None, PostChain.None), chains(foreign, parent, reverse = true))
        assertEquals(listOf(PostChain.None, PostChain.None), chains(foreignSibling, foreign, reverse = false))
    }

    @Test
    fun `a reply to any member of an album above chains`() {
        val album = post(chatId = 1, id = 20, mediaAlbumId = 7L, albumIds = listOf(20L, 21L, 22L))
        val reply = post(chatId = 1, id = 30, replyTo = 1L to 22L)

        assertEquals(listOf(PostChain.End, PostChain.Start), chains(reply, album, reverse = true))
    }

    @Test
    fun `album replies chain as siblings`() {
        val parent = post(chatId = 1, id = 10)
        val albumReply = post(chatId = 1, id = 20, replyTo = 1L to 10L, mediaAlbumId = 7L, albumIds = listOf(20L, 21L))
        val reply = post(chatId = 1, id = 30, replyTo = 1L to 10L)

        assertEquals(
            listOf(PostChain.Start, PostChain.End, PostChain.None),
            chains(reply, albumReply, parent, reverse = false),
        )
    }

    @Test
    fun `a hand-picked quote keeps its card`() {
        val parent = post(chatId = 1, id = 10)
        val quote = post(chatId = 1, id = 11, replyTo = 1L to 10L, isQuote = true)

        assertEquals(listOf(PostChain.None, PostChain.None), chains(quote, parent, reverse = true))
    }

    @Test
    fun `comments and tombstones never chain`() {
        val parent = post(chatId = 1, id = 10)
        val comment = post(chatId = 1, id = 11, replyTo = 1L to 10L, parentId = 10L)
        val deleted = post(chatId = 1, id = 12, replyTo = 1L to 10L, isDeleted = true)

        assertEquals(listOf(PostChain.None, PostChain.None), chains(comment, parent, reverse = true))
        assertEquals(listOf(PostChain.None, PostChain.None), chains(deleted, parent, reverse = true))
    }

    @Test
    fun `rows made neighbours by hidden posts do not link`() {
        val parent = post(chatId = 1, id = 10)
        val hidden = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val r2 = post(chatId = 1, id = 12, replyTo = 1L to 10L)
        val source = listOf(r2, hidden, parent)

        val result = withPostChains(listOf(FeedItem.Post(r2), FeedItem.Post(parent)), reverseLayout = true, source = source)

        assertEquals(listOf(PostChain.None, PostChain.None), result.map { (it as FeedItem.Post).chain })
    }

    @Test
    fun `a folded series row links only on the side away from its folded members`() {
        // Series kept row = its OLDEST post; the folded members are the newer ones above it in data order.
        val parent = post(chatId = 1, id = 10)
        val kept = post(chatId = 1, id = 11, replyTo = 1L to 10L)
        val folded = (12L..14L).map { post(chatId = 1, id = it, replyTo = 1L to 10L) }
        val newer = post(chatId = 1, id = 15, replyTo = 1L to 10L)
        val source = listOf(newer) + folded.reversed() + listOf(kept, parent)
        val visible = listOf(newer, kept, parent).map { FeedItem.Post(it) }

        val result = withPostChains(visible, reverseLayout = true, source = source)

        assertEquals(
            listOf(PostChain.None, PostChain.End, PostChain.Start),
            result.map { (it as FeedItem.Post).chain },
        )
    }

    @Test
    fun `chain is not part of the row key and unchanged rows keep their instance`() {
        val parent = post(chatId = 1, id = 10)
        val other = post(chatId = 2, id = 50)
        val rows = listOf(FeedItem.Post(other), FeedItem.Post(parent))

        val result = withPostChains(rows, reverseLayout = false)
        assertSame(rows[0], result[0])
        assertSame(rows[1], result[1])

        val chained = FeedItem.Post(parent, PostChain.Middle)
        assertEquals(FeedItem.Post(parent).key, chained.key)
        assertFalse(PostChain.None.joinsAbove || PostChain.None.joinsBelow)
    }
}
