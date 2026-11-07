package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPresenceTest {

    @Test
    fun `close waits for an in-flight open of the same chat`() = runTest {
        ChatPresence.clear()
        val td = FakeTdSender()
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val openGate = CompletableDeferred<Unit>()
        td.onAnySuspending("OpenChat") {
            sent += "open"
            openGate.await()
            TdApi.Ok()
        }
        td.onAny("CloseChat") {
            sent += "close"
            TdApi.Ok()
        }

        launch { ChatPresence.openChat(td, 1L) }
        runCurrent()
        launch { ChatPresence.closeChat(td, 1L) }
        runCurrent()

        assertEquals(listOf("open"), sent.toList(), "CloseChat must not overtake the pending OpenChat")

        openGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("open", "close"), sent.toList())
        assertFalse(ChatPresence.isOpen(1L))
    }

    @Test
    fun `nested opens send one OpenChat and one CloseChat`() = runTest {
        ChatPresence.clear()
        val td = FakeTdSender()
        td.onAny("OpenChat") { TdApi.Ok() }
        td.onAny("CloseChat") { TdApi.Ok() }

        ChatPresence.openChat(td, 2L)
        ChatPresence.openChat(td, 2L)
        assertTrue(ChatPresence.isOpen(2L))
        ChatPresence.closeChat(td, 2L)
        assertTrue(ChatPresence.isOpen(2L))
        ChatPresence.closeChat(td, 2L)

        assertFalse(ChatPresence.isOpen(2L))
        assertEquals(1, td.rpcCount("OpenChat"))
        assertEquals(1, td.rpcCount("CloseChat"))
    }

    @Test
    fun `a failed open rolls the refcount back`() = runTest {
        ChatPresence.clear()
        val td = FakeTdSender()
        td.onAny("OpenChat") { TdApi.Error(400, "CHAT_INVALID") }

        ChatPresence.openChat(td, 3L)

        assertFalse(ChatPresence.isOpen(3L))
    }
}
