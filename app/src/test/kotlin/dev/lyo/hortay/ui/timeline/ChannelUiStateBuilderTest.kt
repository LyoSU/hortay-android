package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelUiStateBuilderTest {

    private fun item(id: Long, album: List<Long> = emptyList()): FeedItem =
        FeedItem(testPost(id = id, chatId = 1L, date = id, albumMessageIds = album))

    @Test
    fun `Resolving while history loading and slice empty`() {
        // Cold first-entry with no prior cold-start harvest for this chatId:
        // historyLoading=true, items empty → SkeletonFeed gate is correct.
        val s = buildChannelUiState(
            items = persistentListOf(),
            historyLoading = true,
            scrollToMessageId = 200L,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Ready when slice already populated even if history still loading`() {
        // Channel was in the merged feed's cold-start harvest, so `posts`
        // already had this slice before `loadChannelHistory` resolved. Don't
        // flash a skeleton over real content — the deeper history merges
        // into the live posts flow as it arrives.
        val items = listOf(item(100L), item(99L)).toPersistentList()
        val s = buildChannelUiState(
            items = items,
            historyLoading = true,
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
    fun `Resolving with non-empty slice still gated on deep-link target landing`() {
        // historyLoading=true but slice has content — yet the deep-link
        // target hasn't materialised. We must NOT fall through to Ready at
        // the head post; the initialIndex would latch through
        // reduceChannelUiState before the around-load completes and the
        // user would briefly see the wrong row. Stay in Resolving until
        // either the target shows up or the around-load attempt finishes.
        val items = listOf(item(100L), item(99L)).toPersistentList()
        val s = buildChannelUiState(
            items = items,
            historyLoading = true,
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
            items = items,
            historyLoading = false,
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
            items = items,
            historyLoading = false,
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
            items = items,
            historyLoading = false,
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
            items = items,
            historyLoading = false,
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
            items = items,
            historyLoading = false,
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
        // Asc-by-date layout: ids 100..103, cursor at 101 means 100/101 read,
        // 102/103 unread. Boundary = index 2 (first FeedItem containing unread).
        val items = listOf(item(100L), item(101L), item(102L), item(103L)).toPersistentList()
        val s = buildChannelUiState(
            items = items,
            historyLoading = false,
            scrollToMessageId = null,
            attemptedAround = false,
            searchActive = false,
            chatId = 1L,
            feedOrder = FeedOrder.OldestUnreadFirst,
            cursors = persistentMapOf(1L to 101L),
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(2, s.initialIndex)
        assertNull(s.highlightedMessageId)
    }

    @Test
    fun `Search mode suppresses deep-link landing`() {
        // When user activates in-channel search, the deep-link anchor doesn't
        // apply — search results are their own context. Builder returns Ready
        // at index 0 with no highlight even when scrollToMessageId is set.
        val items = listOf(item(300L), item(200L), item(100L)).toPersistentList()
        val s = buildChannelUiState(
            items = items,
            historyLoading = false,
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
}
