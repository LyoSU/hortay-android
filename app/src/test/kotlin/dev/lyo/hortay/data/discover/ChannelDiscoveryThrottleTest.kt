package dev.lyo.hortay.data.discover

import dev.lyo.hortay.data.FakeTdSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelDiscoveryThrottleTest {

    private fun FakeTdSender.resolveStarts(starts: MutableList<Long>, now: () -> Long) {
        onAny("SearchPublicChat") {
            starts += now()
            TdApi.Error(400, "USERNAME_NOT_OCCUPIED")
        }
    }

    @Test
    fun `paced calls start at least the throttle apart`() = runTest {
        val starts = mutableListOf<Long>()
        val td = FakeTdSender().apply { resolveStarts(starts) { testScheduler.currentTime } }
        val discovery = ChannelDiscoveryRepository(td, elapsedMs = { testScheduler.currentTime }) { this }
        repeat(3) { i -> launch { discovery.resolve("channel$i") } }
        advanceUntilIdle()

        assertEquals(3, starts.size)
        starts.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= ChannelDiscoveryRepository.THROTTLE_MS, "starts $a → $b too close")
        }
    }

    @Test
    fun `a hung call does not hold up the next one`() = runTest {
        val starts = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val td = FakeTdSender().apply {
            resolveStarts(starts) { testScheduler.currentTime }
            // An offline similar-chats call that would sit out TdClient's 60 s send timeout.
            onAnySuspending("GetChatSimilarChats") {
                gate.await()
                TdApi.Chats(0, LongArray(0))
            }
        }
        val discovery = ChannelDiscoveryRepository(td, elapsedMs = { testScheduler.currentTime }) { this }
        launch { discovery.paced { td.send(TdApi.GetChatSimilarChats(-1L)) } }
        runCurrent()

        var resolvedAt = -1L
        launch {
            discovery.resolve("somechannel")
            resolvedAt = testScheduler.currentTime
        }
        advanceTimeBy(ChannelDiscoveryRepository.THROTTLE_MS + 1)
        assertEquals(ChannelDiscoveryRepository.THROTTLE_MS, resolvedAt, "the resolve waited only for the spacing")

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a clock that moves backwards waits at most the throttle`() = runTest {
        val starts = mutableListOf<Long>()
        var clock = 10_000_000L
        val td = FakeTdSender().apply { resolveStarts(starts) { testScheduler.currentTime } }
        val discovery = ChannelDiscoveryRepository(td, elapsedMs = { clock }) { this }
        discovery.resolve("first")

        clock = 0L
        val before = testScheduler.currentTime
        discovery.resolve("second")
        assertTrue(testScheduler.currentTime - before <= ChannelDiscoveryRepository.THROTTLE_MS)
        assertEquals(2, starts.size)
    }
}
