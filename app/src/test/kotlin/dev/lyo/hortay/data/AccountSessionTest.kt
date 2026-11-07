package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionTest {

    @Test
    fun `end runs hooks in registration order and survives a failing hook`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = AccountSession(parent)
        val ran = mutableListOf<String>()
        session.onEnd("a") { ran += "a" }
        session.onEnd("boom") { error("clear failed") }
        session.onEnd("b") { ran += "b" }

        session.end()

        assertEquals(listOf("a", "b"), ran)
        parent.cancel()
    }

    @Test
    fun `end cancels in-flight session work and hands out a fresh scope`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = AccountSession(parent)
        val oldScope = session.scope
        val work = oldScope.launch { awaitCancellation() }

        session.end()

        assertTrue(work.isCancelled, "work launched in the ended session must be cancelled")
        assertFalse(oldScope.isActive)
        assertTrue(session.scope.isActive, "the next session gets a live scope")
        parent.cancel()
    }

    @Test
    fun `bind ends the session on every loggedOut emission`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val session = AccountSession(parent)
        val loggedOut = MutableSharedFlow<Unit>()
        var ends = 0
        session.onEnd("count") { ends++ }
        session.bind(loggedOut)

        loggedOut.emit(Unit)
        loggedOut.emit(Unit)

        assertEquals(2, ends)
        parent.cancel()
    }
}
