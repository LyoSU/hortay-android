package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.SharingStarted

/**
 * Multi-channel orchestrator for the anonymous web pipeline. Co-ordinates:
 *
 *   - SubscriptionsStore changes → DB sync ([WebRepository.subscribe] /
 *     [WebRepository.unsubscribe]) so the user's intent in DataStore always
 *     mirrors into the channel table that the feed query joins against.
 *   - Refresh runs that fan out [WebTelegramClient.fetchChannelPage] across
 *     every subscribed channel, write each successful result through
 *     [WebRepository.ingestPage] (one transaction per channel), and stamp
 *     per-channel status for non-success outcomes.
 *   - Single-flight refresh guarding via [Mutex] so pull-to-refresh can't
 *     overlap an ongoing tier-2 sweep.
 *   - Concurrency cap via [Semaphore] so a 200-channel sweep doesn't open
 *     200 sockets at once. The HTTP client also has a global rate-limit
 *     gate (429 / 5xx backoff) that all in-flight calls share.
 *
 * The [posts] / [channels] StateFlows are derived from [WebRepository] —
 * SQLDelight emits whenever underlying tables mutate, so callers (UI screens,
 * tests) get the merged feed without manual recomputation.
 *
 * Why the StateFlow indirection over exposing [WebRepository.observeFeed]
 * directly: callers that need a snapshot value (e.g. computing whether to
 * trigger an initial refresh) shouldn't have to set up a `stateIn` of their
 * own. Centralising it here also lets the source itself peek at the current
 * feed length to drive UX decisions like "show empty state or refreshing
 * indicator".
 */
