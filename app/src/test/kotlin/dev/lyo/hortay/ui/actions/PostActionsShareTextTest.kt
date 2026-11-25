package dev.lyo.hortay.ui.actions

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Share keeps the link of a protected post but never its text. */
class PostActionsShareTextTest {

    private fun post(protectedContent: Boolean) = TimelinePost(
        id = 1L,
        chatId = -1001L,
        mediaAlbumId = 0L,
        senderName = "Channel",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("secret body")),
        views = 0,
        date = 0L,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
        protectedContent = protectedContent,
    )

    @Test
    fun `unprotected post shares its excerpt and link`() {
        val text = PostActions.buildShareText(post(protectedContent = false), "https://t.me/c/1/1")
        assertTrue(text.startsWith("secret body"))
        assertTrue(text.endsWith("https://t.me/c/1/1"))
    }

    @Test
    fun `protected post shares only attribution and link`() {
        val text = PostActions.buildShareText(post(protectedContent = true), "https://t.me/c/1/1")
        assertFalse(text.contains("secret body"))
        assertEquals("— Channel\nhttps://t.me/c/1/1", text)
    }
}
