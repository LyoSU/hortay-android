package dev.lyo.hortay.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuspendUntilOrTimeoutTest {

    @Test
    fun `returns true immediately if predicate already true`() = runTest {
        val result = suspendUntilOrTimeout(timeoutMs = 1_000, pollIntervalMs = 50) { true }
        assertTrue(result)
    }

    @Test
    fun `returns true when predicate flips before timeout`() = runTest {
        var flag = false
        launch {
            delay(120)
            flag = true
        }
        val result = suspendUntilOrTimeout(timeoutMs = 1_000, pollIntervalMs = 50) { flag }
        assertTrue(result)
    }

    @Test
    fun `returns false when predicate never flips`() = runTest {
        val result = suspendUntilOrTimeout(timeoutMs = 300, pollIntervalMs = 50) { false }
        assertFalse(result)
    }

    @Test
    fun `polls at the configured interval, not faster`() = runTest {
        var calls = 0
        val result = suspendUntilOrTimeout(timeoutMs = 250, pollIntervalMs = 100) {
            calls++; false
        }
        assertFalse(result)
        // 1 synchronous fast-path check at t=0 + 3 loop checks at t=0/100/200,
        // each followed by delay(100); the delay at t=200 would advance to t=300 but
        // withTimeoutOrNull(250) cancels first. Virtual time is deterministic under
        // runTest, so the count is exact — no slack window needed.
        assertEquals(4, calls)
    }
}
