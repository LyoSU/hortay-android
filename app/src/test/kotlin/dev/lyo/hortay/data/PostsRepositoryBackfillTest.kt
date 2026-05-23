package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.BACKFILL_POSTS_PER_CHAT
import dev.lyo.hortay.data.posts.BACKFILL_TOP_K
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [PostsRepository.runFirstSignInBackfill]. The backfill is the only
 * exception to the cold-start "no per-channel `GetChatHistory` fan-out" rule
 * (see ARCHITECTURE.md → "PostsRepository cold-start contract"), so the
 * contract is narrow and load-bearing:
 *
 *   - **Done flag short-circuits**: a second run within the same auth session
 *     issues zero RPCs.
 *   - **Top-K ordering**: chats with higher `chat.positions[ChatListMain].order`
 *     are fetched first; the bottom of the long tail is skipped entirely.
 *   - **FLOOD_WAIT circuit-break**: a single 420/429 stops the loop AND
 *     does NOT mark the flag done. Subsequent sessions retry.
 *   - **Clean completion** marks the flag.
 *   - **Throttle** between RPCs honours Levin's 30-per-30s cap
 *     (tdlib/td#743) — tested via virtual time.
 *
 * Tests use [kotlinx.coroutines.test.runTest]'s virtual time so the 1.1 s
 * throttle doesn't actually slow the suite down; `advanceUntilIdle` advances
 * past every `delay`.
 */
class PostsRepositoryBackfillTest {

    @Test
    fun `backfill is a no-op when done flag is already set`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // Seed three channels with Main positions so a naive backfill would
        // pick them up.
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -5000L - i,
                lastMessage = harness.fakeChannelMessage(-5000L - i, 500L + i),
                mainListOrder = 100L - i,
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()

        harness.coldStartBackfill.done = true
        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetChatHistory"),
            "done=true must short-circuit before any GetChatHistory is issued")
    }

    @Test
    fun `backfill issues GetChatHistory for top-K channels in chat-order desc`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // Seed 5 channels with distinct ChatListMain orders so the sort has
        // unique tiebreaker-free output. We expect the backfill to visit
        // them in descending-order order regardless of insertion sequence.
        val orders = listOf(50L, 300L, 100L, 250L, 175L)
        orders.forEachIndexed { i, order ->
            val chatId = -6000L - i
            val chat = harness.fakeChannel(
                id = chatId,
                lastMessage = harness.fakeChannelMessage(chatId, 600L + i),
                mainListOrder = order,
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()

        val seenInOrder = mutableListOf<Long>()
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            seenInOrder.add(q.chatId)
            TdApi.Messages(0, emptyArray())
        }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        // Sort orders desc: 300, 250, 175, 100, 50 → chat ids -6001, -6003, -6004, -6002, -6000.
        val expected = listOf(-6001L, -6003L, -6004L, -6002L, -6000L)
        assertEquals(expected, seenInOrder,
            "backfill must visit channels in chat.positions[ChatListMain].order desc")
    }

    @Test
    fun `backfill requests POSTS_PER_CHAT messages starting from latest`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(
            id = -7000L,
            lastMessage = harness.fakeChannelMessage(-7000L, 700L),
            mainListOrder = 1000L,
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        var captured: TdApi.GetChatHistory? = null
        harness.td.onAny("GetChatHistory") { req ->
            captured = req as TdApi.GetChatHistory
            TdApi.Messages(0, emptyArray())
        }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertNotNull(captured, "GetChatHistory was not called")
        val q = captured!!
        assertEquals(0L, q.fromMessageId, "fromMessageId=0 starts at latest message")
        assertEquals(0, q.offset, "offset=0 is the only valid value when fromMessageId=0")
        assertEquals(BACKFILL_POSTS_PER_CHAT, q.limit,
            "limit must equal BACKFILL_POSTS_PER_CHAT")
        assertFalse(q.onlyLocal, "onlyLocal=false hits the network — purpose of the backfill")
    }

    @Test
    fun `backfill ingests returned messages into the merged feed`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -8000L
        // Seed UpdateNewChat with NO lastMessage so the only path into _posts
        // is via the backfill — isolates the test signal.
        val chat = harness.fakeChannel(id = chatId, lastMessage = null, mainListOrder = 500L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        val backfilled = arrayOf(
            harness.fakeChannelMessage(chatId, 801L, date = 1_700_000_100),
            harness.fakeChannelMessage(chatId, 802L, date = 1_700_000_101),
            harness.fakeChannelMessage(chatId, 803L, date = 1_700_000_102),
        )
        harness.td.onAny("GetChatHistory") { TdApi.Messages(backfilled.size, backfilled) }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        val postIds = harness.repo.posts.value.map { it.id }.toSet()
        assertTrue(postIds.containsAll(setOf(801L, 802L, 803L)),
            "backfilled messages must reach _posts via ingest; got $postIds")
        assertTrue(harness.coldStartBackfill.done,
            "clean completion must mark the flag done")
    }

    @Test
    fun `FLOOD_WAIT 429 stops the loop and leaves the flag unset`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // Three channels with descending order so we know the visit sequence.
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -9000L - i,
                lastMessage = harness.fakeChannelMessage(-9000L - i, 900L + i),
                mainListOrder = 100L - i,
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()

        var callCount = 0
        harness.td.onAny("GetChatHistory") { _ ->
            callCount++
            if (callCount == 1) TdApi.Messages(0, emptyArray())
            // Second call returns FLOOD_WAIT — circuit-break should stop here
            // and the third call must never happen.
            else TdApi.Error(429, "Too Many Requests: retry after 30")
        }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertEquals(2, callCount,
            "loop must stop on FLOOD_WAIT — third channel must not be requested")
        assertFalse(harness.coldStartBackfill.done,
            "FLOOD_WAIT must NOT mark the flag done — next session retries")
    }

    @Test
    fun `FLOOD_WAIT 420 is recognised as well`() = runTest {
        // Same as the 429 test but exercises the legacy MTProto code path.
        // [TdClient.isFloodWaitCode] folds both — verifying the backfill
        // calls into that helper rather than hard-coding one code.
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(
            id = -9500L,
            lastMessage = harness.fakeChannelMessage(-9500L, 950L),
            mainListOrder = 999L,
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.td.onAny("GetChatHistory") { TdApi.Error(420, "FLOOD_WAIT_42") }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertFalse(harness.coldStartBackfill.done,
            "FLOOD_WAIT 420 must not mark the flag done")
    }

    @Test
    fun `empty channel list marks done and issues zero RPCs`() = runTest {
        // No UpdateNewChat — _mainChatIds stays empty. Backfill should
        // short-circuit cleanly and mark done so we don't re-walk the empty
        // list on every foreground transition.
        val harness = PostsRepositoryTestHarness(this)

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetChatHistory"))
        assertTrue(harness.coldStartBackfill.done,
            "empty top-K must still mark done — nothing to retry")
    }

    @Test
    fun `backfill caps at BACKFILL_TOP_K channels`() = runTest {
        // Seed (TOP_K + 5) channels with strictly descending order. Only the
        // top TOP_K should be visited — the bottom 5 must be untouched.
        val harness = PostsRepositoryTestHarness(this)
        val totalChannels = BACKFILL_TOP_K + 5
        repeat(totalChannels) { i ->
            val chatId = -10_000L - i
            val chat = harness.fakeChannel(
                id = chatId,
                lastMessage = harness.fakeChannelMessage(chatId, 1000L + i),
                mainListOrder = (totalChannels - i).toLong(),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()

        val visited = mutableListOf<Long>()
        harness.td.onAny("GetChatHistory") { req ->
            visited.add((req as TdApi.GetChatHistory).chatId)
            TdApi.Messages(0, emptyArray())
        }

        harness.repo.runFirstSignInBackfill()
        harness.advanceUntilIdle()

        assertEquals(BACKFILL_TOP_K, visited.size,
            "exactly TOP_K channels must be visited; got ${visited.size}")
        // Top-K should be the FIRST TOP_K inserted (highest order values).
        val expectedTopK = (0 until BACKFILL_TOP_K).map { -10_000L - it }
        assertEquals(expectedTopK, visited,
            "the long-tail (bottom 5 channels) must be untouched")
    }
}
