package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.FormattedText.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelDescriptionTest {

    /** The linked substrings with their style, in order. */
    private fun FormattedText.linked(): List<Pair<String, Style>> =
        spans.map { text.substring(it.start, it.end) to it.style }

    @Test
    fun `plain text is untouched`() {
        val text = "News from Kyiv, every day. No ads, no bots: just posts."
        val result = linkifyDescription(text)
        assertEquals(text, result.text)
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `empty text has no spans`() {
        assertTrue(linkifyDescription("").spans.isEmpty())
    }

    @Test
    fun `url with a scheme becomes a link`() {
        val result = linkifyDescription("Site: https://example.com/about and more")
        assertEquals(listOf("https://example.com/about" to Style.Url), result.linked())
    }

    @Test
    fun `www host becomes a link without a scheme`() {
        val result = linkifyDescription("Visit www.example.org today")
        assertEquals(listOf("www.example.org" to Style.Url), result.linked())
    }

    @Test
    fun `host with a path becomes a link`() {
        val result = linkifyDescription("Chat: t.me/hortay_chat")
        assertEquals(listOf("t.me/hortay_chat" to Style.Url), result.linked())
    }

    @Test
    fun `bare host without a path stays text`() {
        val result = linkifyDescription("Edited in notes.txt and sent via example.com")
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `trailing sentence punctuation is not part of the url`() {
        listOf(".", ",", "!", "?", ":", ";", "…").forEach { mark ->
            val result = linkifyDescription("Read https://example.com/post$mark Then more")
            assertEquals(listOf("https://example.com/post" to Style.Url), result.linked(), "mark '$mark'")
        }
    }

    @Test
    fun `url closed by an outer parenthesis drops it`() {
        val result = linkifyDescription("Our site (https://example.com/news) is new")
        assertEquals(listOf("https://example.com/news" to Style.Url), result.linked())
    }

    @Test
    fun `balanced parenthesis inside a url is kept`() {
        val result = linkifyDescription("See https://en.wikipedia.org/wiki/Kyiv_(city) for history")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kyiv_(city)" to Style.Url), result.linked())
    }

    @Test
    fun `balanced parenthesis is kept before trailing punctuation`() {
        val result = linkifyDescription("See https://en.wikipedia.org/wiki/Kyiv_(city).")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kyiv_(city)" to Style.Url), result.linked())
    }

    @Test
    fun `mention of 4 to 32 characters becomes a mention`() {
        val shortest = "@abcd"
        val longest = "@a" + "b".repeat(31)
        val result = linkifyDescription("Admins: $shortest and $longest.")
        assertEquals(listOf(shortest to Style.Mention, longest to Style.Mention), result.linked())
    }

    @Test
    fun `too short or too long mention stays text`() {
        val tooLong = "@a" + "b".repeat(32)
        val result = linkifyDescription("Ping @abc or $tooLong")
        assertTrue(result.spans.isEmpty())
    }

    @Test
    fun `mention must start with a letter`() {
        assertTrue(linkifyDescription("Call @1234567").spans.isEmpty())
    }

    @Test
    fun `email address is not a mention`() {
        assertTrue(linkifyDescription("Write to editor@example.com").spans.isEmpty())
    }

    @Test
    fun `hashtags become hashtags`() {
        val result = linkifyDescription("Topics: #news #новини #tech_2026")
        assertEquals(
            listOf("#news" to Style.Hashtag, "#новини" to Style.Hashtag, "#tech_2026" to Style.Hashtag),
            result.linked(),
        )
    }

    @Test
    fun `number sign without a letter is not a hashtag`() {
        assertTrue(linkifyDescription("Issue #42 is fixed").spans.isEmpty())
    }

    @Test
    fun `mention inside a url is not split out`() {
        val result = linkifyDescription("Profile: https://example.com/@hortay_team/posts")
        assertEquals(listOf("https://example.com/@hortay_team/posts" to Style.Url), result.linked())
    }

    @Test
    fun `hashtag inside a url is not split out`() {
        val result = linkifyDescription("Docs: https://example.com/guide#section")
        assertEquals(listOf("https://example.com/guide#section" to Style.Url), result.linked())
    }

    @Test
    fun `mixed description keeps spans in text order`() {
        val result = linkifyDescription("#daily digest by @hortay_news, archive at www.example.com/archive")
        assertEquals(
            listOf(
                "#daily" to Style.Hashtag,
                "@hortay_news" to Style.Mention,
                "www.example.com/archive" to Style.Url,
            ),
            result.linked(),
        )
    }
}
