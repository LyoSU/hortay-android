package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.web.WebFeedSource.FetchOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks in the adaptive-backoff bucket logic. The earlier implementation
 * was `if (outcomes.none { it == Fresh }) increment else reset`, which
 * meant a 429-burst (every channel returns RateLimited, none returned
 * Fresh) was indistinguishable from a quiet sweep. The scheduler then
 * doubled its interval up to 30 min — costing an hour of update lag for
 * a single transient throttle blip.
 *
 * The fix splits transient (RateLimited / NetworkError) from no-op
 * (304 / NotFound / Private / ParseFailure) and only increments on
 * all-no-op. These tests pin every interesting permutation so a future
 * refactor doesn't silently re-conflate them.
 */
class WebFeedSourceBackoffTest {

    @Test
    fun `any Fresh resets the streak`() {
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = listOf(FetchOutcome.NoOp, FetchOutcome.Fresh, FetchOutcome.NoOp),
            current = 5,
        )
        assertEquals(0, streak)
    }

    @Test
    fun `single Fresh resets even with mostly Transient sweep`() {
        // Mixed sweep where most channels were rate-limited but one returned
        // a real Page. We still want updates to surface promptly.
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = listOf(FetchOutcome.Transient, FetchOutcome.Transient, FetchOutcome.Fresh),
            current = 3,
        )
        assertEquals(0, streak)
    }

    @Test
    fun `all-Transient leaves the counter alone`() {
        // 429 burst across every subscribed channel — DON'T treat as quiet.
        // The gate in WebTelegramClient already governs the retry pace; the
        // scheduler should keep its baseline interval.
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = listOf(FetchOutcome.Transient, FetchOutcome.Transient, FetchOutcome.Transient),
            current = 2,
        )
        assertEquals(2, streak)
    }

    @Test
    fun `mixed Transient and NoOp leaves the counter alone`() {
        // If even one channel is transient, we don't know whether the silence
        // is real — it could be that the rate-limited channel had Fresh
        // content we missed. Conservative: hold the streak.
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = listOf(FetchOutcome.NoOp, FetchOutcome.Transient, FetchOutcome.NoOp),
            current = 4,
        )
        assertEquals(4, streak)
    }

    @Test
    fun `all-NoOp increments`() {
        // Every channel either returned 304 or a permanent classification
        // (NotFound / Private / ParseFailure). The feed really was quiet.
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = listOf(FetchOutcome.NoOp, FetchOutcome.NoOp, FetchOutcome.NoOp),
            current = 1,
        )
        assertEquals(2, streak)
    }

    @Test
    fun `empty outcome list increments`() {
        // Edge case: zero subscribed channels. There's no Fresh and no
        // Transient, so the all-NoOp branch wins and we tick the counter.
        // In practice doRefresh skips empty target lists earlier, but
        // pinning the function's pure-input shape here.
        val streak = WebFeedSource.nextNoOpStreak(
            outcomes = emptyList(),
            current = 0,
        )
        assertEquals(1, streak)
    }
}
