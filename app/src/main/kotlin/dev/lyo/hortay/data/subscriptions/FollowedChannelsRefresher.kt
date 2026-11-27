package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.HistoryRequestWindow
import dev.lyo.hortay.data.posts.FollowedRefresh
import dev.lyo.hortay.data.suspendRunCatching
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the channels the account reads without joining fresh. Telegram streams a channel's
 * new posts only while the chat is OPEN (td_api.tl `openChat`), and only the focused card's
 * chat is opened, so for a followed channel nothing arrives on its own: this polls them, one
 * `GetChatHistory` page per check ([PostsRepository.refreshFollowedChannel]). See
 * ARCHITECTURE.md → "Read without joining".
 *
 * **When it runs.** Only while [active] is true — app in the foreground, startup out of its
 * Booting phase, account ready — and only in the session scope it is [start]ed in, which a
 * logout cancels. Leaving the foreground cancels a sweep mid-way; nothing polls in the
 * background (no WorkManager, no push).
 *
 * **Schedule.** Per channel, in memory only: the next check is due one backoff step after
 * the last, the steps being [FOLLOWED_BACKOFF_STEPS_MS] (5 → 10 → 20 → 40 → 60 min). New
 * posts reset a channel to the first step; nothing new or a failure moves it one step on.
 * When the process starts every channel is due once, oldest
 * `lastSeenMessageId` first; a channel followed later is first due one step after its seed.
 * A channel the user has open is skipped (its updates stream live).
 *
 * **Unavailable is not final.** A check answering [FollowedRefresh.Unavailable] (400 / 403,
 * or the chat object missing from TDLib's store — e.g. after a cache clear) first gets ONE
 * [rehydrate]: `SearchPublicChat` on the stored handle. If that resolves to the same chat,
 * still a non-member channel, the single-page fetch is retried once. Only otherwise is the
 * channel marked unavailable; it then leaves the backoff schedule but is re-checked on
 * [requestRefreshNow] and once per [FOLLOWED_UNAVAILABLE_RECHECK_MS] (foreground only, like
 * everything here), and a check that works flips it back to Ok at the first step.
 * `SearchPublicChat` is a throttled `contacts.resolveUsername` on the server (about one per
 * 3 s), so consecutive rehydrates are spaced [FOLLOWED_REHYDRATE_SPACING_MS] apart.
 *
 * **Budget.** Before every check the loop waits on the account-wide [historyWindow] until
 * fewer than [FOLLOWED_WINDOW_CEILING] networked `GetChatHistory` calls are in it. MTProto
 * guidance is to short-poll at most ~10 channels; with Telegram's ~30 per 30 s cap shared
 * by every caller, 10 leaves ~20 for the user's own channel opens, scroll-back and album
 * repairs. A FLOOD_WAIT stops the sweep: [dev.lyo.hortay.data.TdClient]'s flood gate holds
 * every send for the penalty anyway, and the loop resumes after [FOLLOWED_FLOOD_PAUSE_MS].
 *
 * [requestRefreshNow] (pull-to-refresh, via [refreshRequests]) makes every channel due at
 * once; coming back to the foreground makes due the channels last checked at least one
 * first step ago. Neither waits for the sweep.
 */
class FollowedChannelsRefresher(
    private val store: FollowedChannelsStore,
    private val refresh: suspend (chatId: Long, sinceMessageId: Long) -> FollowedRefresh,
    private val historyWindow: HistoryRequestWindow,
    private val active: Flow<Boolean>,
    private val refreshRequests: Flow<Unit>,
    private val isChatOpen: suspend (chatId: Long) -> Boolean,
    private val nowMs: () -> Long,
    private val rehydrate: suspend (chatId: Long, username: String) -> Boolean = { _, _ -> false },
) {
    private val forceAll = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val startLock = Any()

    /** The current session's loop; guarded by [startLock]. */
    private var job: Job? = null

    /** When the last [rehydrate] started; touched only by the loop coroutine. */
    private var lastRehydrateAt = NEVER_CHECKED

    /** Make every followed channel — unavailable ones included — due now and wake the loop. Non-blocking. */
    fun requestRefreshNow() {
        forceAll.set(true)
        wake.trySend(Unit)
    }

    /** Launch the loop in [scope] (the session scope) unless one is still running. */
    fun start(scope: CoroutineScope) {
        synchronized(startLock) {
            if (job?.isActive == true) return
            job = scope.launch { run() }
        }
    }

    /** The loop itself; returns only by cancellation. Exposed to tests on virtual time. */
    internal suspend fun run() = coroutineScope {
        store.awaitLoaded()
        val schedule = Schedule(nowMs())
        // A stale request from a previous session must not force the first sweep.
        forceAll.set(false)
        launch { refreshRequests.collect { requestRefreshNow() } }
        // Re-plan when channels are followed or dropped (a new one gets its first slot).
        launch { store.ids.drop(1).collect { wake.trySend(Unit) } }
        var wasActive = false
        var resumePending = false
        active.distinctUntilChanged().collectLatest { on ->
            if (!on) {
                if (wasActive) resumePending = true
                return@collectLatest
            }
            wasActive = true
            if (resumePending) {
                resumePending = false
                schedule.sync(store.channels.value.values)
                schedule.markStaleDue(nowMs(), FOLLOWED_BACKOFF_STEPS_MS.first())
            }
            sweepLoop(schedule)
        }
    }

    private suspend fun sweepLoop(schedule: Schedule) {
        while (true) {
            if (forceAll.getAndSet(false)) {
                schedule.sync(store.channels.value.values)
                schedule.markAllDue(nowMs())
            }
            schedule.sync(store.channels.value.values)
            val due = schedule.due(nowMs(), store.channels.value)
            if (due.isEmpty()) {
                val waitMs = schedule.nextDueIn(nowMs()) ?: Long.MAX_VALUE
                withTimeoutOrNull(waitMs.coerceAtLeast(1L)) { wake.receive() }
                continue
            }
            if (!sweep(due, schedule)) {
                withTimeoutOrNull(FOLLOWED_FLOOD_PAUSE_MS) { wake.receive() }
            }
        }
    }

    /** Check [due] in order; false when a FLOOD_WAIT stopped the sweep. */
    private suspend fun sweep(due: List<Long>, schedule: Schedule): Boolean {
        for (chatId in due) {
            if (!check(chatId, schedule)) return false
        }
        return true
    }

    /** One channel's check and schedule update; false only on FLOOD_WAIT. */
    private suspend fun check(chatId: Long, schedule: Schedule): Boolean {
        val entry = store.channels.value[chatId]
        val slot = schedule[chatId]
        if (entry == null || slot == null) return true
        if (isChatOpen(chatId)) {
            slot.dueAt = nowMs() + slot.interval()
            return true
        }
        var result = fetch(entry)
        if (result == FollowedRefresh.Unavailable && rehydrateOnce(entry)) result = fetch(entry)
        val at = nowMs()
        slot.lastCheckedAt = at
        when (result) {
            is FollowedRefresh.NewPosts -> {
                // Never seeded (the follow's seed failed): the history enters as read.
                if (entry.readCursor <= 0L) store.advanceReadCursor(chatId, result.newestId)
                store.markSeen(chatId, result.newestId)
                slot.recovered(entry)
                slot.step = 0
                slot.dueAt = at + FOLLOWED_BACKOFF_STEPS_MS[0]
            }
            FollowedRefresh.NoChange -> {
                // A channel that just came back starts the backoff from the first step.
                if (slot.recovered(entry)) slot.dueAt = at + FOLLOWED_BACKOFF_STEPS_MS[0] else slot.backOff(at)
            }
            FollowedRefresh.Failed -> slot.backOff(at)
            FollowedRefresh.Unavailable -> {
                store.markStatus(chatId, FollowedChannel.Status.Unavailable)
                slot.unavailable = true
                slot.step = 0
                slot.dueAt = at + FOLLOWED_UNAVAILABLE_RECHECK_MS
            }
            FollowedRefresh.FloodWait -> return false
        }
        return true
    }

    /** One budgeted page fetch: waits for room in the shared window first. */
    private suspend fun fetch(entry: FollowedChannel): FollowedRefresh {
        historyWindow.awaitBelow(FOLLOWED_WINDOW_CEILING)
        return suspendRunCatching { refresh(entry.chatId, entry.lastSeenMessageId) }
            .warnUnlessCancelled(TAG, "refresh(${entry.chatId})")
            .getOrDefault(FollowedRefresh.Failed)
    }

    /** The single [rehydrate] attempt of a check, spaced from the previous one. */
    private suspend fun rehydrateOnce(entry: FollowedChannel): Boolean {
        val username = entry.username?.takeIf { it.isNotBlank() } ?: return false
        val waitMs = lastRehydrateAt + FOLLOWED_REHYDRATE_SPACING_MS - nowMs()
        if (waitMs > 0) delay(waitMs)
        lastRehydrateAt = nowMs()
        return suspendRunCatching { rehydrate(entry.chatId, username) }
            .warnUnlessCancelled(TAG, "rehydrate(${entry.chatId})")
            .getOrDefault(false)
    }

    /**
     * A check that worked: an unavailable channel is Ok again and back on the backoff
     * schedule at the first step. True when the channel was unavailable until now.
     */
    private suspend fun Slot.recovered(entry: FollowedChannel): Boolean {
        val wasUnavailable = unavailable || entry.status != FollowedChannel.Status.Ok
        if (entry.status != FollowedChannel.Status.Ok) store.markStatus(entry.chatId, FollowedChannel.Status.Ok)
        unavailable = false
        if (wasUnavailable) step = 0
        return wasUnavailable
    }

    /**
     * Slot state. [unavailable] mirrors the entry's status as this loop last saw it: such a
     * slot is due only on its slow re-check (or a forced sweep), and a failed re-check keeps
     * it there instead of moving it through the backoff steps.
     */
    private class Slot(var dueAt: Long, var step: Int, var lastCheckedAt: Long, var unavailable: Boolean) {
        fun interval(): Long = if (unavailable) FOLLOWED_UNAVAILABLE_RECHECK_MS else FOLLOWED_BACKOFF_STEPS_MS[step]

        fun backOff(at: Long) {
            if (!unavailable) step = (step + 1).coerceAtMost(FOLLOWED_BACKOFF_STEPS_MS.lastIndex)
            dueAt = at + interval()
        }
    }

    /** Per-channel next-check times. Touched only by the loop coroutine. */
    private inner class Schedule(private val startedAt: Long) {
        private val slots = HashMap<Long, Slot>()
        private var seeded = false

        operator fun get(chatId: Long): Slot? = slots[chatId]

        /**
         * Add slots for new channels and drop the unfollowed ones. An unavailable channel
         * gets a slow re-check slot; one that is Ok again outside this loop (unfollowed and
         * followed anew between two passes) goes back to the first backoff step.
         */
        fun sync(entries: Collection<FollowedChannel>) {
            slots.keys.retainAll(entries.mapTo(HashSet()) { it.chatId })
            val now = nowMs()
            for (entry in entries) {
                val unavailable = entry.status != FollowedChannel.Status.Ok
                val existing = slots[entry.chatId]
                if (existing != null) {
                    if (existing.unavailable && !unavailable) {
                        existing.unavailable = false
                        existing.step = 0
                        existing.dueAt = now + FOLLOWED_BACKOFF_STEPS_MS[0]
                    }
                    continue
                }
                // Present when the loop started → due once now; followed later → it was just
                // seeded, so its first check waits one step. Unavailable → the slow re-check.
                val base = if (seeded) now else startedAt
                val dueAt = when {
                    unavailable -> base + FOLLOWED_UNAVAILABLE_RECHECK_MS
                    seeded -> now + FOLLOWED_BACKOFF_STEPS_MS[0]
                    else -> startedAt
                }
                slots[entry.chatId] = Slot(dueAt = dueAt, step = 0, lastCheckedAt = NEVER_CHECKED, unavailable = unavailable)
            }
            seeded = true
        }

        fun markAllDue(now: Long) {
            for (slot in slots.values) slot.dueAt = now
        }

        fun markStaleDue(now: Long, minAgeMs: Long) {
            for (slot in slots.values) {
                if (!slot.unavailable && now - slot.lastCheckedAt >= minAgeMs) slot.dueAt = minOf(slot.dueAt, now)
            }
        }

        /** Due channels, earliest first; ties (the cold-start batch) oldest last-seen first. */
        fun due(now: Long, entries: Map<Long, FollowedChannel>): List<Long> =
            slots.entries.asSequence()
                .filter { it.value.dueAt <= now }
                .sortedWith(
                    compareBy<Map.Entry<Long, Slot>> { it.value.dueAt }
                        .thenBy { entries[it.key]?.lastSeenMessageId ?: 0L }
                        .thenBy { it.key },
                )
                .map { it.key }
                .toList()

        fun nextDueIn(now: Long): Long? = slots.values.minOfOrNull { it.dueAt }?.let { it - now }
    }

    private companion object {
        const val TAG = "FollowedRefresher"
        const val NEVER_CHECKED = Long.MIN_VALUE / 2
    }
}

/**
 * Backoff steps between two checks of one followed channel: 5, 10, 20, 40, then every 60
 * minutes. A channel that keeps posting stays on 5; a quiet one settles at an hourly check,
 * so 100 mostly-quiet channels cost ~100 calls an hour, far under the account's budget. The
 * first step also bounds how stale a busy followed channel gets while the app is open.
 */
val FOLLOWED_BACKOFF_STEPS_MS: List<Long> = listOf(5L, 10L, 20L, 40L, 60L).map { it * 60_000L }

/**
 * Most networked `GetChatHistory` calls (all callers, [HistoryRequestWindow]) the account may
 * have in the current window before the refresher sends its next one. ~20 of Telegram's ~30
 * per 30 s stay free for user-driven calls, which never wait; ≤ 10 also matches the MTProto
 * guidance to short-poll at most about ten channels.
 */
const val FOLLOWED_WINDOW_CEILING = 10

/** Pause after a FLOOD_WAIT stopped a sweep, before the loop looks at its schedule again. */
const val FOLLOWED_FLOOD_PAUSE_MS = 60_000L

/**
 * How often a channel marked unavailable is re-checked without a pull-to-refresh: once a
 * day. Rare enough that 100 dead entries cost nothing, often enough that a channel that
 * went private for a while, or a chat TDLib lost after a cache clear, comes back on its own.
 */
const val FOLLOWED_UNAVAILABLE_RECHECK_MS = 24L * 60 * 60_000L

/**
 * Minimum gap between two handle re-resolves (`SearchPublicChat` → server-side
 * `contacts.resolveUsername`), which Telegram throttles at about one per 3 s per account.
 */
const val FOLLOWED_REHYDRATE_SPACING_MS = 3_000L
