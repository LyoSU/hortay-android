package dev.lyo.hortay.data.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks the rotation policy for the failover watchdog: pick the first untried proxy in pool
 * order, and only signal [FailoverAction.RetryCycle] (never a silent direct fallback) once every
 * proxy has failed this cycle.
 */
class ProxyFailoverTest {

    @Test
    fun `picks first untried proxy in order`() {
        val action = decideFailover(orderedProxyIds = listOf(1, 2, 3), failedIds = setOf(1))
        assertEquals(FailoverAction.SwitchTo(2), action)
    }

    @Test
    fun `skips already-failed proxies`() {
        val action = decideFailover(orderedProxyIds = listOf(1, 2, 3), failedIds = setOf(1, 2))
        assertEquals(FailoverAction.SwitchTo(3), action)
    }

    @Test
    fun `exhausted pool retries the cycle rather than going direct`() {
        val action = decideFailover(orderedProxyIds = listOf(1, 2, 3), failedIds = setOf(1, 2, 3))
        assertEquals(FailoverAction.RetryCycle, action)
    }

    @Test
    fun `empty pool retries the cycle`() {
        assertEquals(FailoverAction.RetryCycle, decideFailover(emptyList(), emptySet()))
    }

    @Test
    fun `first run with nothing failed picks the head of the pool`() {
        assertEquals(FailoverAction.SwitchTo(7), decideFailover(listOf(7, 8), emptySet()))
    }
}
