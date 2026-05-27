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
        // Reverse feed: post data is always desc (newest = index 0). With the
        // cursor at 98, ids 99/100 are unread. continueReadingIndex returns the
        // OLDEST unread (resume boundary) = id 99 = indexOfLast{unread} = index 1.
        val items = listOf(item(100L), item(99L), item(98L)).toPersistentList()
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
    fun `Ready at index 0 in OldestUnreadFirst when caught up`() {
        // Post data is always desc (newest = index 0); reverseLayout=true in the
        // LazyColumn flips the visual order so index 0 appears at the bottom.
        // "Caught up" (boundary == -1) must land at 0 == newest == bottom-of-viewport,
        // not lastIndex. This is the unified fallback for both feed orders.
        val items = listOf(item(100L), item(99L), item(98L)).toPersistentList()
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
        assertEquals(0, s.initialIndex) // newest post; sits at the bottom under reverseLayout
    }

    @Test
    fun `OldestUnreadFirst with recency floor lands past the dormant unread`() {
        // Aggregated feed (desc by date): active channel B's fresh unread on top,
        // dormant channel A's weeks-old unread at the bottom. With minUnreadDate
        // between the two dates, only B's posts qualify; the resume boundary is
        // the OLDEST qualifying unread = id 70 = indexOfLast{qualifies} = index 1.
        val items = listOf(
            item(80L, chatId = 2L, date = 700L),  // fresh unread
            item(70L, chatId = 2L, date = 600L),  // fresh unread — boundary
            item(60L, chatId = 1L, date = 100L),  // dormant unread (below floor)
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
    fun `OldestUnreadFirst with recency floor falls back to index 0 when every unread is dormant`() {
        // Only ancient unread exists. The fallback is index 0 (newest; bottom of
        // viewport under reverseLayout), which reads as "you're caught up on
        // anything recent". The dormant post itself stays in the feed.
        val items = listOf(
            item(70L, date = 200L), // newest  — index 0 under desc ordering
            item(60L, date = 100L), // dormant unread — index 1
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
        assertEquals(0, s.initialIndex) // newest; sits at the bottom under reverseLayout
    }
}
