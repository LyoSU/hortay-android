package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupRepliesKeyTest {

    @Test
    fun `thread row keeps reply key when parent backfills later`() {
        val reply = testPost(
            chatId = 1L,
            id = 200L,
            date = 2_000L,
            reply = ReplyPreview(
                authorName = "channel",
                excerpt = "parent",
                isQuote = false,
                replyToChatId = 1L,
                replyToMessageId = 100L,
            ),
        )
        val parent = testPost(chatId = 1L, id = 100L, date = 1_500L)

        val before = groupReplies(listOf(reply))
        val after = groupReplies(listOf(reply, parent))

        assertEquals("post_1_200", before.single().key)
        assertTrue(after.single() is FeedItem.Thread)
        assertEquals(before.single().key, after.single().key)
    }
}
