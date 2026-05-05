package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.StateFlow

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
}
