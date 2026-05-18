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
    fun `album stays unread when cursor is below the highest member id`() {
        // External acks (UpdateChatReadInbox from the official Telegram
        // client) can land the cursor mid-album: anchor below cursor, later
        // members above. Comparing only anchor.id would flip the card to
        // "read" prematurely — the card must stay unread until every
        // member is at or below the cursor.
        val cursors = persistentMapOf(CHAT_ID to 102L)
        val album = post(id = 100L).copy(albumMessageIds = listOf(100L, 101L, 102L, 103L, 104L))
        assertTrue(album.isUnreadIn(cursors), "104 > 102 — card must still be unread")
    }

    @Test
    fun `album marked read only when cursor passes the highest member id`() {
        val cursors = persistentMapOf(CHAT_ID to 104L)
        val album = post(id = 100L).copy(albumMessageIds = listOf(100L, 101L, 102L, 103L, 104L))
        assertFalse(album.isUnreadIn(cursors), "cursor at top member — card is read")
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
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // unread, newest
            post(id = 60L, date = 600L), // unread
            post(id = 40L, date = 400L), // read
            post(id = 30L, date = 300L), // read, oldest
        )
        val ordered = newestFirst.orderedFor(FeedOrder.Newest)
        assertEquals(listOf(70L, 60L, 40L, 30L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst sorts strictly ascending by date — oldest at top, newest at bottom`() {
        // Reverse-feed: chronological order, scroll DOWN to advance forward
        // in time. Read / unread state doesn't affect the sort (would have
        // lifted a newer read post above an older unread post in the old
        // block model — reads as a broken sort in a reverse feed).
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // unread, newest
            post(id = 60L, date = 600L), // unread
            post(id = 40L, date = 400L), // read
            post(id = 30L, date = 300L), // read, oldest
        )
        val ordered = newestFirst.orderedFor(FeedOrder.OldestUnreadFirst)
        assertEquals(listOf(30L, 40L, 60L, 70L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst is a stable sort — preserves input order on date ties`() {
        // Telegram emits album members with the same whole-second date, and
        // PostFilterStrategy already anchors albums on a deterministic id.
        // The asc-by-date sort must not disturb that ordering.
        val posts = listOf(
            post(id = 10L, date = 500L),
            post(id = 11L, date = 500L),
            post(id = 12L, date = 500L),
        )
        val ordered = posts.orderedFor(FeedOrder.OldestUnreadFirst)
        assertEquals(listOf(10L, 11L, 12L), ordered.map { it.id })
    }

    @Test
    fun `OldestUnreadFirst new unread arrival lands at the END of the list`() {
        // New posts via UpdateNewMessage carry the highest date, so they
        // sort to the bottom of the asc-by-date list — the canonical
        // "newest at the bottom" position in a reverse feed.
        val initial = listOf(
            post(id = 60L, date = 600L),
            post(id = 70L, date = 700L),
            post(id = 40L, date = 400L),
        )
        val withArrival = listOf(post(id = 80L, date = 800L)) + initial
        val ordered = withArrival.orderedFor(FeedOrder.OldestUnreadFirst)
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

    @Test
    fun `OldestUnreadFirst recency floor skips dormant unread and lands on the next fresh unread`() {
        // Aggregated feed with a dormant channel: a single weeks-old unread
        // post at the top of asc-by-date sort, and a fresh unread post below
        // it. With minUnreadDate between the two dates, the picker ignores
        // the dormant post and lands on the fresh one — the user opens the
        // feed and is taken to recent reading, not into the dormant tail.
        val cursors = persistentMapOf(CHAT_ID to 50L, CHAT_ID + 1 to 50L)
        val displayed = listOf(
            post(id = 60L, chatId = CHAT_ID, date = 100L),         // dormant unread
            post(id = 70L, chatId = CHAT_ID + 1, date = 600L),     // fresh unread
            post(id = 80L, chatId = CHAT_ID + 1, date = 700L),     // fresh unread
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.OldestUnreadFirst, displayed, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `OldestUnreadFirst recency floor returns -1 when every unread is dormant`() {
        // Only ancient unread exists. Caller (TimelineUiState) maps -1 to
        // lastIndex = newest, so the user lands "caught up on anything
        // recent". Dormant unread remains in the feed for the user to
        // scroll up to.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val displayed = listOf(
            post(id = 60L, date = 100L), // dormant unread
            post(id = 70L, date = 200L), // dormant unread
        )
        assertEquals(
            -1,
            continueReadingIndex(FeedOrder.OldestUnreadFirst, displayed, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `Newest recency floor scopes the oldest-unread anchor to recent posts`() {
        // Newest order: oldest unread = LAST unread in iteration order
        // (newest-first input). With the floor, the picker still wants the
        // oldest unread *within the recent window* — so it lands just above
        // the read tail rather than going back weeks for a dormant unread.
        val cursors = persistentMapOf(CHAT_ID to 50L, CHAT_ID + 1 to 50L)
        val newestFirst = listOf(
            post(id = 90L, chatId = CHAT_ID + 1, date = 900L),     // fresh unread
            post(id = 80L, chatId = CHAT_ID + 1, date = 800L),     // fresh unread — boundary
            post(id = 60L, chatId = CHAT_ID, date = 100L),         // dormant unread (skipped)
            post(id = 40L, chatId = CHAT_ID, date = 90L),          // read
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.Newest, newestFirst, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `zero or negative minUnreadDate disables the floor`() {
        // Default and explicit 0 must match the pre-floor behaviour exactly.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val posts = listOf(post(id = 60L, date = 100L)) // dormant unread
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors))
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors, minUnreadDate = 0L))
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors, minUnreadDate = -1L))
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
