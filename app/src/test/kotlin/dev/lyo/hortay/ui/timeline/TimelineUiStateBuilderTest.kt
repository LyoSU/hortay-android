package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineUiStateBuilderTest {

    private fun item(id: Long, chatId: Long = 1L, date: Long = id): FeedItem =
        FeedItem(testPost(id = id, chatId = chatId, date = date))

    @Test
    fun `Loading when items empty and refreshing`() {
        val s = buildTimelineUiState(
            items = persistentListOf(),
            cursorsLanded = false,
            frozenCursors = EmptyReadCursors,
            feedOrder = FeedOrder.Newest,
            refreshing = true,
        )
        assertTrue(s is TimelineUiState.Loading)
    }

    @Test
    fun `Empty when items empty and refresh finished`() {
        val s = buildTimelineUiState(
            items = persistentListOf(),
            cursorsLanded = true,
            frozenCursors = EmptyReadCursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Empty)
    }

    @Test
    fun `Loading in reverse feed when cursors not landed`() {
        // Critical: rendering reverse-feed items at index 0 (= oldest post) before
        // cursors land was the source of "starts on a random ancient post" symptom.
        val items = listOf(item(id = 100L)).toPersistentList()
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = false,
            frozenCursors = EmptyReadCursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Loading)
    }

    @Test
    fun `Ready at top in Newest when caught up`() {
        // Newest order: items already sorted newest-first by caller. All read → no
        // unread block → land at index 0 (top of feed).
        val items = listOf(item(100L), item(99L), item(98L)).toPersistentList()
        val cursors = persistentMapOf(1L to 100L)
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(0, s.initialIndex)
        assertEquals(items, s.items)
    }

    @Test
    fun `Ready at oldest-unread index in Newest`() {
        // Newest: newest-first. Items [100=unread, 99=unread, 98=read]. Oldest
        // unread is at the BOTTOM of the unread block — continueReadingIndex returns
        // indexOfLast for Newest, which is index 1 here.
        val items = listOf(item(100L), item(99L), item(98L)).toPersistentList()
        val cursors = persistentMapOf(1L to 98L)
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(1, s.initialIndex)
    }

    @Test
    fun `Ready at first-unread boundary in OldestUnreadFirst`() {
        // Reverse feed: items already asc-by-date by caller's orderedFor.
        // Items [98=read, 99=unread, 100=unread]. First unread = index 1.
        val items = listOf(item(98L), item(99L), item(100L)).toPersistentList()
        val cursors = persistentMapOf(1L to 98L)
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(1, s.initialIndex)
        assertEquals(cursors, s.frozenCursors)
    }

    @Test
    fun `Ready at lastIndex in OldestUnreadFirst when caught up`() {
        val items = listOf(item(98L), item(99L), item(100L)).toPersistentList()
        val cursors = persistentMapOf(1L to 100L) // all read
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(2, s.initialIndex) // lastIndex of 3-element list
    }

    @Test
    fun `OldestUnreadFirst with recency floor lands past the dormant unread`() {
        // Aggregated feed: dormant channel A has a weeks-old unread post,
        // active channel B has fresh unread. With minUnreadDate between the
        // two dates, the builder skips the dormant post and lands on the
        // fresh one — the cold-start protection against "I open the feed,
        // it lands on a weeks-old post".
        val items = listOf(
            item(60L, chatId = 1L, date = 100L),  // dormant unread
            item(70L, chatId = 2L, date = 600L),  // fresh unread — target
            item(80L, chatId = 2L, date = 700L),  // fresh unread
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 50L, 2L to 50L)
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
            minUnreadDate = 500L,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(1, s.initialIndex)
    }

    @Test
    fun `OldestUnreadFirst with recency floor falls back to newest when every unread is dormant`() {
        // Only ancient unread exists. The fallback is `lastIndex` (= newest,
        // bottom of asc-by-date), which reads as "you're caught up on
        // anything recent". The dormant post itself stays in the feed.
        val items = listOf(
            item(60L, date = 100L),
            item(70L, date = 200L),
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 50L)
        val s = buildTimelineUiState(
            items = items,
            cursorsLanded = true,
            frozenCursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
            minUnreadDate = 500L,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(1, s.initialIndex)
    }
}
