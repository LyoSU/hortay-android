package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure decision logic of [WebFeedSource]: the feed ∪ saved composition (and which rows
 * the Feed route must drop), the first-emit subscription reconcile across the legacy
 * upgrade, and the stale-media page choice.
 */
class WebFeedSourceCompositionTest {

    private fun tp(user: String, seq: Long, day: Int): TimelinePost = WebPostAdapter.toTimelinePost(
        seq = seq,
        publishedAtIso = "2026-01-%02dT00:00:00+00:00".format(day),
        textHtml = "post $seq",
        mediaList = emptyList(),
        webPreview = null,
        forwarded = null,
        viewsRaw = "1",
        reactionsList = emptyList(),
        channelUsername = user,
        channelTitle = user,
        channelAvatarUrl = null,
        strings = FakeStrings,
    )

    // ---- composeFeed ---------------------------------------------------------

    @Test
    fun savedPostsInsideTheFeedAddNothing() {
        val feed = persistentListOf(tp("a", 2, 2), tp("a", 1, 1))
        val result = WebFeedSource.composeFeed(feed, persistentListOf(tp("a", 1, 1)), emptySet())
        assertSame(feed, result.posts)
        assertTrue(result.savedOnlyKeys.isEmpty())
    }

    @Test
    fun savedPostsOutsideTheFeedAreMergedAndMarkedSavedOnly() {
        val feed = persistentListOf(tp("a", 2, 3))
        val oldSaved = tp("a", 1, 1) // aged out of the feed window
        val unsubscribedSaved = tp("gone", 9, 2) // channel no longer subscribed
        val result = WebFeedSource.composeFeed(
            feed,
            persistentListOf(unsubscribedSaved, oldSaved),
            emptySet(),
        )
        assertEquals(listOf(2L, 9L, 1L), result.posts.map { it.id })
        assertEquals(setOf(oldSaved.bookmarkKey(), unsubscribedSaved.bookmarkKey()), result.savedOnlyKeys)
        // The Feed route's view: everything except the saved-only rows.
        assertEquals(listOf(2L), result.posts.filter { it.bookmarkKey() !in result.savedOnlyKeys }.map { it.id })
    }

    @Test
    fun hiddenChannelIsDroppedFromFeedAndSavedRows() {
        val hiddenSaved = tp("hidden", 5, 2)
        val feed = listOf(tp("a", 1, 3), hiddenSaved, tp("hidden", 4, 1)).toPersistentList()
        val ignored = setOf(WebPostAdapter.stableChatId("hidden"))
        val result = WebFeedSource.composeFeed(feed, persistentListOf(hiddenSaved), ignored)

        assertEquals(listOf(1L), result.posts.map { it.id })
        assertEquals(emptySet<String>(), result.savedOnlyKeys)
    }

    // ---- planFirstEmitReconcile ----------------------------------------------

    @Test
    fun legacyUpgradeAdoptsTableOnlySubscriptionsInsteadOfDroppingThem() {
        // An empty legacy DataStore must not wipe the table's whole list.
        val plan = WebFeedSource.planFirstEmitReconcile(
            dataStore = emptySet(),
            table = setOf("a", "b"),
            legacyAdopted = false,
        )
        assertEquals(setOf("a", "b"), plan.adopt)
        assertTrue(plan.unsubscribe.isEmpty())
        assertTrue(plan.subscribe.isEmpty())
    }

    @Test
    fun afterAdoptionTableOnlyRowsAreLostUnsubscribes() {
        val plan = WebFeedSource.planFirstEmitReconcile(
            dataStore = setOf("a", "new"),
            table = setOf("a", "stale"),
            legacyAdopted = true,
        )
        assertEquals(setOf("stale"), plan.unsubscribe)
        assertEquals(setOf("new"), plan.subscribe)
        assertTrue(plan.adopt.isEmpty())
    }

    @Test
    fun dataStoreOnlyNamesAreSubscribedInBothPhases() {
        for (adopted in listOf(false, true)) {
            val plan = WebFeedSource.planFirstEmitReconcile(setOf("x"), emptySet(), adopted)
            assertEquals(setOf("x"), plan.subscribe)
        }
    }

    // ---- needsOlderPage -------------------------------------------------------

    @Test
    fun olderPageIsNeededOnlyBelowTheNewestPageFloor() {
        assertTrue(WebFeedSource.needsOlderPage(seq = 10, newestPageFloor = 30))
        assertFalse(WebFeedSource.needsOlderPage(seq = 30, newestPageFloor = 30))
        assertFalse(WebFeedSource.needsOlderPage(seq = 10, newestPageFloor = null))
        assertFalse(WebFeedSource.needsOlderPage(seq = null, newestPageFloor = 30))
    }
}
