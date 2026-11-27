package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelDirectory
import dev.lyo.hortay.data.ChatRegistry
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.FakeTdSender
import dev.lyo.hortay.data.UserMessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThrottledChannelJoinerTest {

    private val floodUntil = MutableStateFlow(0L)

    private fun joiner(td: FakeTdSender) = ThrottledChannelJoiner(
        channels = ChannelDirectory(td, ChatRegistry(td)),
        channelActions = ChannelActionsRepository(
            td = td,
            userMessages = UserMessageBus(),
            connection = MutableStateFlow(ConnectionStatus.Ready),
            res = FakeStrings,
        ),
        floodWaitUntilMs = floodUntil,
        nowMs = { 1_000L },
    )

    private fun chat(id: Long) = TdApi.Chat().apply { this.id = id }

    @Test
    fun `classifies joined, not found and failed channels`() = runTest {
        val td = FakeTdSender()
        td.onNext { chat(1L) }
        td.onNext { TdApi.ChatJoinResultSuccess(1L) }
        td.onNext { TdApi.Error(400, "USERNAME_NOT_OCCUPIED") }
        td.onNext { TdApi.Error(500, "INTERNAL") }
        val joined = mutableListOf<String>()
        val result = joiner(td).joinAll(
            usernames = listOf("@One", "missing", "broken"),
            stopOnFloodWait = true,
            onJoined = { joined += it },
        )
        assertEquals(listOf("one"), result.joined)
        assertEquals(listOf("one"), joined)
        assertEquals(listOf("missing"), result.notFound)
        assertEquals(listOf("broken"), result.failed)
        assertFalse(result.floodLimited)
    }

    @Test
    fun `stops before the next channel once a flood wait is registered`() = runTest {
        val td = FakeTdSender()
        // The lookup fails with a rate limit; TdClient would push its deadline forward.
        td.onNext {
            floodUntil.value = 60_000L
            TdApi.Error(420, "FLOOD_WAIT_59")
        }
        val result = joiner(td).joinAll(listOf("first", "second", "third"), stopOnFloodWait = true)
        assertTrue(result.floodLimited)
        assertEquals(listOf("first"), result.failed)
        assertEquals(1, td.rpcCount("SearchPublicChat"))
    }

    @Test
    fun `an active flood wait stops the run before any request`() = runTest {
        floodUntil.value = 60_000L
        val td = FakeTdSender()
        val result = joiner(td).joinAll(listOf("first"), stopOnFloodWait = true)
        assertTrue(result.floodLimited)
        assertEquals(0, td.rpcCount("SearchPublicChat"))
    }
}
