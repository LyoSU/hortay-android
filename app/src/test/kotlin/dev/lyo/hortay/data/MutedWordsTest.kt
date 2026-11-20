package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutedWordsTest {

    @Test
    fun `a post containing a muted word is dropped, case-insensitively, also in Cyrillic`() {
        val posts = listOf(post(1, "Сьогодні РЕКЛАМА нового банку"), post(2, "Просто новини"), post(3, "Big GIVEAWAY today"))
        val shown = MutedWordsFilter().apply(posts, setOf("реклам", "giveaway"))
        assertEquals(listOf(2L), shown.map { it.id })
    }

    @Test
    fun `a hashtag is matched with its hash sign and does not catch the bare word`() {
        val posts = listOf(post(1, "Купуйте #промо зараз"), post(2, "Про промо без хештега"))
        val shown = MutedWordsFilter().apply(posts, setOf("#промо"))
        assertEquals(listOf(2L), shown.map { it.id })
    }

    @Test
    fun `nothing muted returns the very same list`() {
        val posts = listOf(post(1, "one"), post(2, "two"))
        val filter = MutedWordsFilter()
        assertSame(posts, filter.apply(posts, emptySet()))
        assertSame(posts, filter.apply(posts, setOf("absent")))
    }

    @Test
    fun `the verdict follows the word list, not a stale memo`() {
        val posts = listOf(post(1, "alpha"), post(2, "beta"))
        val filter = MutedWordsFilter()
        assertEquals(listOf(2L), filter.apply(posts, setOf("alpha")).map { it.id })
        assertEquals(listOf(1L), filter.apply(posts, setOf("beta")).map { it.id })
        assertEquals(listOf(1L, 2L), filter.apply(posts, emptySet()).map { it.id })
    }

    @Test
    fun `an edited post is judged again`() {
        val filter = MutedWordsFilter()
        val words = setOf("spam")
        assertEquals(1, filter.apply(listOf(post(1, "clean")), words).size)
        assertEquals(0, filter.apply(listOf(post(1, "now with spam")), words).size)
    }

    @Test
    fun `entries are normalised and one-letter entries are refused`() {
        assertEquals("big sale", MutedWordsStore.normalize("  Big   SALE "))
        assertEquals("#промо", MutedWordsStore.normalize("#Промо"))
        assertNull(MutedWordsStore.normalize(" a "))
        assertNull(MutedWordsStore.normalize("   "))
    }

    @Test
    fun `an empty text is never muted`() {
        assertFalse(MutedWordsFilter.isMuted("", setOf("x")))
        assertTrue(MutedWordsFilter.isMuted("xy", setOf("x")))
    }

    private fun post(id: Long, text: String): TimelinePost = TimelinePost(
        id = id,
        chatId = -100L,
        mediaAlbumId = 0L,
        senderName = "Channel",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain(text)),
        views = 0,
        date = id,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
        parentId = null,
    )
}
