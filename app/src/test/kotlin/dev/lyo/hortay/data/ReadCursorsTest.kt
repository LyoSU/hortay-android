package dev.lyo.hortay.data

import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadCursorsTest {

    @Test
    fun `post with id above cursor is unread`() {
        val cursors = persistentMapOf(CHAT_ID to 100L)
        assertTrue(post(id = 101L).isUnreadIn(cursors))
    }

    @Test
    fun `post with id equal to cursor is read`() {
        val cursors = persistentMapOf(CHAT_ID to 100L)
        assertFalse(post(id = 100L).isUnreadIn(cursors))
    }

    @Test
    fun `post with id below cursor is read`() {
        val cursors = persistentMapOf(CHAT_ID to 100L)
        assertFalse(post(id = 99L).isUnreadIn(cursors))
    }

    @Test
    fun `post in chat with no recorded cursor counts as read`() {
        // Cold-start race: chatCache has not yet emitted UpdateNewChat for this
        // chat, so we have no cursor. Treat as read — flashing the whole feed as
        // unread for the first 500ms after auth reads as a UI bug.
        assertFalse(post(id = 9_999L).isUnreadIn(EmptyReadCursors))
    }

    @Test
    fun `discussion thread reply is never marked unread`() {
        val cursors = persistentMapOf(CHAT_ID to 100L)
        val reply = post(id = 200L).copy(parentId = 42L)
        assertFalse(reply.isUnreadIn(cursors))
    }

    @Test
    fun `firstUnreadIndex returns the first unread iterator position`() {
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val posts = listOf(
            post(id = 100L), // unread
            post(id = 80L),  // unread
            post(id = 40L),  // read
        )
        assertEquals(0, firstUnreadIndex(posts, cursors))
    }

    @Test
    fun `firstUnreadIndex returns -1 when everything is read`() {
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val posts = listOf(post(id = 100L), post(id = 50L))
        assertEquals(-1, firstUnreadIndex(posts, cursors))
    }

    @Test
    fun `firstUnreadIndex returns -1 on empty input`() {
        assertEquals(-1, firstUnreadIndex(emptyList(), EmptyReadCursors))
    }

    @Test
    fun `Newest order is a no-op on already-chronological input`() {
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // unread, newest
            post(id = 60L, date = 600L), // unread
            post(id = 40L, date = 400L), // read
            post(id = 30L, date = 300L), // read, oldest
        )
        val ordered = newestFirst.orderedFor(FeedOrder.Newest, cursors)
        assertEquals(listOf(70L, 60L, 40L, 30L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst puts read block on top, unread below, both asc by date`() {
        // Chat-app idiom: read history at the top, unread queue at the bottom.
        // User auto-scrolls to first-unread boundary on mount; scrolling up walks
        // back into history, scrolling down advances through the queue.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // unread
            post(id = 60L, date = 600L), // unread
            post(id = 40L, date = 400L), // read
            post(id = 30L, date = 300L), // read (oldest)
        )
        val ordered = newestFirst.orderedFor(FeedOrder.OldestUnreadFirst, cursors)
        assertEquals(listOf(30L, 40L, 60L, 70L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst on all-read feed sorts everything asc by date`() {
        // Channel cursor sits above every loaded post — no unread block; the list
        // is just the read history asc. TimelineScreen auto-scroll falls back to
        // lastIndex (= newest at the bottom).
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val posts = listOf(
            post(id = 70L, date = 700L),
            post(id = 30L, date = 300L),
            post(id = 50L, date = 500L),
        )
        val ordered = posts.orderedFor(FeedOrder.OldestUnreadFirst, cursors)
        assertEquals(listOf(30L, 50L, 70L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst falls back to source order while cursors are empty`() {
        // Cold-start race fallback. TimelineScreen also gates the LazyColumn
        // render on cursors-landed for OldestUnreadFirst, so this branch is a
        // belt-and-braces against any callsite that hits orderedFor before
        // cursors arrive.
        val cursors = persistentMapOf<Long, Long>()
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 60L, date = 600L),
            post(id = 40L, date = 400L),
            post(id = 30L, date = 300L),
        )
        val ordered = newestFirst.orderedFor(FeedOrder.OldestUnreadFirst, cursors)
        assertEquals(listOf(70L, 60L, 40L, 30L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst preserves source order for same-date same-block posts`() {
        val cursors = persistentMapOf(CHAT_ID to 0L)
        val posts = listOf(
            post(id = 10L, date = 500L),
            post(id = 11L, date = 500L),
            post(id = 12L, date = 500L),
        )
        val ordered = posts.orderedFor(FeedOrder.OldestUnreadFirst, cursors)
        assertEquals(listOf(10L, 11L, 12L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst new unread arrival lands at the END of the unread block`() {
        // New posts via UpdateNewMessage are unread and carry the highest date.
        // In the read-on-top, unread-below layout, they sort to the bottom of
        // the unread block.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val initial = listOf(
            post(id = 60L, date = 600L), // unread
            post(id = 70L, date = 700L), // unread
            post(id = 40L, date = 400L), // read
        )
        val withArrival = listOf(post(id = 80L, date = 800L)) + initial
        val ordered = withArrival.orderedFor(FeedOrder.OldestUnreadFirst, cursors)
        assertEquals(listOf(40L, 60L, 70L, 80L), ordered.map { it.id })
    }

    @Test
    fun `continueReadingIndex points to oldest unread in Newest order`() {
        // Newest-first orientation: oldest unread is the LAST entry in the
        // unread block.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // index 0, unread (newest)
            post(id = 60L, date = 600L), // index 1, unread (oldest unread)
            post(id = 40L, date = 400L), // index 2, read
            post(id = 30L, date = 300L), // index 3, read
        )
        assertEquals(1, continueReadingIndex(FeedOrder.Newest, newestFirst, cursors))
    }

    @Test
    fun `continueReadingIndex returns -1 when everything is read`() {
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 30L, date = 300L),
        )
        assertEquals(-1, continueReadingIndex(FeedOrder.Newest, newestFirst, cursors))
        assertEquals(-1, continueReadingIndex(FeedOrder.OldestUnreadFirst, newestFirst, cursors))
    }

    @Test
    fun `continueReadingIndex points to first unread (read-tail boundary) in OldestUnreadFirst`() {
        // Layout: read history above (asc by date) → unread queue below. The
        // "continue reading" target is the boundary — first unread index =
        // top of unread block. Scrolling there lands the user at "where they
        // left off".
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val displayed = listOf(
            post(id = 30L, date = 300L), // read
            post(id = 40L, date = 400L), // read
            post(id = 60L, date = 600L), // first unread — the target
            post(id = 70L, date = 700L),
        )
        assertEquals(2, continueReadingIndex(FeedOrder.OldestUnreadFirst, displayed, cursors))
    }

    private companion object {
        const val CHAT_ID = -1001L
    }

    private fun post(
        id: Long = 1L,
        chatId: Long = CHAT_ID,
        date: Long = 0L,
    ): TimelinePost = TimelinePost(
        id = id, chatId = chatId, mediaAlbumId = 0L,
        senderName = "C", senderHandle = null, avatarThumb = null, avatarFileId = null,
        content = PostContent.Text(FormattedText.Empty),
        views = 0, date = date, editDate = 0L,
        forwardOrigin = null, authorSignature = null, reply = null,
        reactions = Reactions(0, emptyList()), commentCount = null,
        albumMessageIds = emptyList(), parentId = null,
    )
}
