package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.async
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
    fun `loadChannelHistory re-asks a nothing-new follow-up page instead of settling on one post`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9120L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        // Non-member channel, cold cache: the head page is the locally-known last message,
        // the first walk-back serves only that boundary message again, and the repeat
        // carries the server slice.
        val walkBacks = java.util.concurrent.atomic.AtomicInteger(0)
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            val ids = when {
                q.fromMessageId == 0L -> listOf(100L)
                walkBacks.getAndIncrement() == 0 -> listOf(100L)
                else -> listOf(100L, 99L, 98L)
            }
            TdApi.Messages(ids.size, ids.map { harness.fakeChannelMessage(chat.id, it) }.toTypedArray())
        }

        val result = harness.repo.loadChannelHistory(chat.id, limit = 80)
        harness.advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(setOf(100L, 99L, 98L), harness.idsOf(chat.id))
    }

    @Test
    fun `loadChannelHistory of a one-post channel stops after one nothing-new retry`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9130L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.historyPages(chat.id, mapOf(0L to listOf(100L), 100L to listOf(100L)))

        harness.repo.loadChannelHistory(chat.id, limit = 80)
        harness.advanceUntilIdle()

        assertEquals(setOf(100L), harness.idsOf(chat.id))
        assertEquals(3, harness.td.rpcCount("GetChatHistory"),
            "head page, the boundary-only page, and its single retry")
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
        assertEquals(3, callsAfterFirst,
            "an anchor-only page is re-asked a bounded number of times before it means the end")
        assertEquals(0, harness.repo.loadOlder(chat.id))
        assertEquals(callsAfterFirst, harness.td.rpcCount("GetChatHistory"),
            "end-of-history is sticky — no further RPCs")
    }

    @Test
    fun `loadOlder retries an anchor-only cold-cache page instead of ending paging`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9250L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, harness.fakeChannelMessage(chat.id, 50L), emptyArray()),
        )
        harness.advanceUntilIdle()

        // Cold cache: the first pass serves only the locally-known anchor; the repeat
        // carries the server slice.
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        harness.td.onAny("GetChatHistory") {
            val ids = if (calls.getAndIncrement() == 0) listOf(50L) else listOf(50L, 49L, 48L)
            TdApi.Messages(ids.size, ids.map { harness.fakeChannelMessage(chat.id, it) }.toTypedArray())
        }

        assertEquals(2, harness.repo.loadOlder(chat.id))
        assertEquals(setOf(50L, 49L, 48L), harness.idsOf(chat.id))
    }

    @Test
    fun `loadOlder does not publish a post deleted while its page was in flight`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -9350L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, harness.fakeChannelMessage(chat.id, 50L), emptyArray()),
        )
        harness.advanceUntilIdle()

        val pageRequested = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releasePage = kotlinx.coroutines.CompletableDeferred<Unit>()
        harness.td.onAnySuspending("GetChatHistory") {
            pageRequested.complete(Unit)
            releasePage.await()
            val ids = listOf(50L, 49L, 48L)
            TdApi.Messages(ids.size, ids.map { harness.fakeChannelMessage(chat.id, it) }.toTypedArray())
        }

        val older = async { harness.repo.loadOlder(chat.id) }
        pageRequested.await()
        harness.td.emitUpdate(TdApi.UpdateDeleteMessages(chat.id, longArrayOf(49L), true, false))
        harness.advanceUntilIdle()
        releasePage.complete(Unit)
        older.await()

        assertEquals(setOf(50L, 48L), harness.idsOf(chat.id),
            "a delete processed while the page was in flight must not be resurrected")
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
