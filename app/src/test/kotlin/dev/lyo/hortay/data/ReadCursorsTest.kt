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
        // Channel inbox cursor doesn't apply to thread replies — replies live in
        // the linked discussion supergroup which has its own per-thread read state
        // we don't currently track. UnreadStrip stays a feed-only affordance.
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
    fun `resumeReadingIndex returns oldest unread when there is a clear read-unread boundary`() {
        // Newest-first feed: unread at the top (just-arrived), read below
        // (history). The "resume reading" target is the OLDEST unread post —
        // the boundary just above the read block. Scrolling there in newest-
        // first order lands the user with newer unread above them and read
        // history below.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L), // index 0, unread (newest)
            post(id = 60L, date = 600L), // index 1, unread (oldest unread = TARGET)
            post(id = 40L, date = 400L), // index 2, read (newest read)
            post(id = 30L, date = 300L), // index 3, read (oldest)
        )
        assertEquals(1, resumeReadingIndex(newestFirst, cursors))
    }

    @Test
    fun `resumeReadingIndex returns -1 when caught up`() {
        // Nothing to resume to — every post is read. Cold-start scroll pin
        // stays at top (newest) and the UnreadCounterPill is hidden.
        val cursors = persistentMapOf(CHAT_ID to 1_000L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 30L, date = 300L),
        )
        assertEquals(-1, resumeReadingIndex(newestFirst, cursors))
    }

    @Test
    fun `resumeReadingIndex returns -1 when all posts are unread`() {
        // No read history to anchor the boundary against. Hortay reads more
        // like a Twitter feed than a chat-app inbox; landing on the oldest
        // post of the entire feed reads as "the app threw me into ancient
        // history". Cold-start pin stays at top (newest); the user reads
        // forward into the past as they scroll down. Without this guard, an
        // account with no pre-existing read state would land on a 2017-era
        // post on every cold start.
        val cursors = persistentMapOf(CHAT_ID to 0L)
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 60L, date = 600L),
            post(id = 30L, date = 300L),
        )
        assertEquals(-1, resumeReadingIndex(newestFirst, cursors))
    }

    @Test
    fun `resumeReadingIndex returns -1 while cursors are empty`() {
        // Cold-start race: TDLib UpdateChatReadInbox hasn't landed yet, so we
        // can't classify any post. Don't auto-scroll until cursors arrive.
        val newestFirst = listOf(
            post(id = 70L, date = 700L),
            post(id = 30L, date = 300L),
        )
        assertEquals(-1, resumeReadingIndex(newestFirst, EmptyReadCursors))
    }

    @Test
    fun `resumeReadingIndex returns -1 on empty input`() {
        val cursors = persistentMapOf(CHAT_ID to 50L)
        assertEquals(-1, resumeReadingIndex(emptyList(), cursors))
    }

    @Test
    fun `resumeReadingIndex ignores thread replies when counting read posts`() {
        // parentId != null are comment replies; they're treated as read by
        // isUnreadIn (the feed cursor doesn't apply to discussion threads),
        // but they aren't "feed read history" either. A feed of one thread
        // reply + several unread channel posts must NOT land on a resume
        // target — there's no real read history to anchor against.
        val cursors = persistentMapOf(CHAT_ID to 50L)
        val mixed = listOf(
            post(id = 70L, date = 700L),                       // unread
            post(id = 60L, date = 600L),                       // unread
            post(id = 200L, date = 200L).copy(parentId = 42L), // thread reply (treated as read)
        )
        assertEquals(-1, resumeReadingIndex(mixed, cursors))
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
