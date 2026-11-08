package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the join / leave result contract the optimistic subscribe UI rolls back on:
 * a rejected RPC reports false and does NOT notify membership listeners.
 */
class ChannelActionsMembershipTest {

    private val changes = mutableListOf<Pair<Long, Boolean>>()

    private fun repo(td: FakeTdSender) = ChannelActionsRepository(
        td = td,
        userMessages = UserMessageBus(),
        connection = MutableStateFlow(ConnectionStatus.Ready),
        res = FakeStrings,
    ) { chatId, isMember -> changes += chatId to isMember }

    @Test
    fun `successful join reports true and notifies membership`() = runTest {
        val td = FakeTdSender().apply { onAny("JoinChat") { TdApi.Ok() } }
        assertTrue(repo(td).joinChat(7L))
        assertEquals(listOf(7L to true), changes)
    }

    @Test
    fun `rejected join reports false and leaves membership untouched`() = runTest {
        val td = FakeTdSender().apply { onAny("JoinChat") { TdApi.Error(400, "CHANNELS_TOO_MUCH") } }
        assertFalse(repo(td).joinChat(7L))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `rejected leave reports false`() = runTest {
        val td = FakeTdSender().apply { onAny("LeaveChat") { TdApi.Error(400, "USER_NOT_PARTICIPANT") } }
        assertFalse(repo(td).leaveChat(7L))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `successful leave notifies non-membership`() = runTest {
        val td = FakeTdSender().apply { onAny("LeaveChat") { TdApi.Ok() } }
        assertTrue(repo(td).leaveChat(7L))
        assertEquals(listOf(7L to false), changes)
    }
}
