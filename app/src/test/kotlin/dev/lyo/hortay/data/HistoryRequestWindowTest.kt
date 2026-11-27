package dev.lyo.hortay.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [HistoryRequestWindow]: the count is a sliding window over the last
 * [HistoryRequestWindow.WINDOW_MS], and [HistoryRequestWindow.awaitBelow] waits exactly until
 * enough of the oldest entries age out — re-checking after the sleep so a call recorded
 * meanwhile still counts. The clock is the test scheduler's virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRequestWindowTest {

    private fun TestScope.window() = HistoryRequestWindow(nowMs = { testScheduler.currentTime })

    @Test
    fun `entries leave the count exactly one window after they were recorded`() = runTest {
        val window = window()
        window.record()
        advanceTimeBy(10_000)
        window.record()
        assertEquals(2, window.countInWindow())

        advanceTimeBy(HistoryRequestWindow.WINDOW_MS - 10_000 - 1)
        assertEquals(2, window.countInWindow(), "one ms short of the window, the first entry still counts")

        advanceTimeBy(1)
        assertEquals(1, window.countInWindow(), "a full window after it, the first entry is gone")

        advanceTimeBy(10_000)
        assertEquals(0, window.countInWindow())
    }

    @Test
    fun `awaitBelow returns at once while the window is under the ceiling`() = runTest {
        val window = window()
        repeat(2) { window.record() }
        val before = testScheduler.currentTime

        window.awaitBelow(3)

        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `awaitBelow waits until the oldest entry ages out`() = runTest {
        val window = window()
        val first = testScheduler.currentTime
        window.record()
        advanceTimeBy(5_000)
        window.record()
        advanceTimeBy(5_000)
        window.record()

        // Three in the window, ceiling three: one must leave, the oldest goes first.
        window.awaitBelow(3)

        assertEquals(first + HistoryRequestWindow.WINDOW_MS, testScheduler.currentTime)
        assertEquals(2, window.countInWindow())
    }

    @Test
    fun `awaitBelow waits for as many entries as exceed the ceiling`() = runTest {
        val window = window()
        val start = testScheduler.currentTime
        repeat(4) { i ->
            if (i > 0) advanceTimeBy(1_000)
            window.record()
        }

        // Four in the window, ceiling two: "below" means one left, so the three oldest go.
        window.awaitBelow(2)

        assertEquals(start + 2_000 + HistoryRequestWindow.WINDOW_MS, testScheduler.currentTime)
        assertEquals(1, window.countInWindow())
    }

    @Test
    fun `a call recorded during the wait extends it`() = runTest {
        val window = window()
        val start = testScheduler.currentTime
        window.record()
        window.record()

        var releasedAt = -1L
        val waiter = launch {
            window.awaitBelow(2)
            releasedAt = testScheduler.currentTime
        }
        runCurrent()
        // Mid-sleep, other callers send two more. When the first two age out the window
        // still holds two, so the waiter must sleep on until the first of the new ones goes.
        advanceTimeBy(10_000)
        window.record()
        window.record()
        advanceTimeBy(HistoryRequestWindow.WINDOW_MS - 10_000 + 1)
        assertFalse(waiter.isCompleted, "two calls recorded mid-wait must keep the window full")

        advanceTimeBy(10_000)
        assertTrue(waiter.isCompleted)
        assertEquals(start + 10_000 + HistoryRequestWindow.WINDOW_MS, releasedAt)
    }

    @Test
    fun `awaitBelow is cancellable while it waits`() = runTest {
        val window = window()
        window.record()

        val waiter = launch { window.awaitBelow(1) }
        runCurrent()
        assertFalse(waiter.isCompleted)

        waiter.cancel()
        runCurrent()

        assertTrue(waiter.isCancelled)
        assertEquals(1, window.countInWindow(), "cancelling a waiter must not touch the window")
    }
}
