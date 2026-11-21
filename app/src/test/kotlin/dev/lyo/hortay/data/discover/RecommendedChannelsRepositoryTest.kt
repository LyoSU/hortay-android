package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.FakeTdSender
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TdClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendedChannelsRepositoryTest {

    private class MemoryStore : SimilarChatsStore {
        val map = HashMap<Long, SimilarEntry>()
        var cleared = false
        override suspend fun load(): Map<Long, SimilarEntry> = HashMap(map)
        override suspend fun put(source: Long, entry: SimilarEntry) { map[source] = entry }
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
        val repo = repo(td, store, sources = listOf(-1L, -2L, -3L))
        repo.requestScan()
        advanceUntilIdle()

        assertEquals(listOf(-1L, -2L), asked, "no retry loop and nothing after the flood")
        assertEquals(setOf(-1L), store.map.keys)
        assertFalse(repo.state.value.complete)
        assertFalse(repo.state.value.scanning)
        assertEquals(listOf(-100L), repo.state.value.items.map { it.channel.chatId })

        flood = false
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
    fun `one scan spends at most the source limit and skips hidden channels`() = runTest {
        val td = FakeTdSender().apply { channels { chats() } }
        val sources = (1L..80L).map { -it }
        val repo = repo(td, MemoryStore(), sources = sources, hidden = setOf(-1L))
        repo.requestScan()
        advanceUntilIdle()

        assertEquals(RecommendedChannelsRepository.MAX_SOURCES_PER_SCAN, td.rpcCount("GetChatSimilarChats"))
        assertEquals(79, repo.state.value.total)
        assertEquals(RecommendedChannelsRepository.MAX_SOURCES_PER_SCAN, repo.state.value.scanned)
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

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
