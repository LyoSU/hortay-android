package dev.lyo.hortay.data

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Account-wide sliding window over the networked `GetChatHistory` calls this app sent.
 *
 * Telegram caps `getChatHistory` per account at about 30 requests per 30-second window
 * (Aliaksei Levin, tdlib/td#743; one account measured 60 — see the `BACKFILL_*` KDoc in
 * `PostsRepository.kt`). The cap is shared by every caller in the process — channel opens,
 * scroll-back, album repair, comments, the first-sign-in backfill — and going over earns a
 * FLOOD_WAIT on the whole account, which then stalls every user-driven RPC behind
 * [TdClient]'s flood gate. The limit is a COUNT per window, not a sustained rate: 30 calls
 * in 2 s are as fine as 30 calls in 29 s, and the 31st is not, however it is spaced.
 *
 * [TdClient.send] [record]s every networked `GetChatHistory` (`onlyLocal = true` never
 * reaches the server and is not counted), so the window sees all callers without per-call-site
 * bookkeeping. User-driven calls are only recorded and never wait — a tap must not queue
 * behind background work. Background callers [awaitBelow] a ceiling of their own, set below
 * the server's 30 so the window keeps headroom for the user-driven calls that may land
 * between the check and the send.
 *
 * Timebase: [SystemClock.elapsedRealtime] by default, like the flood gate — a wall-clock jump
 * (NTP sync, manual time change) must not age entries out early or freeze them for hours.
 * The clock is injectable so JVM tests can drive it from virtual time.
 */
class HistoryRequestWindow(
    private val windowMs: Long = WINDOW_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lock = Any()

    /** Send times, oldest first. Guarded by [lock]. */
    private val sentAt = ArrayDeque<Long>()

    /** Count one networked `GetChatHistory`. Thread-safe; called from any dispatcher. */
    fun record() {
        synchronized(lock) {
            val now = nowMs()
            prune(now)
            sentAt.addLast(now)
        }
    }

    /** Networked `GetChatHistory` calls sent within the last [windowMs]. */
    fun countInWindow(): Int = synchronized(lock) {
        prune(nowMs())
        sentAt.size
    }

    /**
     * Suspend until fewer than [maxInWindow] calls are in the window. Returns at once when
     * there is headroom. Otherwise sleeps until enough of the oldest entries age out, then
     * re-checks: a call recorded by someone else during the sleep must be respected, so a
     * single sleep is not enough — same loop rationale as `TdClient.awaitFloodGate`.
     * Cancellable through [delay].
     */
    suspend fun awaitBelow(maxInWindow: Int) {
        require(maxInWindow > 0) { "maxInWindow must be positive: $maxInWindow" }
        while (true) {
            val waitMs = synchronized(lock) {
                val now = nowMs()
                prune(now)
                val excess = sentAt.size - maxInWindow
                if (excess < 0) return
                // Dropping the oldest `excess + 1` entries brings the count below the
                // ceiling. The last of those, index `excess`, leaves the window once
                // `now >= its time + windowMs` (see [prune]).
                sentAt[excess] + windowMs - now
            }
            delay(waitMs.coerceAtLeast(1L))
        }
    }

    /** Drop entries that are a full window old. Caller holds [lock]. */
    private fun prune(now: Long) {
        while (sentAt.isNotEmpty() && sentAt.first() + windowMs <= now) sentAt.removeFirst()
    }

    companion object {
        /** Length of the server's `getChatHistory` budget window. */
        const val WINDOW_MS = 30_000L
    }
}
