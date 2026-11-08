package dev.lyo.hortay.data

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SuspendRunCatchingTest {

    @Test
    fun `success is wrapped`() {
        assertEquals(42, suspendRunCatching { 42 }.getOrNull())
    }

    @Test
    fun `ordinary failure is captured`() {
        val result = suspendRunCatching { error("boom") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `cancellation is rethrown, not captured`() {
        assertThrows<CancellationException> {
            suspendRunCatching { throw CancellationException("cancelled") }
        }
    }
}
