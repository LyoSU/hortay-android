package dev.lyo.hortay.data.web

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground-bound polling scheduler for [WebFeedSource].
 *
 * Tier model:
 *   - Tier 1 (viewport, 60 s) — not yet implemented. Would refresh only the
 *     channels of currently visible posts; planned once we have viewport
 *     metadata flowing from [dev.lyo.hortay.ui.web.WebTimelineScreen].
 *   - Tier 2 (foreground sweep, 5 min) — IMPLEMENTED HERE. While the app is in
 *     foreground, kick a non-forced refresh every [tier2IntervalMs]. The
 *     non-forced flag lets [WebFeedSource]'s 30 s staleness window absorb any
 *     overlap with user-initiated pulls so we don't double-sweep.
 *   - Tier 3 (background WorkManager, 30 min) — not yet implemented. Will use
 *     androidx.work for unmetered + battery-not-low constraints, and a
 *     `Wi-Fi only` user toggle. Adding it doesn't change this class.
 *   - Tier 4 (manual pull / Add-channel) — handled directly by callers via
 *     [WebFeedSource.refresh] / [WebFeedSource.subscribeAndRefresh].
 *
 * Why a separate scheduler class instead of an `init { … }` in [WebFeedSource]:
 *   - Lifecycle dependency. The scheduler has to read [foreground] from
 *     [TdLifecycleBridge], which lives at the AppGraph layer. WebFeedSource is
 *     deliberately ignorant of foreground — it just runs refreshes when asked.
 *   - Testability. Stubbing a foreground StateFlow in unit tests is trivial;
 *     poking [WebFeedSource]'s init logic to do the same is not.
 *   - Future tier 3 / tier 1 additions co-locate with the scheduler, not the
 *     fetch orchestrator.
 *
 * The scheduler is process-singleton (lives on AppGraph, started once). It
 * does NOT auto-start fetches when the app is in the background — that's
 * tier 3's job, with its own constraints and Wi-Fi gate.
 */
class WebFeedScheduler(
    private val feedSource: WebFeedSource,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val tier2IntervalMs: Long = DEFAULT_TIER2_INTERVAL_MS,
) {

    private var tier2Job: Job? = null

    /**
     * Wire foreground transitions to the tier-2 loop. Idempotent: a duplicate
     * call is a no-op rather than spawning two parallel pollers (the latter
     * would double our request rate against t.me/s/, hello FLOOD_WAIT).
     */
    fun bind() {
        // StateFlow already applies distinctUntilChanged via its own conflation,
        // so we plug onEach in directly.
        foreground
            .onEach { isForeground ->
                if (isForeground) {
                    startTier2()
                } else {
                    stopTier2()
                }
            }
            .launchIn(scope)
    }

    private fun startTier2() {
        if (tier2Job?.isActive == true) return
        tier2Job = scope.launch {
            // First refresh fires immediately on resume. The staleness window
            // inside WebFeedSource means a recent pull-to-refresh won't double
            // up — the coroutine just falls through.
            feedSource.refreshAsync(force = false).join()
            while (true) {
                // Adaptive backoff: every consecutive no-op sweep doubles the
                // delay (capped at MAX_INTERVAL_MS). A reader who keeps the app
                // open all afternoon while their channels are quiet pays
                // 5min → 10 → 20 → 30 → 30 … instead of 12 sweeps an hour
                // forever. The counter resets to 0 inside WebFeedSource the
                // moment any channel returns fresh content, so the next sweep
                // after a publish snaps back to the base 5-minute cadence.
                val streak = feedSource.consecutiveNoOpSweeps.value.coerceAtMost(MAX_BACKOFF_DOUBLINGS)
                val nextDelay = (tier2IntervalMs shl streak).coerceAtMost(MAX_INTERVAL_MS)
                delay(nextDelay)
                feedSource.refreshAsync(force = false).join()
            }
        }.also {
            Log.i(TAG, "tier-2 scheduler started (${tier2IntervalMs / 1000}s base, adaptive)")
        }
    }

    private fun stopTier2() {
        tier2Job?.cancel()
        tier2Job = null
    }

    companion object {
        private const val TAG = "WebFeedScheduler"

        /**
         * 5 minutes. Picked to match the official Telegram client's cadence for
         * channel-list refresh while in foreground. A faster cadence is wasted
         * effort — channels publish every few minutes at fastest, and our
         * conditional GET means a no-op sweep costs ~200 bytes per channel
         * not the full ~30 KB body anyway.
         */
        const val DEFAULT_TIER2_INTERVAL_MS = 5 * 60 * 1000L

        // Adaptive backoff cap. After this many consecutive no-op sweeps we
        // stop doubling — a 30-minute idle interval is the worst case. Picked
        // so that a publish in the middle of a quiet stretch is observed
        // within ~30 min, well under the threshold where a user would
        // pull-to-refresh anyway. Beyond this counter the value clamps
        // (`coerceAtMost`) but doesn't overflow.
        private const val MAX_BACKOFF_DOUBLINGS = 3 // 5min × 2^3 = 40min, capped to 30min by MAX_INTERVAL_MS
        private const val MAX_INTERVAL_MS = 30 * 60 * 1000L
    }
}
