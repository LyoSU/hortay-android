package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.FakeTdSender
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TdClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendedChannelsRepositoryTest {

    private class MemoryStore : SimilarChatsStore {
        val map = HashMap<Long, SimilarEntry>()
        var cleared = false
        var puts = 0
        override suspend fun load(): Map<Long, SimilarEntry> = HashMap(map)
        override suspend fun put(source: Long, entry: SimilarEntry) { map[source] = entry; puts++ }
        override suspend fun retain(sources: Set<Long>) { map.keys.retainAll(sources) }
        override suspend fun clear() { map.clear(); cleared = true }
    }

    /** Chat ids are negative; the supergroup id is the absolute value. */
    private fun FakeTdSender.channels(similar: (Long) -> TdApi.Object) {
        onAny("GetChat") { req ->
            val id = (req as TdApi.GetChat).chatId
            TdApi.Chat().apply {
                this.id = id
                title = "Channel $id"
                type = TdApi.ChatTypeSupergroup().apply {
                    supergroupId = -id
                    isChannel = true
                }
            }
        }
        onAny("GetSupergroup") { req ->
            TdApi.Supergroup().apply {
                id = (req as TdApi.GetSupergroup).supergroupId
                isChannel = true
                memberCount = 100
                status = TdApi.ChatMemberStatusLeft()
            }
        }
        onAny("GetChatSimilarChats") { req -> similar((req as TdApi.GetChatSimilarChats).chatId) }
    }

    private fun TestScope.repo(
        td: FakeTdSender,
        store: SimilarChatsStore,
        sources: List<Long>,
        hidden: Set<Long> = emptySet(),
        now: () -> Long = { NOW },
    ) = RecommendedChannelsRepository(
        td = td,
        discovery = ChannelDiscoveryRepository(td) { this },
        store = store,
        subscribedChannels = { sources },
        hiddenChannels = { hidden },
        startupPhase = MutableStateFlow(StartupCoordinator.Phase.Active),
        sessionScope = { this },
        clock = now,
        elapsedMs = { testScheduler.currentTime },
    )

    private fun chats(vararg ids: Long) = TdApi.Chats(ids.size, ids)

    @Test
    fun `scan aggregates votes and publishes cards with the top voter`() = runTest {
        val td = FakeTdSender().apply {
            channels { source ->
                when (source) {
                    -1L -> chats(-100L, -200L)
                    -2L -> chats(-200L)
                    else -> chats()
                }
            }
        }
        val repo = repo(td, MemoryStore(), sources = listOf(-1L, -2L))
        repo.requestScan()
        advanceUntilIdle()

        val state = repo.state.value
        assertEquals(listOf(-200L, -100L), state.items.map { it.channel.chatId })
        assertEquals(-1L, state.items.first().topVoter)
        assertEquals("Channel -1", state.items.first().topVoterTitle)
        assertTrue(state.complete)
        assertFalse(state.scanning)
        assertEquals(2, td.rpcCount("GetChatSimilarChats"))
        assertEquals(0, td.rpcCount("GetSupergroupFullInfo"))
    }

    @Test
    fun `fresh entries are not refetched, stale ones are`() = runTest {
        val td = FakeTdSender().apply { channels { chats(-100L) } }
        val store = MemoryStore().apply {
            map[-1L] = SimilarEntry(listOf(-100L), NOW - 1_000)
            map[-2L] = SimilarEntry(listOf(-100L), NOW - RecommendedChannelsRepository.TTL_MS - 1)
        }
        val repo = repo(td, store, sources = listOf(-1L, -2L))
        repo.requestScan()
        advanceUntilIdle()

        assertEquals(1, td.rpcCount("GetChatSimilarChats"))
        assertEquals(NOW, store.map.getValue(-2L).fetchedAtMs)
        assertTrue(repo.state.value.complete)

        // Reopening with everything fresh costs no network at all.
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"))
    }

    @Test
    fun `entries of channels the user left are pruned`() = runTest {
        val td = FakeTdSender().apply { channels { chats() } }
        val store = MemoryStore().apply { map[-9L] = SimilarEntry(listOf(-100L), NOW) }
        repo(td, store, sources = listOf(-1L)).requestScan()
        advanceUntilIdle()
        assertFalse(-9L in store.map)
    }

    @Test
    fun `flood stops the scan quietly and the next demand resumes from the next source`() = runTest {
        val asked = mutableListOf<Long>()
        var flood = true
        val td = FakeTdSender().apply {
            channels { source ->
                asked += source
                if (source == -2L && flood) TdApi.Error(429, "Too Many Requests: retry after 27") else chats(-100L)
            }
        }
        val store = MemoryStore()
        var now = NOW
        val repo = repo(td, store, sources = listOf(-1L, -2L, -3L), now = { now })
        repo.requestScan()
        advanceUntilIdle()

        assertEquals(listOf(-1L, -2L), asked, "no retry loop and nothing after the flood")
        assertEquals(setOf(-1L), store.map.keys)
        assertFalse(repo.state.value.complete)
        assertFalse(repo.state.value.scanning)
        assertEquals(listOf(-100L), repo.state.value.items.map { it.channel.chatId })

        flood = false
        now += RecommendedChannelsRepository.FLOOD_COOLDOWN_MS
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(listOf(-1L, -2L, -2L, -3L), asked)
        assertTrue(repo.state.value.complete)
    }

    @Test
    fun `send timeout also stops the scan`() = runTest {
        val td = FakeTdSender().apply {
            channels { TdApi.Error(TdClient.SEND_TIMEOUT_CODE, "SEND_TIMEOUT") }
        }
        val repo = repo(td, MemoryStore(), sources = listOf(-1L, -2L))
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"))
    }

    @Test
    fun `one scan spends at most the call budget and skips hidden channels`() = runTest {
        val td = FakeTdSender().apply { channels { chats() } }
        val sources = (1L..80L).map { -it }
        val repo = repo(td, MemoryStore(), sources = sources, hidden = setOf(-1L))
        repo.requestScan()
        advanceUntilIdle()

        assertEquals(RecommendedChannelsRepository.MAX_CALLS_PER_SCAN, td.rpcCount("GetChatSimilarChats"))
        assertEquals(79, repo.state.value.total)
        assertEquals(RecommendedChannelsRepository.MAX_CALLS_PER_SCAN, repo.state.value.scanned)
        assertFalse(repo.state.value.complete)
    }

    @Test
    fun `logout wipe clears memory and the store`() = runTest {
        val td = FakeTdSender().apply { channels { chats(-100L) } }
        val store = MemoryStore()
        val repo = repo(td, store, sources = listOf(-1L))
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(1, repo.state.value.items.size)

        repo.clear()
        assertTrue(store.cleared)
        assertTrue(store.map.isEmpty())
        assertEquals(RecommendedState(), repo.state.value)
    }

    @Test
    fun `a join elsewhere keeps the row flagged and later scans leave it out`() = runTest {
        val td = FakeTdSender().apply { channels { chats(-100L, -200L) } }
        val repo = repo(td, MemoryStore(), sources = listOf(-1L))
        repo.requestScan()
        advanceUntilIdle()

        repo.onMembershipChanged(-100L, isMember = true)
        assertTrue(repo.state.value.items.first { it.channel.chatId == -100L }.channel.isMember)

        repo.requestScan()
        advanceUntilIdle()
        assertEquals(listOf(-200L), repo.state.value.items.map { it.channel.chatId })
    }

    @Test
    fun `similar-chat calls are spaced and the shared throttle stays free in between`() = runTest {
        val callTimes = mutableListOf<Long>()
        val td = FakeTdSender().apply {
            channels {
                callTimes += testScheduler.currentTime
                chats()
            }
            onAny("SearchPublicChat") { TdApi.Error(400, "USERNAME_NOT_OCCUPIED") }
        }
        val discovery = ChannelDiscoveryRepository(td) { this }
        val repo = RecommendedChannelsRepository(
            td = td,
            discovery = discovery,
            store = MemoryStore(),
            subscribedChannels = { listOf(-1L, -2L, -3L) },
            hiddenChannels = { emptySet() },
            startupPhase = MutableStateFlow(StartupCoordinator.Phase.Active),
            sessionScope = { this },
            clock = { NOW },
            elapsedMs = { testScheduler.currentTime },
        )
        repo.requestScan()
        runCurrent()
        assertEquals(1, callTimes.size)

        // A curated resolve during the scan's spacing sleep is not queued behind it.
        val resolveStart = testScheduler.currentTime
        var resolvedAt = -1L
        launch {
            discovery.resolve("somechannel")
            resolvedAt = testScheduler.currentTime
        }
        advanceTimeBy(RecommendedChannelsRepository.SIMILAR_CALL_SPACING_MS - 1)
        assertTrue(resolvedAt >= 0, "resolve waited for the scan's spacing")
        assertTrue(resolvedAt - resolveStart < RecommendedChannelsRepository.SIMILAR_CALL_SPACING_MS)

        advanceUntilIdle()
        assertEquals(3, callTimes.size)
        callTimes.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= RecommendedChannelsRepository.SIMILAR_CALL_SPACING_MS, "calls $a → $b too close")
        }
    }

    @Test
    fun `the call budget is safe for the server bucket on its own`() {
        val budget = RecommendedChannelsRepository.MAX_CALLS_PER_SCAN
        val spacing = RecommendedChannelsRepository.SIMILAR_CALL_SPACING_MS
        // At most this many calls start inside any 30 s window (both edges included)...
        val perWindow = (BUCKET_WINDOW_MS / spacing + 1).toInt()
        assertTrue(perWindow * 2 <= BUCKET_CALLS, "spacing must keep a window under half the bucket")
        // ...and the whole budget never fits in one window.
        assertTrue((budget - 1) * spacing > BUCKET_WINDOW_MS)
    }

    @Test
    fun `a flood starts a cooldown that blocks re-entry until it passes`() = runTest {
        var flood = true
        val td = FakeTdSender().apply {
            channels { source ->
                if (source == -2L && flood) TdApi.Error(429, "Too Many Requests: retry after 27") else chats(-100L)
            }
        }
        var now = NOW
        val repo = repo(td, MemoryStore(), sources = listOf(-1L, -2L, -3L), now = { now })
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(2, td.rpcCount("GetChatSimilarChats"))

        // Typing and clearing the query re-enters the sheet's empty state: nothing happens.
        flood = false
        now += RecommendedChannelsRepository.FLOOD_COOLDOWN_MS - 1
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(2, td.rpcCount("GetChatSimilarChats"))

        now += 1
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(4, td.rpcCount("GetChatSimilarChats"))
        assertTrue(repo.state.value.complete)
    }

    @Test
    fun `a longer server retry-after stretches the cooldown, capped`() = runTest {
        val td = FakeTdSender().apply {
            channels { TdApi.Error(420, "FLOOD_WAIT_999999") }
        }
        var now = NOW
        val repo = repo(td, MemoryStore(), sources = listOf(-1L), now = { now })
        repo.requestScan()
        advanceUntilIdle()
        now += RecommendedChannelsRepository.MAX_FLOOD_COOLDOWN_MS - 1
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"))
        now += 1
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(2, td.rpcCount("GetChatSimilarChats"))
    }

    @Test
    fun `clear resets the cooldown`() = runTest {
        var flood = true
        val td = FakeTdSender().apply {
            channels { if (flood) TdApi.Error(429, "Too Many Requests: retry after 27") else chats() }
        }
        val repo = repo(td, MemoryStore(), sources = listOf(-1L))
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"))

        repo.clear()
        flood = false
        repo.requestScan()
        advanceUntilIdle()
        assertEquals(2, td.rpcCount("GetChatSimilarChats"))
        assertTrue(repo.state.value.complete)
    }

    @Test
    fun `clear during a scan leaves empty state and store, and nothing is written after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val td = FakeTdSender().apply {
            channels { chats(-100L) }
            // A call that finishes even though its scan is cancelled, as a TDLib answer can.
            onAnySuspending("GetChatSimilarChats") {
                withContext(NonCancellable) { gate.await() }
                chats(-100L)
            }
        }
        val store = MemoryStore().apply { map[-1L] = SimilarEntry(listOf(-100L), NOW) }
        val repo = repo(td, store, sources = listOf(-1L, -2L, -3L))
        repo.requestScan()
        runCurrent()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"))
        assertEquals(1, repo.state.value.items.size, "the stored entry painted before the network")

        val clearing = launch { repo.clear() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(clearing.isCompleted)

        assertEquals(RecommendedState(), repo.state.value)
        assertTrue(store.cleared)
        assertTrue(store.map.isEmpty())
        assertEquals(0, store.puts, "the in-flight call's answer is not written")
        advanceUntilIdle()
        assertEquals(1, td.rpcCount("GetChatSimilarChats"), "the scan did not go on")
        assertEquals(RecommendedState(), repo.state.value)
        assertTrue(store.map.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** Measured `getChatSimilarChats` bucket: ~30 calls per ~30 s window. */
        const val BUCKET_CALLS = 30
        const val BUCKET_WINDOW_MS = 30_000L
    }
}
