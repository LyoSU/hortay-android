package dev.lyo.hortay.data.web

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Lifecycle of guest new-post notifications (tier 3 of [WebFeedScheduler]'s model), one
 * process-wide instance on `AppGraph`:
 *
 *  - **Scheduling.** The periodic check exists only while the app is in guest mode
 *    ([guestActive]: guest flag on, no signed-in account — the same gate tier 2 and the
 *    view reporter use) AND at least one subscribed channel has its bell on. Otherwise
 *    it is cancelled, so a reader who never turns a bell on never has background work.
 *    [wifiOnly] maps to an unmetered-network constraint.
 *  - **Leaving guest mode** (or signing in) cancels the schedule and every notification
 *    — there is no separate guest-exit hook; this is the one observer of that state.
 *    A bell turned off, or a channel unsubscribed (which clears its bell), drops that
 *    channel's notification.
 *  - **Reading.** Opening the channel ([onChannelOpened]) or reading past what was
 *    notified ([onChannelRead]) clears its notification and raises its baseline; what
 *    the foreground poller ingested counts as seen ([onForegroundSettled]), so the
 *    worker never notifies a post the reader had the chance to see in the app.
 */
class WebNotifyCoordinator(
    private val store: WebNotifyStore,
    private val notifications: WebPostNotifications,
    private val scheduling: WebNotifyScheduling,
    private val guestActive: Flow<Boolean>,
    private val wifiOnly: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    @Volatile private var bound = false

    /** Start observing; idempotent. */
    fun bind() {
        if (bound) return
        bound = true
        var previous: Set<String> = emptySet()
        combine(guestActive.distinctUntilChanged(), store.observeNotifyUsernames(), wifiOnly) { active, channels, wifi ->
            State(active, channels, wifi)
        }
            .distinctUntilChanged()
            .onEach { state ->
                apply(state, previous)
                previous = if (state.active) state.channels else emptySet()
            }
            .launchIn(scope)
    }

    private data class State(val active: Boolean, val channels: Set<String>, val wifiOnly: Boolean)

    private fun apply(state: State, previous: Set<String>) {
        runCatching {
            if (!state.active) {
                scheduling.cancel()
                notifications.cancelAll()
                return
            }
            (previous - state.channels).forEach(notifications::cancel)
            if (state.channels.isEmpty()) scheduling.cancel() else scheduling.schedule(state.wifiOnly)
        }.onFailure { Log.w(TAG, "notification scheduling failed", it) }
    }

    /** The bell on [username]'s screen was toggled. */
    suspend fun setNotify(username: String, on: Boolean) {
        store.setNotify(username.lowercase(), on)
    }

    /** The reader opened [username]: its posts are in front of them now. */
    suspend fun onChannelOpened(username: String) {
        val handle = username.lowercase()
        if (store.notifiedSeqIfOn(handle) == null) return
        store.advanceToLatest(handle)
        notifications.cancel(handle)
    }

    /**
     * The read-ack dwell advanced [username]'s read cursor to [seq]. Raises the baseline and,
     * once everything notified has been read, clears the notification.
     */
    suspend fun onChannelRead(username: String, seq: Long) {
        if (store.notifiedSeqIfOn(username) == null) return
        store.advance(username, seq)
        val shown = notifications.shown(username) ?: return
        if (seq >= shown.maxSeq) notifications.cancel(username)
    }

    /** A foreground sweep finished or the app left the foreground: what is stored counts as seen. */
    suspend fun onForegroundSettled() {
        runCatching { store.advanceAllToLatest() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "advance failed", it) }
    }

    private companion object {
        const val TAG = "WebNotify"
    }
}
