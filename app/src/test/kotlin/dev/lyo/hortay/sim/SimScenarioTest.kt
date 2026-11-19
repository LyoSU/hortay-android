package dev.lyo.hortay.sim

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Drives [dev.lyo.hortay.data.posts.PostsRepository] against the stateful [SimTelegram]
 * model: a few deterministic scenarios for documented TDLib contracts, and a seeded random
 * walk that checks the safety properties in [violations] after every step. A failing seed
 * reproduces exactly — rerun with that seed and quirk set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimScenarioTest {

    private suspend fun SimHarness.coldStart(account: SimAccount) {
        sim.signIn(account)
        repo.triggerInitialSync()
        settle()
    }

    private fun account(name: String, base: Long, channels: Int, postsEach: Int): SimAccount =
        SimAccount(name).also { acc ->
            repeat(channels) { i ->
                val ch = acc.channel(base - i, archived = i % 4 == 3)
                repeat(postsEach) {
                    val id = ch.nextServerId++ shl 20
                    ch.messages[id] = SimMessage(id, ch.id, 1_700_000_000 + it, 0L, "seed $id")
                }
                ch.order = ch.messages.lastKey()
            }
        }

    @Test
    fun `cold start shows every channel without any history request`() = runTest {
        val h = SimHarness(this)
        h.coldStart(account("a", base = -1000L, channels = 12, postsEach = 5))

        assertEquals(emptyList<String>(), h.violations())
        assertEquals(emptyList<String>(), h.missingNewest())
        assertEquals(12, h.posts.map { it.chatId }.distinct().size)
        assertEquals(0, h.sim.callCount("GetChatHistory", networkedOnly = true))
        assertEquals(0, h.sim.callCount("GetChat"))
    }

    @Test
    fun `cold start survives last-message updates that outrun their chat`() = runTest {
        val h = SimHarness(this, SimQuirks(lastMessageBeforeNewChat = true))
        h.coldStart(account("a", base = -1000L, channels = 8, postsEach = 3))

        assertEquals(emptyList<String>(), h.violations())
        assertEquals(emptyList<String>(), h.missingNewest())
    }

    @Test
    fun `older paging reaches the first post whatever page shape TDLib picks`() = runTest {
        val shapes = listOf(
            SimQuirks(),
            SimQuirks(maxHistoryPage = 2),
            SimQuirks(historyInclusiveBoundary = true),
            SimQuirks(historyInclusiveBoundary = true, maxHistoryPage = 1),
            SimQuirks(coldFirstPassLocalOnly = false, maxHistoryPage = 3),
        )
        for (quirks in shapes) {
            val h = SimHarness(TestScope(testScheduler), quirks)
            val acc = account("a", base = -2000L, channels = 1, postsEach = 14)
            h.coldStart(acc)
            val chatId = acc.channels.keys.single()

            // Bounded: each call may add nothing on a short page without being stuck.
            repeat(40) { h.repo.loadOlder(chatId, limit = 5) }
            h.settle()

            val expected = acc.channels.getValue(chatId).messages.keys.toSet()
            assertEquals(expected, h.posts.filter { it.chatId == chatId }.map { it.id }.toSet(), "quirks=$quirks")
            assertEquals(emptyList<String>(), h.violations(), "quirks=$quirks")
        }
    }

    @Test
    fun `an album posted live renders as one complete card`() = runTest {
        val h = SimHarness(this)
        val acc = account("a", base = -3000L, channels = 2, postsEach = 2)
        h.coldStart(acc)
        val chatId = acc.channels.keys.first()

        val ids = h.sim.post(chatId, albumSize = 4)
        h.settle()

        val card = h.posts.single { it.chatId == chatId && ids.last() in it.albumMessageIds.ifEmpty { listOf(it.id) } }
        assertEquals(ids.toSet(), card.albumMessageIds.toSet())
        assertEquals(emptyList<String>(), h.violations())
    }

    @Test
    fun `nothing of the previous account survives a sign-out`() = runTest {
        val h = SimHarness(this, SimQuirks(latencyMs = 50))
        val first = account("a", base = -4000L, channels = 6, postsEach = 6)
        h.coldStart(first)
        val chatId = first.channels.keys.first()

        // A slow history request is still in flight when the user signs out.
        val inFlight = async { runCatching { h.repo.loadChannelHistory(chatId, force = true) } }
        testScheduler.advanceTimeBy(10)
        h.logOut()
        h.coldStart(account("b", base = -5000L, channels = 4, postsEach = 3))
        inFlight.await()
        h.settle()

        assertEquals(emptyList<String>(), h.violations())
        assertEquals(emptyList<String>(), h.missingNewest())
    }

    @Test
    fun `random walks keep the feed consistent with the server`() = runTest {
        val quirkSets = listOf(
            SimQuirks(),
            SimQuirks(latencyMs = 40, maxHistoryPage = 3),
            SimQuirks(latencyMs = 15, historyInclusiveBoundary = true, lastMessageBeforeNewChat = true),
            SimQuirks(coldFirstPassLocalOnly = false, chatPageSize = 1),
        )
        for (seed in 1..SEEDS) {
            val quirks = quirkSets[seed % quirkSets.size]
            val h = SimHarness(TestScope(testScheduler), quirks)
            val rnd = Random(seed)
            var generation = 0
            var acc = account("acc0", base = -10_000L, channels = 5, postsEach = 4)
            h.coldStart(acc)
            val trace = ArrayList<String>()

            repeat(STEPS) {
                // The reader's pace: actions are seconds apart, not back to back — the
                // history budget in [violations] is a rate over virtual time.
                testScheduler.advanceTimeBy(rnd.nextLong(THINK_MIN_MS, THINK_MAX_MS))
                val chatId = acc.channels.keys.random(rnd)
                val channel = acc.channels.getValue(chatId)
                when (rnd.nextInt(10)) {
                    0, 1 -> { trace += "post $chatId"; h.sim.post(chatId) }
                    2 -> { trace += "album $chatId"; h.sim.post(chatId, albumSize = rnd.nextInt(2, 6)) }
                    3 -> channel.messages.keys.randomOrNull(rnd)?.let {
                        trace += "delete $chatId/$it"
                        h.sim.delete(chatId, listOf(it))
                    }
                    4, 5 -> { trace += "older $chatId"; h.repo.loadOlder(chatId, limit = 5) }
                    6 -> { trace += "history $chatId"; h.repo.loadChannelHistory(chatId, limit = 8, force = true) }
                    7 -> {
                        // A page in flight while the server deletes one of its posts.
                        trace += "older+delete $chatId"
                        val older = async { runCatching { h.repo.loadOlder(chatId, limit = 5) } }
                        testScheduler.advanceTimeBy(quirks.latencyMs / 2)
                        channel.messages.keys.randomOrNull(rnd)?.let { h.sim.delete(chatId, listOf(it)) }
                        older.await()
                    }
                    8 -> {
                        trace += "history+post $chatId"
                        val load = async { runCatching { h.repo.loadChannelHistory(chatId, limit = 8, force = true) } }
                        testScheduler.advanceTimeBy(quirks.latencyMs / 2)
                        h.sim.post(chatId, albumSize = rnd.nextInt(1, 4))
                        load.await()
                    }
                    else -> {
                        trace += "relogin"
                        val load = async { runCatching { h.repo.loadChannelHistory(chatId, limit = 8, force = true) } }
                        testScheduler.advanceTimeBy(quirks.latencyMs / 2)
                        h.logOut()
                        generation++
                        acc = account("acc$generation", base = -10_000L - generation * 100L, channels = 4, postsEach = 3)
                        h.coldStart(acc)
                        load.await()
                    }
                }
                h.settle()
                assertEquals(emptyList<String>(), h.violations(), "seed=$seed quirks=$quirks trace=$trace")
            }
            assertEquals(emptyList<String>(), h.missingNewest(), "seed=$seed quirks=$quirks trace=$trace")
        }
    }

    private companion object {
        const val SEEDS = 200
        const val STEPS = 25
        const val THINK_MIN_MS = 2_000L
        const val THINK_MAX_MS = 6_000L
    }
}
