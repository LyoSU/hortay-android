package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineFeedGroupingTest {

    @Test
    fun `withBoundary inserts Boundary above oldest unread in OldestUnreadFirst`() {
        // Posts descending (newest = idx 0). Cursor for chat 1 = 50, so post 60 is unread.
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 100L)),
            FeedItem.Post(testPost(chatId = 1L, id = 80L)),
            FeedItem.Post(testPost(chatId = 1L, id = 60L)),
            FeedItem.Post(testPost(chatId = 1L, id = 40L)),
            FeedItem.Post(testPost(chatId = 1L, id = 20L)),
        )
        val cursors: ReadCursors = persistentMapOf(1L to 50L)
        val result = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 42L,
            recencyCutoffMs = 0L,
        )

        val boundaryIdx = result.indexOfFirst { it is FeedItem.Boundary }
        assertEquals(3, boundaryIdx)
        assertEquals(42L, (result[boundaryIdx] as FeedItem.Boundary).epoch)
    }

    @Test
    fun `withBoundary skips insertion when nothing is unread`() {
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 40L)),
            FeedItem.Post(testPost(chatId = 1L, id = 20L)),
        )
        val cursors: ReadCursors = persistentMapOf(1L to 100L)
        val result = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 1L,
            recencyCutoffMs = 0L,
        )
        assertTrue(result.none { it is FeedItem.Boundary })
        assertEquals(2, result.size)
    }

    @Test
    fun `withBoundary skips insertion when nothing is read yet — fresh subscriber`() {
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 100L)),
            FeedItem.Post(testPost(chatId = 1L, id = 80L)),
        )
        val cursors: ReadCursors = persistentMapOf()
        val result = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 1L,
            recencyCutoffMs = 0L,
        )
        // ReadCursors.isUnreadIn — cursor == null → returns false. So nothing qualifies
        // as unread, no boundary inserted (cold-start anchor handled elsewhere via
        // continueReadingIndex's recency floor).
        assertTrue(result.none { it is FeedItem.Boundary })
    }

    @Test
    fun `withBoundary keeps stable epoch — divider key is stable across calls`() {
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 80L)),
            FeedItem.Post(testPost(chatId = 1L, id = 60L)),
            FeedItem.Post(testPost(chatId = 1L, id = 40L)),
        )
        val cursors: ReadCursors = persistentMapOf(1L to 50L)
        val first = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 7L,
            recencyCutoffMs = 0L,
        )
        val second = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 7L,
            recencyCutoffMs = 0L,
        )
        val firstKey = first.first { it is FeedItem.Boundary }.key
        val secondKey = second.first { it is FeedItem.Boundary }.key
        assertEquals(firstKey, secondKey)
    }

    @Test
    fun `withBoundary Newest mode inserts boundary at same data position`() {
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 100L)),
            FeedItem.Post(testPost(chatId = 1L, id = 80L)),
            FeedItem.Post(testPost(chatId = 1L, id = 60L)),
            FeedItem.Post(testPost(chatId = 1L, id = 40L)),
        )
        val cursors: ReadCursors = persistentMapOf(1L to 50L)
        val result = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.Newest,
            epoch = 1L,
            recencyCutoffMs = 0L,
        )
        val boundaryIdx = result.indexOfFirst { it is FeedItem.Boundary }
        assertEquals(3, boundaryIdx)
    }

    @Test
    fun `withBoundary skips insertion when every post is unread`() {
        // Edge case — no read prefix to separate from. Mirrors Telegram's "no rule" idiom.
        val posts = listOf(
            FeedItem.Post(testPost(chatId = 1L, id = 100L)),
            FeedItem.Post(testPost(chatId = 1L, id = 80L)),
        )
        val cursors: ReadCursors = persistentMapOf(1L to 50L) // both unread
        val result = withBoundary(
            items = posts,
            cursors = cursors,
            order = FeedOrder.OldestUnreadFirst,
            epoch = 1L,
            recencyCutoffMs = 0L,
        )
        assertTrue(result.none { it is FeedItem.Boundary })
    }
}
