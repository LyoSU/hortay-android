package dev.lyo.hortay.data

import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `Newest order is a no-op on already-descending input`() {
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
    fun `OldestUnreadFirst is identity — sort is owned by the repository`() {
        // Since PostsRepository always emits newest-first (descending), orderedFor
        // is a no-op shim for both orders. FeedOrder controls reverseLayout only.
        // Tie-break determinism lives in the repository's descending sort.
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 60L, date = 600L),
            post(id = 40L, date = 400L),
            post(id = 30L, date = 300L),
        )
        val ordered = newestFirst.orderedFor(FeedOrder.OldestUnreadFirst)
        assertEquals(listOf(70L, 60L, 40L, 30L), ordered.map { it.id })
    }

    @Test
    fun `orderedFor preserves input reference identity for both orders`() {
        // Both branches must return `this` — no copy, no re-sort.
        val posts = listOf(
            post(id = 10L, date = 500L),
            post(id = 11L, date = 500L),
            post(id = 12L, date = 500L),
        )
        assertSame(posts, posts.orderedFor(FeedOrder.Newest))
        assertSame(posts, posts.orderedFor(FeedOrder.OldestUnreadFirst))
    }

    @Test
    fun `continueReadingIndex points to oldest unread (highest index) on descending data`() {
        // Descending data (newest=index 0): the resume boundary is the LAST
        // entry in the unread block — indexOfLast. Same for both orders.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val descending = listOf(
            post(id = 70L, date = 700L), // index 0, unread (newest)
            post(id = 60L, date = 600L), // index 1, unread — oldest unread, the target
            post(id = 40L, date = 400L), // index 2, read
            post(id = 30L, date = 300L), // index 3, read
        )
        assertEquals(1, continueReadingIndex(FeedOrder.Newest, descending, cursors))
        // OldestUnreadFirst yields the same result — data contract is identical
        assertEquals(1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors))
    }

    @Test
    fun `continueReadingIndex returns -1 when everything is read`() {
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val descending = listOf(
            post(id = 70L, date = 700L),
            post(id = 30L, date = 300L),
        )
        assertEquals(-1, continueReadingIndex(FeedOrder.Newest, descending, cursors))
        assertEquals(-1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors))
    }

    @Test
    fun `continueReadingIndex is identical for Newest and OldestUnreadFirst on descending input`() {
        // Both orders share the same "indexOfLast unread" logic — this test
        // proves that the order parameter no longer forks the result.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val descending = listOf(
            post(id = 80L, date = 800L), // unread
            post(id = 70L, date = 700L), // unread
            post(id = 60L, date = 600L), // unread — oldest unread, target
            post(id = 40L, date = 400L), // read
        )
        val forNewest = continueReadingIndex(FeedOrder.Newest, descending, cursors)
        val forOldest = continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors)
        assertEquals(forNewest, forOldest)
        assertEquals(2, forNewest)
    }

    @Test
    fun `continueReadingIndex returns -1 when feed is caught up`() {
        // -1 signals the caller to fall back to index 0 (newest, top of descending list).
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val descending = listOf(post(id = 70L, date = 700L), post(id = 30L, date = 300L))
        assertEquals(-1, continueReadingIndex(FeedOrder.Newest, descending, cursors))
        assertEquals(-1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors))
    }

    @Test
    fun `recency floor skips dormant unread and lands on the oldest fresh unread`() {
        // Aggregated feed (descending). A dormant channel post at the bottom
        // (low date) and fresh unread above it. With minUnreadDate between
        // the two dates, the picker skips the dormant post — indexOfLast
        // of posts qualifying (isUnread && date >= floor) stops at the
        // oldest fresh unread, not the dormant one.
        val cursors = persistentMapOf(CHAT_ID to 50L, CHAT_ID + 1 to 50L)
        val descending = listOf(
            post(id = 80L, chatId = CHAT_ID + 1, date = 700L),     // fresh unread, index 0
            post(id = 70L, chatId = CHAT_ID + 1, date = 600L),     // fresh unread, index 1 — target
            post(id = 60L, chatId = CHAT_ID, date = 100L),         // dormant unread, index 2
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors, minUnreadDate = 500L),
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.Newest, descending, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `recency floor returns -1 when every unread is dormant`() {
        // Only ancient unread exists. Caller (TimelineUiState) maps -1 to
        // index 0 (newest, top of descending list). Dormant unread remains
        // in the feed for the user to scroll to.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val descending = listOf(
            post(id = 70L, date = 200L), // dormant unread
            post(id = 60L, date = 100L), // dormant unread
        )
        assertEquals(
            -1,
            continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors, minUnreadDate = 500L),
        )
        assertEquals(
            -1,
            continueReadingIndex(FeedOrder.Newest, descending, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `recency floor scopes the oldest-unread anchor to the recent window`() {
        // Descending input: newest-first. With the floor, the picker wants the
        // oldest unread *within the recent window* — indexOfLast of qualifying
        // posts. Mixed-age unread: the dormant one at the bottom doesn't count.
        val cursors = persistentMapOf(CHAT_ID to 50L, CHAT_ID + 1 to 50L)
        val descending = listOf(
            post(id = 90L, chatId = CHAT_ID + 1, date = 900L),     // fresh unread, index 0
            post(id = 80L, chatId = CHAT_ID + 1, date = 800L),     // fresh unread — boundary, index 1
            post(id = 60L, chatId = CHAT_ID, date = 100L),         // dormant unread (skipped), index 2
            post(id = 40L, chatId = CHAT_ID, date = 90L),          // read, index 3
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.Newest, descending, cursors, minUnreadDate = 500L),
        )
        assertEquals(
            1,
            continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, cursors, minUnreadDate = 500L),
        )
    }

    @Test
    fun `zero or negative minUnreadDate disables the floor`() {
        // Default and explicit 0 must ignore the date — dormant unread qualifies.
        // Single-element descending list: indexOfLast of the only unread = 0.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val posts = listOf(post(id = 60L, date = 100L)) // dormant unread
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors))
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors, minUnreadDate = 0L))
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, posts, cursors, minUnreadDate = -1L))
    }

    @Test
    fun `advancing the cursor past the oldest unread moves the boundary to the next`() {
        // Models the next-unread pill's one-tap skip and the optimistic on-ack cursor
        // advance: parked on the oldest unread, ack it (advance the cursor to its id),
        // and the boundary recomputes to the NEXT oldest unread. Descending data.
        val descending = listOf(
            post(id = 70L, date = 700L), // index 0, unread (newest)
            post(id = 60L, date = 600L), // index 1, unread — oldest unread, parked here
            post(id = 40L, date = 400L), // index 2, read
        )
        val before = persistentMapOf(CHAT_ID to 50L)
        assertEquals(1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, before))
        // Ack post 60 → cursor advances to 60. Boundary moves up to post 70 (index 0).
        val after = before.put(CHAT_ID, 60L)
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, after))
    }

    @Test
    fun `acking the only remaining unread leaves the feed caught up`() {
        val descending = listOf(
            post(id = 70L, date = 700L), // unread — the only unread
            post(id = 40L, date = 400L), // read
        )
        val before = persistentMapOf(CHAT_ID to 50L)
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, before))
        // Ack the last unread → -1 (caller maps to index 0 = newest).
        val after = before.put(CHAT_ID, 70L)
        assertEquals(-1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, after))
    }

    @Test
    fun `acking an album advances past its highest member so it reads as read`() {
        // The pill advances the cursor to albumMessageIds.maxOrNull(), not the anchor id,
        // so an album the user skipped past does not re-light as unread.
        val album = post(id = 100L, date = 600L).copy(albumMessageIds = listOf(100L, 101L, 102L))
        val descending = listOf(post(id = 200L, date = 700L), album)
        val before = persistentMapOf(CHAT_ID to 50L)
        assertEquals(1, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, before))
        val after = before.put(CHAT_ID, album.albumMessageIds.maxOrNull()!!)
        assertEquals(0, continueReadingIndex(FeedOrder.OldestUnreadFirst, descending, after))
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
