package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mode-agnostic post stream. Both [PostsRepository] (TDLib) and
 * [dev.lyo.hortay.data.web.WebFeedSource] (anonymous web mode) implement this
 * so the shared [dev.lyo.hortay.ui.timeline.TimelineScreen] +
 * [dev.lyo.hortay.ui.timeline.TimelineViewModel] can drive either backend
 * without forking. The interface is deliberately tiny:
 *
 *   - [posts] — chronologically-sorted live feed
 *   - [refresh] / [refreshIfStale] — unified pull-to-refresh + cold-start
 *     freshness check. Both are suspend fns; "if stale" returns immediately
 *     when the feed was already refreshed within the impl-defined window.
 *   - [restoreFromSnapshot] — cold-start UX hook. TDLib mode populates
 *     [posts] from a persisted top-N snapshot in <100ms before TDLib's full
 *     restore arrives; web mode is DB-backed (SQLDelight observers fire as
 *     soon as the Flow is subscribed) so it's a no-op.
 *
 * Channel-level operations (search, archive, view-receipts, openChat) are
 * intentionally NOT in this interface — they're TDLib-only and the
 * [dev.lyo.hortay.ui.timeline.TimelineScreen] composable gates them on a
 * separate optional [PostsRepository] parameter that's null in guest mode.
 * That keeps this interface sharp without dragging mode-specific surface
 * through every implementer.
 */
interface FeedSource {

    val posts: StateFlow<PersistentList<TimelinePost>>

    /**
     * Subset of [posts] limited to chats the user is actually subscribed to.
     * Used by the merged feed surface (TimelineScreen) so transient posts
     * fetched into [posts] by a single-channel drill (`loadChannelHistory`,
     * `loadHistoryAround`) — for a channel the user is NOT subscribed to —
     * don't leak into the main feed. Single-channel surfaces (ChannelScreen)
     * still consume [posts] directly with their own per-chat filter.
     *
     * Default implementation = identity; impls without a subscription model
     * (guest mode) treat every fetched chat as subscribed.
     */
    val subscribedPosts: StateFlow<PersistentList<TimelinePost>> get() = posts

    /**
     * Live, permanent deletions of whole rows, each emitted BEFORE [posts] drops those rows
     * (see [LiveDeletion]). A cosmetic hint for the dissolve effect; null for impls without
     * live deletes (guest mode), so their rows skip the effect's per-row layer entirely.
     */
    val liveDeletions: Flow<LiveDeletion>? get() = null

    /**
     * Force a fresh fetch regardless of staleness window. Pull-to-refresh
     * always uses this path. May suspend for several seconds on cold network.
     */
    suspend fun refresh()

    /**
     * Soft refresh: the impl decides whether the cached feed is fresh enough
     * to skip work. Used at ViewModel construction and on foreground resume so
     * a recent successful sweep isn't repeated.
     */
    suspend fun refreshIfStale()

    /**
     * Optional cold-start optimisation. When the impl has a fast local source
     * (TDLib's `useMessageDatabase` or web.db's SQLDelight Flow), call this to
     * surface persisted content while the network round-trip is in flight.
     * Default no-op — implementers without a snapshot source need do nothing.
     */
    suspend fun restoreFromSnapshot() {}

    /**
     * `true` once the impl's session-start sync has delivered the chat list (and with
     * it the read cursors) — TDLib mode flips it when the cold-start `LoadChats` drain
     * returns. TimelineScreen's cold-start boundary picker waits on this instead of a
     * fixed timer. Default: always settled (guest mode has no such sync phase).
     */
    val initialSyncDone: StateFlow<Boolean> get() = ALWAYS_SETTLED

    /**
     * Monotonic account-session counter: changes exactly when the impl wipes its
     * per-account state (TDLib mode: `PostsRepository.clear()` on logout), and never
     * otherwise. Long-lived consumers that hold session-derived state — the feed
     * ViewModel outlives a logout because `HomeKey` stays on the back stack — reset on
     * every change. Unlike a true → false edge on [initialSyncDone], it also fires when
     * the first sync never completed (offline start, logout mid-sync). Default: one
     * session for the whole process (guest mode has no account to sign out of).
     */
    val sessionGeneration: StateFlow<Long> get() = SINGLE_SESSION
}

private val ALWAYS_SETTLED: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

private val SINGLE_SESSION: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
