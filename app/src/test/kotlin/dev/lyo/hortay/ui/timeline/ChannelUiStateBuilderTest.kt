package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelUiStateBuilderTest {

    private fun item(id: Long, album: List<Long> = emptyList()): FeedItem.Single =
        FeedItem.Single(testPost(id = id, chatId = 1L, date = id, albumMessageIds = album))

    @Test
    fun `Resolving while history loading regardless of items`() {
        val s = buildChannelUiState(
            items = persistentListOf(),
            historyLoading = true,
            scrollToMessageId = 200L,
            attemptedAround = false,
            searchActive = false,
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
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
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
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(0, s.initialIndex)
        assertNull(s.highlightedMessageId)
    }
}
