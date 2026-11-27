package dev.lyo.hortay.data.subscriptions

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [FollowedChannelsStore]: monotonic cursors, the [FOLLOWED_CHANNELS_MAX] cap, the
 * debounced cursor write, the logout race (no write of the previous account after
 * [FollowedChannelsStore.clear]) and corruption → empty list, never a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowedChannelsStoreTest {

    /** In-memory disk recording every save; [loadGate] can hold the initial load. */
    class FakeDisk(var stored: String? = null, private val loadGate: CompletableDeferred<Unit>? = null) :
        FollowedChannelsDisk {
        val saves = mutableListOf<String>()
        override suspend fun load(): String? {
            loadGate?.await()
            return stored
        }

        override suspend fun save(encoded: String) {
            saves += encoded
            stored = encoded
        }
    }

    private fun entry(chatId: Long, cursor: Long = 0L, seen: Long = 0L) = FollowedChannel(
        chatId = chatId,
        username = "c$chatId",
        title = "Channel $chatId",
        addedAtMs = 1L,
        readCursor = cursor,
        lastSeenMessageId = seen,
        status = FollowedChannel.Status.Ok,
    )

    @Test
    fun `cursor and last seen only move up`() = runTest {
        val store = FollowedChannelsStore(FakeDisk(), backgroundScope)
        store.add(entry(1L, cursor = 10L, seen = 10L))

        store.advanceReadCursor(1L, 15L)
        store.advanceReadCursor(1L, 12L)
        store.markSeen(1L, 20L)
        store.markSeen(1L, 18L)

        val e = store.channels.value.getValue(1L)
        assertEquals(15L, e.readCursor)
        assertEquals(20L, e.lastSeenMessageId)
        assertEquals(mapOf(1L to 15L), store.readCursors.value)
    }

    @Test
    fun `add refuses a duplicate and anything past the cap`() = runTest {
        val store = FollowedChannelsStore(FakeDisk(), backgroundScope)
        repeat(FOLLOWED_CHANNELS_MAX) { assertEquals(AddResult.Added, store.add(entry(it + 1L))) }

        assertEquals(AddResult.AlreadyFollowed, store.add(entry(1L)))
        assertEquals(AddResult.LimitReached, store.add(entry(10_000L)))
        assertEquals(FOLLOWED_CHANNELS_MAX, store.ids.value.size)

        store.remove(1L)
        assertEquals(AddResult.Added, store.add(entry(10_000L)))
    }

    @Test
    fun `cursor moves are coalesced into one write per window`() = runTest {
        val disk = FakeDisk()
        val store = FollowedChannelsStore(disk, backgroundScope)
        store.add(entry(1L))
        val afterAdd = disk.saves.size

        repeat(5) { store.advanceReadCursor(1L, 100L + it) }
        runCurrent()
        assertEquals(afterAdd, disk.saves.size, "nothing written before the debounce elapses")

        advanceTimeBy(CURSOR_WRITE_DEBOUNCE_MS + 1)
        assertEquals(afterAdd + 1, disk.saves.size)
        assertEquals(104L, FollowedChannelsStore.decode(disk.stored).getValue(1L).readCursor)
    }

    @Test
    fun `clear cancels a pending cursor write so the previous account never lands after it`() = runTest {
        val disk = FakeDisk()
        val store = FollowedChannelsStore(disk, backgroundScope)
        store.add(entry(1L))
        store.advanceReadCursor(1L, 55L)

        store.clear()
        val savesAtClear = disk.saves.size
        advanceTimeBy(CURSOR_WRITE_DEBOUNCE_MS * 3)

        assertEquals(savesAtClear, disk.saves.size, "the debounced write was cancelled")
        assertTrue(FollowedChannelsStore.decode(disk.stored).isEmpty())
        assertTrue(store.ids.value.isEmpty())
    }

    @Test
    fun `a load still running at logout does not install the previous account's list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val disk = FakeDisk(FollowedChannelsStore.encode(mapOf(1L to entry(1L))), gate)
        val store = FollowedChannelsStore(disk, backgroundScope)
        runCurrent()

        store.clear()
        gate.complete(Unit)
        runCurrent()

        assertTrue(store.channels.value.isEmpty())
    }

    @Test
    fun `stored list survives a restart`() = runTest {
        val disk = FakeDisk()
        val first = FollowedChannelsStore(disk, backgroundScope)
        first.add(entry(7L, cursor = 70L, seen = 71L))
        first.markStatus(7L, FollowedChannel.Status.Unavailable)

        val second = FollowedChannelsStore(disk, backgroundScope)
        second.awaitLoaded()

        val e = second.channels.value.getValue(7L)
        assertEquals(70L, e.readCursor)
        assertEquals(71L, e.lastSeenMessageId)
        assertEquals(FollowedChannel.Status.Unavailable, e.status)
    }

    @Test
    fun `unreadable stored value resets to an empty list`() = runTest {
        val store = FollowedChannelsStore(FakeDisk("{not json"), backgroundScope)
        store.awaitLoaded()

        assertTrue(store.channels.value.isEmpty())
        assertNull(FollowedChannelsStore.decode(null)[1L])
    }

    @Test
    fun `corrupted DataStore file resets to an empty list instead of crashing`(@TempDir dir: File) {
        val file = File(dir, "followed.preferences_pb").apply { writeBytes(byteArrayOf(0x0f, 0x7f, 0x00, 0x13, 0x37)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
            val disk = DataStoreFollowedChannelsDisk(dataStore)
            runBlocking {
                assertNull(disk.load())
                val store = FollowedChannelsStore(disk, scope)
                store.awaitLoaded()
                assertTrue(store.channels.value.isEmpty())
                assertEquals(AddResult.Added, store.add(entry(3L)))
                assertEquals(setOf(3L), FollowedChannelsStore.decode(disk.load()).keys)
            }
        } finally {
            scope.cancel()
        }
    }
}
