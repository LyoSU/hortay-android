package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelActionsRepositoryTest {

    private val td = FakeTdSender()
    private val repo = ChannelActionsRepository(
        td = td,
        userMessages = UserMessageBus(),
        connection = MutableStateFlow(ConnectionStatus.Ready),
        res = FakeStrings,
    )

    @Test
    fun `toggling a paid reaction reports failure without throwing or hitting TDLib`() = runTest {
        val ok = repo.toggleReaction(chatId = 1L, messageId = 2L, kind = ReactionKind.Paid, isChosen = false)

        assertFalse(ok)
        assertEquals(0, td.rpcCount("AddMessageReaction"))
        assertEquals(0, td.rpcCount("RemoveMessageReaction"))
    }

    @Test
    fun `toggling an emoji reaction dispatches AddMessageReaction`() = runTest {
        td.onAny("AddMessageReaction") { TdApi.Ok() }

        val ok = repo.toggleReaction(chatId = 1L, messageId = 2L, kind = ReactionKind.Emoji("👍"), isChosen = false)

        assertTrue(ok)
        assertEquals(1, td.rpcCount("AddMessageReaction"))
    }
}
