package dev.lyo.hortay.ui.comments

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Coverage for [quotesAnchor] — when a comment's quote tap targets the post above the thread. */
class QuotesAnchorTest {

    private val channel = -100L
    private val discussion = -200L

    private val anchor = TimelinePost(
        id = 50L,
        chatId = channel,
        mediaAlbumId = 0L,
        senderName = "ch",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("The quick brown fox jumps over the lazy dog")),
        views = 0,
        date = 0L,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = listOf(50L, 51L),
    )

    private fun reply(chatId: Long, messageId: Long, excerpt: String) = ReplyPreview(
        authorName = "a",
        excerpt = excerpt,
        isQuote = true,
        replyToChatId = chatId,
        replyToMessageId = messageId,
    )

    @Test
    fun `reply naming the channel post or an album sibling targets the anchor`() {
        assertTrue(quotesAnchor(reply(channel, 50L, "anything"), anchor, discussion))
        assertTrue(quotesAnchor(reply(channel, 51L, "anything"), anchor, discussion))
    }

    @Test
    fun `reply to the discussion mirror targets the anchor only when the quote is in the post`() {
        assertTrue(quotesAnchor(reply(discussion, 900L, "brown fox jumps"), anchor, discussion))
        assertFalse(quotesAnchor(reply(discussion, 900L, "words from an older comment"), anchor, discussion))
    }

    @Test
    fun `reply into another chat or another channel post is not the anchor`() {
        assertFalse(quotesAnchor(reply(channel, 49L, "brown fox jumps"), anchor, discussion))
        assertFalse(quotesAnchor(reply(-300L, 50L, "brown fox jumps"), anchor, discussion))
    }
}
