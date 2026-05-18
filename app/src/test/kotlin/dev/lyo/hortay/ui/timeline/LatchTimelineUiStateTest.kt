package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatchTimelineUiStateTest {

    private fun item(id: Long): FeedItem =
        FeedItem(testPost(id = id, chatId = 1L, date = id))

    private fun ready(items: List<FeedItem>, initialIndex: Int, cursorsValue: Long = 100L): TimelineUiState.Ready =
        TimelineUiState.Ready(
            items = items.toPersistentList(),
            initialIndex = initialIndex,
            frozenCursors = persistentMapOf(1L to cursorsValue),
        )

    @Test
    fun `null previous adopts candidate verbatim`() {
        val candidate = ready(items = listOf(item(1L), item(2L)), initialIndex = 1)
        val out = reduceTimelineUiState(previous = null, candidate = candidate, refreshJustCompleted = false)
        assertSame(candidate, out)
    }

    @Test
    fun `Loading previous adopts Ready candidate verbatim`() {
        val candidate = ready(items = listOf(item(1L)), initialIndex = 0)
        val out = reduceTimelineUiState(
            previous = TimelineUiState.Loading,
            candidate = candidate,
            refreshJustCompleted = false,
        )
        assertSame(candidate, out)
    }

    @Test
    fun `Ready to Ready preserves initialIndex and frozenCursors`() {
        val first = ready(items = listOf(item(1L), item(2L)), initialIndex = 1, cursorsValue = 100L)
        val candidate = ready(items = listOf(item(1L), item(2L), item(3L)), initialIndex = 2, cursorsValue = 200L)
        val out = reduceTimelineUiState(previous = first, candidate = candidate, refreshJustCompleted = false)
        assertTrue(out is TimelineUiState.Ready)
        out as TimelineUiState.Ready
        assertEquals(1, out.initialIndex)
        assertEquals(persistentMapOf(1L to 100L), out.frozenCursors)
        assertEquals(candidate.items, out.items)
    }

    @Test
    fun `refresh just completed re-latches frozenCursors but preserves initialIndex`() {
        // PTR while scrolled MUST preserve the user's scroll anchor — re-keying
        // [rememberSaveable] on a fresh initialIndex would yank them. Only the
        // boundary cursors get re-latched so the unread divider can update.
        val first = ready(items = listOf(item(1L), item(2L)), initialIndex = 1, cursorsValue = 100L)
        val candidate = ready(items = listOf(item(1L), item(2L), item(3L)), initialIndex = 2, cursorsValue = 200L)
        val out = reduceTimelineUiState(previous = first, candidate = candidate, refreshJustCompleted = true)
        assertTrue(out is TimelineUiState.Ready)
        out as TimelineUiState.Ready
        assertEquals(1, out.initialIndex)
        assertEquals(persistentMapOf(1L to 200L), out.frozenCursors)
    }

    @Test
    fun `Ready to Loading adopts Loading (e-g- logout)`() {
        val first = ready(items = listOf(item(1L)), initialIndex = 0)
        val out = reduceTimelineUiState(previous = first, candidate = TimelineUiState.Loading, refreshJustCompleted = false)
        assertSame(TimelineUiState.Loading, out)
    }

    @Test
    fun `Ready to Empty adopts Empty (e-g- user unsubscribed everything)`() {
        val first = ready(items = listOf(item(1L)), initialIndex = 0)
        val out = reduceTimelineUiState(previous = first, candidate = TimelineUiState.Empty, refreshJustCompleted = false)
        assertSame(TimelineUiState.Empty, out)
    }

}
