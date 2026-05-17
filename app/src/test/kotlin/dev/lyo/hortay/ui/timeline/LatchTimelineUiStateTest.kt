package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.testutil.testPost
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatchTimelineUiStateTest {

    private fun item(id: Long): FeedItem.Single =
        FeedItem.Single(testPost(id = id, chatId = 1L, date = id))

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

    @Test
    fun `preserveReady keeps Ready during covered overlay transient Loading`() {
        val first = ready(items = listOf(item(1L)), initialIndex = 3)
        val out = reduceTimelineUiState(
            previous = first,
            candidate = TimelineUiState.Loading,
            refreshJustCompleted = false,
            preserveReady = true,
        )
        assertSame(first, out)
    }

    @Test
    fun `preserveReady freezes Ready to Ready transition under overlay`() {
        // Regression for "Feed scroll position lost after Comments to Channel
        // to back". While [coveredByOverlay] = true (Comments / ChannelScreen
        // mounted on top), [_posts] still emits — [ChannelViewModel.init]
        // issues `loadChannelHistory(chatId)` which writes the deepened slice
        // into the same global posts flow [TimelineScreen] is subscribed to.
        // The downstream `candidate` therefore arrives as a fresh Ready with
        // a different `items` list. Before this contract was tightened the
        // reducer copied `candidate.items` into the latched state — which
        // means the LazyColumn UNDER the overlay reconciled the new keyed
        // dataset. When the new list regrouped the row that was first-visible
        // (loadChannelHistory promoting a reply parent → single→thread merge,
        // new arrivals in Newest mode shifting indices) the firstVisibleItemKey
        // lookup missed and the scroll relayouted off-anchor — what the user
        // saw on pop as "back lands at the start of the feed".
        //
        // The fix: when [preserveReady] is true ANY Ready→Ready transition is
        // a no-op. Live state flows past the latch unchanged once the overlay
        // is dismissed (preserveReady flips false), at which point the
        // LazyColumn reconciles in one batched update from the user's actual
        // scroll position.
        val first = ready(items = listOf(item(1L), item(2L)), initialIndex = 1, cursorsValue = 100L)
        val candidate = ready(items = listOf(item(1L), item(2L), item(3L)), initialIndex = 2, cursorsValue = 200L)
        val out = reduceTimelineUiState(
            previous = first,
            candidate = candidate,
            refreshJustCompleted = false,
            preserveReady = true,
        )
        assertSame(first, out)
    }

    @Test
    fun `preserveReady freeze releases once overlay dismisses`() {
        // Once the user pops the overlay (preserveReady flips false) the
        // latch resumes normal Ready→Ready semantics: items adopt the live
        // candidate so the LazyColumn renders any posts that arrived while
        // the overlay was up; initialIndex is still preserved so the user's
        // scroll position from before the overlay opens isn't moved.
        val first = ready(items = listOf(item(1L), item(2L)), initialIndex = 1, cursorsValue = 100L)
        val candidate = ready(items = listOf(item(1L), item(2L), item(3L)), initialIndex = 2, cursorsValue = 200L)
        val out = reduceTimelineUiState(
            previous = first,
            candidate = candidate,
            refreshJustCompleted = false,
            preserveReady = false,
        )
        assertTrue(out is TimelineUiState.Ready)
        out as TimelineUiState.Ready
        assertEquals(1, out.initialIndex)
        assertEquals(candidate.items, out.items)
    }

    @Test
    fun `preserveReady does not block initial Ready landing from null`() {
        // Cold-start mounted with the overlay already on top (e.g. the user
        // somehow lands on a state where coveredByOverlay is already true on
        // first composition). Latch must still adopt the FIRST Ready —
        // otherwise [TimelineScreen] would render its skeleton under the
        // overlay forever and the LazyColumn would never mount.
        val candidate = ready(items = listOf(item(1L)), initialIndex = 0)
        val out = reduceTimelineUiState(
            previous = null,
            candidate = candidate,
            refreshJustCompleted = false,
            preserveReady = true,
        )
        assertSame(candidate, out)
    }
}
