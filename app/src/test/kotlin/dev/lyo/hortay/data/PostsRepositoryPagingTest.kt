package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Channel history paging + delete/ingest ordering in [dev.lyo.hortay.data.posts.PostsRepository]:
 *
 *  - `loadChannelHistory` keeps walking when TDLib under-returns the first page
 *    (cold cache), instead of settling for one post behind the deep-load cooldown.
 *  - `loadOlder` treats a page holding only the (inclusive) boundary message as
 *    end-of-history, and stops issuing RPCs afterwards.
 *  - A permanent delete processed BEFORE the ingest of the same message must not
 *    let the late ingest resurrect it.
 */
class PostsRepositoryPagingTest {

    private fun PostsRepositoryTestHarness.historyPages(
        chatId: Long,
        pages: Map<Long, List<Long>>,
    ) {
        td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            val ids = pages[q.fromMessageId].orEmpty()
            TdApi.Messages(ids.size, ids.map { fakeChannelMessage(chatId, it) }.toTypedArray())
        }
    }

    private fun PostsRepositoryTestHarness.idsOf(chatId: Long): Set<Long> =
        repo.posts.value.filter { it.chatId == chatId }.mapTo(HashSet()) { it.id }

    @Test
    fun `loadChannelHistory walks back when the first page under-returns`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9100L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.historyPages(
            chat.id,
            mapOf(
                0L to listOf(100L),
                100L to listOf(100L, 99L, 98L),
                98L to emptyList(),
            ),
        )

        val result = harness.repo.loadChannelHistory(chat.id, limit = 80)
        harness.advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(setOf(100L, 99L, 98L), harness.idsOf(chat.id))
        assertEquals(3, harness.td.rpcCount("GetChatHistory"),
            "first page, the walk-back page, and the empty terminator")
    }

    @Test
    fun `forced loadChannelHistory bypasses the deep-load cooldown`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9150L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.historyPages(chat.id, mapOf(0L to listOf(100L, 99L), 99L to emptyList()))

        harness.repo.loadChannelHistory(chat.id, limit = 80)
        harness.advanceUntilIdle()
        val afterFirst = harness.td.rpcCount("GetChatHistory")

        // Inside the cooldown a plain call is a no-op…
        harness.repo.loadChannelHistory(chat.id, limit = 80)
        harness.advanceUntilIdle()
        assertEquals(afterFirst, harness.td.rpcCount("GetChatHistory"))

        // …while a user pull-to-refresh (force) really refetches.
        val forced = harness.repo.loadChannelHistory(chat.id, limit = 80, force = true)
        harness.advanceUntilIdle()
        assertTrue(forced.isSuccess)
        assertTrue(harness.td.rpcCount("GetChatHistory") > afterFirst)
    }

    @Test
    fun `loadOlder marks end of history on a boundary-only page and stops pinging`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9200L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, harness.fakeChannelMessage(chat.id, 50L), emptyArray()),
        )
        harness.advanceUntilIdle()

        // Inclusive boundary: TDLib hands back the anchor itself and nothing older.
        harness.historyPages(chat.id, mapOf(50L to listOf(50L)))

        assertEquals(0, harness.repo.loadOlder(chat.id))
        val callsAfterFirst = harness.td.rpcCount("GetChatHistory")
        assertEquals(0, harness.repo.loadOlder(chat.id))
        assertEquals(callsAfterFirst, harness.td.rpcCount("GetChatHistory"),
            "end-of-history is sticky — no further RPCs")
    }

    @Test
    fun `loadOlder appends messages older than the oldest card`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9300L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, harness.fakeChannelMessage(chat.id, 50L), emptyArray()),
        )
        harness.advanceUntilIdle()

        harness.historyPages(chat.id, mapOf(50L to listOf(50L, 49L, 48L)))

        assertEquals(2, harness.repo.loadOlder(chat.id))
        assertEquals(setOf(50L, 49L, 48L), harness.idsOf(chat.id))
    }

    @Test
    fun `a permanent delete processed before the ingest blocks resurrection`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9400L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.td.emitUpdate(TdApi.UpdateDeleteMessages(chat.id, longArrayOf(42L), true, false))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, harness.fakeChannelMessage(chat.id, 42L), emptyArray()),
        )
        harness.advanceUntilIdle()

        assertTrue(harness.idsOf(chat.id).isEmpty(),
            "the deleted message must not reappear after a late ingest")
    }
}
