package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelUiStateBuilderTest {

    private fun item(id: Long, album: List<Long> = emptyList()): FeedItem =
        FeedItem.Post(testPost(id = id, chatId = 1L, date = id, albumMessageIds = album))

    private fun loaded(items: PersistentList<FeedItem>): ChannelData =
        ChannelData.Loaded(items.flatMap { it.posts() }.toPersistentList())

    @Test
    fun `Resolving while data is Loading`() {
        // Cold first-entry: data is still loading, items is empty (or
        // about to be replaced atomically). Skeleton gate is correct.
        val s = buildChannelUiState(
            data = ChannelData.Loading,
            items = persistentListOf(),
            scrollToMessageId = 200L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Resolving even when items is non-empty under Loading state`() {
        // [ChannelData.Loading] means the deep slice has not yet landed.
        // Even if some stray items (e.g. live UpdateNewMessage arrivals)
        // appear in [items] during the load, the UI must stay in
        // Resolving so the LazyColumn doesn't paint with a sparse
        // pre-load slice that will be back-filled by older posts.
        val items = listOf(item(100L), item(99L)).toPersistentList()
        val s = buildChannelUiState(
            data = ChannelData.Loading,
            items = items,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Resolving with Loaded data still gated on deep-link target landing`() {
        // [ChannelData.Loaded] but the deep-link target hasn't
        // materialised in [items]. We must NOT fall through to Ready at
        // the head post; the initialIndex would latch through
        // reduceChannelUiState before the around-load completes and the
        // user would briefly see the wrong row. Stay in Resolving until
        // either the target shows up or the around-load attempt
        // finishes.
        val items = listOf(item(100L), item(99L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 999L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Ready at zero when no scrollToMessageId`() {
        val items = listOf(item(100L), item(99L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(0, s.initialIndex)
        assertNull(s.highlightedMessageId)
    }

    @Test
    fun `Ready at target index when scrollToMessageId resolved`() {
        val items = listOf(item(300L), item(200L), item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 200L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
        assertEquals(200L, s.highlightedMessageId)
    }

    @Test
    fun `Resolving when scrollToMessageId not yet found and not attempted`() {
        val items = listOf(item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 999L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Missing when scrollToMessageId not found after around-load attempt`() {
        val items = listOf(item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 999L,
            attemptedAround = true,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Missing)
    }

    @Test
    fun `Album member id resolves to anchor index`() {
        val items = listOf(
            item(300L),
            item(200L, album = listOf(200L, 201L, 202L)),
        ).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 202L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
    }

    @Test
    fun `OldestUnreadFirst lands at first-unread boundary when no deep-link`() {
        // Desc-by-date layout: ids 103..100, cursor at 101 means 100/101 read,
        // 102/103 unread. continueReadingIndex returns the OLDEST unread (resume
        // boundary) = id 102 = indexOfLast{unread} = index 1.
        val items = listOf(item(103L), item(102L), item(101L), item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
            feedOrder = FeedOrder.OldestUnreadFirst,
            cursors = persistentMapOf(1L to 101L),
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
        assertNull(s.highlightedMessageId)
    }

    @Test
    fun `OldestUnreadFirst caught up lands at index 0 when no deep-link`() {
        // All posts read (cursor at 103L, highest id). continueReadingIndex returns -1.
        // Fallback must be 0 (newest; sits at the bottom under reverseLayout=true),
        // not lastIndex — post data is always desc (newest = index 0) in the new model.
        val items = listOf(item(103L), item(102L), item(101L), item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
            feedOrder = FeedOrder.OldestUnreadFirst,
            cursors = persistentMapOf(1L to 103L), // all read
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(0, s.initialIndex) // newest; bottom-of-viewport under reverseLayout
        assertNull(s.highlightedMessageId)
    }

    @Test
    fun `Search mode suppresses deep-link landing`() {
        // When user activates in-channel search, the deep-link anchor
        // doesn't apply — search results are their own context. Builder
        // returns Ready at index 0 with no highlight even when
        // scrollToMessageId is set.
        val items = listOf(item(300L), item(200L), item(100L)).toPersistentList()
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = 200L,
            attemptedAround = false,
            searchActive = true,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(0, s.initialIndex)
        assertNull(s.highlightedMessageId)
    }

    @Test
    fun `OldestUnreadFirst with FeedItem dot Boundary present targets the divider row`() {
        // Mirrors the merged-feed test in TimelineUiStateBuilderTest: when the
        // upstream withBoundary helper has inserted a divider row at
        // boundaryPost + 1, the cold-entry landing should be the DIVIDER index,
        // not the boundary post. Top-aligning the divider puts the divider at
        // the screen top with the oldest unread post immediately below it.
        val items = persistentListOf<FeedItem>(
            FeedItem.Post(testPost(chatId = 1L, id = 103L)), // unread
            FeedItem.Post(testPost(chatId = 1L, id = 102L)), // unread — boundary post (index 1)
            FeedItem.Boundary(epoch = 11L),                  // divider (index 2)
            FeedItem.Post(testPost(chatId = 1L, id = 101L)), // read
            FeedItem.Post(testPost(chatId = 1L, id = 100L)), // read
        )
        val s = buildChannelUiState(
            data = loaded(items),
            items = items,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
            feedOrder = FeedOrder.OldestUnreadFirst,
            cursors = persistentMapOf(1L to 101L),
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(2, s.initialIndex) // FeedItem.Boundary, NOT the boundary post (index 1)
    }
}