class WebFeedSource(
    private val client: WebTelegramClient,
    private val repository: WebRepository,
    private val subscriptions: SubscriptionsStore,
    private val scope: CoroutineScope,
    private val maxConcurrentFetches: Int = DEFAULT_CONCURRENCY,
    private val stalenessWindowMs: Long = DEFAULT_STALENESS_WINDOW_MS,
    private val mediaTtlMs: Long = DEFAULT_MEDIA_TTL_MS,
) : FeedSource {

    private val refreshMutex = Mutex()
    private val fetchSemaphore = Semaphore(maxConcurrentFetches)

    /**
     * Merged feed from the DB, mapped to [TimelinePost] so the existing
     * [dev.lyo.hortay.ui.timeline.PostCard] (and its children Avatar, HeaderRow,
     * PostBody, ActionRow, ReactionChip) renders web content with no parallel
     * Composable tree. Mapping happens inside the same Flow chain so the
     * StateFlow consumer sees one already-converted list per emit, not a raw
     * WebFeedEntry list that needs per-item adaptation downstream.
     */
    override val posts: StateFlow<PersistentList<TimelinePost>> =
        repository.observeFeed()
            .stateIn(scope, SharingStarted.Eagerly, kotlinx.collections.immutable.persistentListOf())

    /** All known channels, with subscription + fetch status for UI affordances. */
    val channels: StateFlow<PersistentList<ChannelEntry>> =
        repository.observeAllChannels()
            .stateIn(scope, SharingStarted.Eagerly, kotlinx.collections.immutable.persistentListOf())

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    @Volatile
    private var lastSuccessfulRefreshAtMs: Long = 0L

    init {
        // Mirror DataStore subscription set into the channel table on every change.
        // Distinct so a no-op DataStore write (which does happen — every edit emits
        // even if the set didn't actually mutate) doesn't trigger a sync round.
        subscriptions.subscriptions
            .distinctUntilChanged()
            .onEach { handleSubscriptionsChanged(it) }
            .launchIn(scope)
    }

    private suspend fun handleSubscriptionsChanged(latest: Set<String>) {
        // Pull current DB-side subscribed set so we know what's added vs removed.
        val currentDb = repository.subscribedUsernames().toSet()
        val added = latest - currentDb
        val removed = currentDb - latest

        for (username in added) {
            repository.subscribe(username)
        }
        for (username in removed) {
            repository.unsubscribe(username)
        }

        // Newly added subscriptions deserve immediate content. Stale-window check
        // is bypassed when we have additions — the user just asked to follow this
        // channel; making them wait for the next 5-minute sweep would feel broken.
        if (added.isNotEmpty()) {
            doRefresh(force = true)
        } else if (latest.isNotEmpty() && lastSuccessfulRefreshAtMs == 0L) {
            // First run on this process with existing subs — populate immediately.
            doRefresh(force = false)
        }
    }

    /**
     * Refresh every subscribed channel. [force] bypasses the staleness window —
     * pull-to-refresh always passes true; foreground-resume passes false so a
     * second open-within-30s doesn't re-fan-out a 200-channel sweep that just
     * completed.
     *
     * Returns the launched job so callers can join() if they want to await
     * completion (test harnesses, in particular).
     */
    /**
     * FeedSource: pull-to-refresh path. Bypasses the staleness window. Suspends
     * until the sweep completes (or returns immediately when another refresh
     * already holds the mutex).
     */
    override suspend fun refresh() = doRefresh(force = true)

    /**
     * FeedSource: foreground-resume path. Returns immediately when the previous
     * sweep finished within [stalenessWindowMs].
     */
    override suspend fun refreshIfStale() = doRefresh(force = false)

    /** Backwards-compatible Job-returning entry point used by external callers. */
    fun refreshAsync(force: Boolean = false): Job = scope.launch { doRefresh(force) }

    private suspend fun doRefresh(force: Boolean) {
        if (!refreshMutex.tryLock()) {
            // Another refresh in progress — silent skip is the desired pull-to-refresh
            // semantic (UI shows the in-flight indicator already).
            return
        }
        try {
            val nowMs = System.currentTimeMillis()
            val targets = repository.subscribedUsernames()
            if (targets.isEmpty()) {
                _refreshState.value = RefreshState.Idle
                return
            }
            if (!force && nowMs - lastSuccessfulRefreshAtMs < stalenessWindowMs) {
                _refreshState.value = RefreshState.Idle
                return
            }

            val staleMediaSet = repository.channelsWithStaleMedia(nowMs - mediaTtlMs).toSet()
            _refreshState.value = RefreshState.Refreshing(targets.size)

            try {
                coroutineScope {
                    targets.map { username ->
                        async {
                            val outcome = fetchOne(
                                username = username,
                                forceNetwork = force || username in staleMediaSet,
                                fetchedAtMs = System.currentTimeMillis(),
                            )
                            username to outcome
                        }
                    }.awaitAll()
                }
                lastSuccessfulRefreshAtMs = System.currentTimeMillis()
                _refreshState.value = RefreshState.Idle
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed: ${t.message}")
                _refreshState.value = RefreshState.Error(t.message ?: "refresh failed")
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    /** Re-fetch a single channel — used after an explicit error chip tap. */
    fun retry(username: String): Job = scope.launch {
        fetchOne(username, forceNetwork = true, fetchedAtMs = System.currentTimeMillis())
    }

    /** Re-fetch the channel a post belongs to after Coil reports a stale media URL. */
    fun refetchForStalePost(postId: String): Job = scope.launch {
        repository.markMediaStale(postId)
        val username = postId.substringBefore('/')
        if (username.isNotBlank()) {
            fetchOne(username, forceNetwork = true, fetchedAtMs = System.currentTimeMillis())
        }
    }

    private suspend fun fetchOne(
        username: String,
        forceNetwork: Boolean,
        fetchedAtMs: Long,
    ) {
        repository.markFetchStatus(username, ChannelFetchStatus.Loading)
        val result = fetchSemaphore.withPermit {
            client.fetchChannelPage(username, useCache = !forceNetwork)
        }
        when (result) {
            is FetchResult.Page -> {
                repository.ingestPage(
                    page = result.page,
                    etag = result.etag,
                    lastModified = result.lastModified,
                    fetchedAtMs = fetchedAtMs,
                )
            }
            FetchResult.NotModified -> {
                repository.markNotModified(username, fetchedAtMs)
            }
            FetchResult.NotFound -> {
                repository.markFetchStatus(username, ChannelFetchStatus.NotFound, error = null)
            }
            FetchResult.PrivateChannel -> {
                repository.markFetchStatus(username, ChannelFetchStatus.Private)
            }
            is FetchResult.RateLimited -> {
                repository.markFetchStatus(
                    username = username,
                    status = ChannelFetchStatus.RateLimited,
                    retryAfterMs = result.retryAfterMs,
                )
            }
            is FetchResult.NetworkError -> {
                repository.markFetchStatus(
                    username = username,
                    status = ChannelFetchStatus.Error,
                    error = result.cause.message,
                )
            }
            FetchResult.ParseFailure -> {
                repository.markFetchStatus(
                    username = username,
                    status = ChannelFetchStatus.ParseFailure,
                    error = "parse failed",
                )
            }
        }
    }

    /**
     * Convenience "subscribe and refresh" that's the common path from the
     * Add-channel screen. Idempotent: subscribing to an already-followed channel
     * still triggers a fetch (user might be expecting fresh content) but the DB
     * upsert is a no-op.
     */
    suspend fun subscribeAndRefresh(username: String, placeholderTitle: String = username) {
        repository.subscribe(username, placeholderTitle)
        // The flow-driven sync in init will trigger a refresh once the DataStore
        // notification round-trips, but we kick a direct fetch too so UX feels
        // immediate — the DataStore sync is purely a mirror.
        scope.launch {
            fetchOne(username, forceNetwork = true, fetchedAtMs = System.currentTimeMillis())
        }
    }

    sealed interface RefreshState {
        data object Idle : RefreshState
        data class Refreshing(val channelCount: Int) : RefreshState
        data class Error(val message: String) : RefreshState
    }

    companion object {
        private const val TAG = "WebFeedSource"

        /**
         * Cap on simultaneous in-flight HTTP requests. 6 picked empirically — high
         * enough that a 200-channel sweep finishes inside a 30-second window on a
         * decent connection, low enough that we don't fingerprint as a scraper.
         * The OkHttp connection pool is sized to match (8 sockets) so we never
         * starve the limiter on TCP setup.
         */
        const val DEFAULT_CONCURRENCY = 6

        /**
         * Shortest interval between auto-refreshes triggered by foreground-resume.
         * Pull-to-refresh always bypasses this. 30 s tracks the TDLib-mode value
         * for consistency.
         */
        const val DEFAULT_STALENESS_WINDOW_MS = 30_000L

        /**
         * After this long, post media URLs are considered likely-expired (signed
         * CDN tokens rotate). Channels whose latest post is older than the TTL
         * get FORCE_NETWORK on the next sweep so we pick up fresh URLs even
         * inside the OkHttp Cache validity window.
         */
        const val DEFAULT_MEDIA_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
