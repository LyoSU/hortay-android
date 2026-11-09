package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.SharingStarted
import java.util.concurrent.ConcurrentHashMap

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
    /**
     * Same cross-mode hidden-channels store wired into TDLib's PostsRepository.
     * Optional so existing tests that construct a WebFeedSource without a
     * DataStore still compile and behave identically (filter is a no-op).
     * See [IgnoredChannelsStore] KDoc for the rationale on a single Long-keyed
     * store across both modes.
     */
    private val ignoredChannels: IgnoredChannelsStore? = null,
    private val maxConcurrentFetches: Int = DEFAULT_CONCURRENCY,
    private val stalenessWindowMs: Long = DEFAULT_STALENESS_WINDOW_MS,
    private val mediaTtlMs: Long = DEFAULT_MEDIA_TTL_MS,
    /**
     * Archive capture hook. Optional (default null) so existing tests and guest-
     * mode without archive enabled don't require the dependency. When present,
     * [fetchOne] captures edits (changed text or media filenames) and deletions
     * (posts that vanished within the 30-minute CDN age-out window) into the
     * archive DB before [WebRepository.ingestPage] overwrites them.
     */
    private val archiveRepository: dev.lyo.hortay.data.archive.ArchiveRepository? = null,
) : FeedSource {

    private val webPostDiff = WebPostDiff()

    private val refreshMutex = Mutex()
    private val fetchSemaphore = Semaphore(maxConcurrentFetches)

    // Dedup map for UI-initiated retries: two fast taps + a background sweep
    // could otherwise launch 3 concurrent fetches for the same channel. The
    // tier-2 sweep (doRefresh) does not yet register its per-username jobs
    // here — refreshMutex single-flights the whole sweep, so any in-flight
    // doRefresh is already mutually-exclusive with other sweeps, and racing
    // a retry against an in-flight sweep is benign (both write the same
    // channel page; SQLDelight upserts are idempotent). If/when the sweep
    // gains per-username concurrency it should register its jobs here too.
    private val inFlightRetries = ConcurrentHashMap<String, Job>()

    // Cooldowns for the stale-media re-fetch path (see [refetchForStalePost] /
    // [refetchForStaleMediaUrl]). Keyed by channel username and by URL respectively
    // so a screen of expired images doesn't storm t.me. Entries are tiny and only
    // added on the rare event of an actual image-load failure, so unbounded growth
    // over a session is a non-issue in practice.
    private val staleRefetchCooldown = ConcurrentHashMap<String, Long>()
    private val staleUrlCooldown = ConcurrentHashMap<String, Long>()
    // (username, top seq) → launch time; guarded by synchronized(itself). See
    // [claimOlderPageRefetch].
    private val staleOlderPageCooldown = HashMap<Pair<String, Long>, Long>()

    // Last-known DataStore subscription set, used for delta computation in
    // [handleSubscriptionsChanged]. We deliberately diff against THIS rather
    // than against `repository.subscribedUsernames()` because direct-write paths
    // ([subscribeAndRefresh] writes to the channel table immediately) would
    // otherwise corrupt the diff (a freshly-direct-subscribed channel would
    // look like "should be removed" when the DataStore Flow finally emits the
    // pre-write value). Tracking DataStore-only deltas keeps direct-channel
    // writes out of the diff entirely. Initialised lazily on first emit.
    @Volatile
    private var lastKnownSubscriptionsSet: Set<String>? = null
    private val subscriptionsMutex = Mutex()

    /**
     * Merged feed from the DB, mapped to [TimelinePost] so the existing
     * [dev.lyo.hortay.ui.timeline.PostCard] (and its children Avatar, HeaderRow,
     * PostBody, ActionRow, ReactionChip) renders web content with no parallel
     * Composable tree. Mapping happens inside the same Flow chain so the
     * StateFlow consumer sees one already-converted list per emit, not a raw
     * WebFeedEntry list that needs per-item adaptation downstream.
     */
    override val posts: StateFlow<PersistentList<TimelinePost>> =
        combine(
            // Bookmarked posts are merged in so the Saved tab (which renders this
            // same source, filtered to bookmark keys) keeps every saved post even
            // after it ages out of the feed's newest-N window or its channel is
            // unsubscribed. The Saved tab must share this source rather than get
            // its own: TimelineViewModel is keyed by FeedSource class, and a
            // separate VM would classify a freshly bookmarked recent post as
            // "pending new" and hide it.
            combine(repository.observeFeed(), repository.observeBookmarked()) { feed, saved ->
                mergeSaved(feed, saved)
            },
            // Read-side filter for hidden channels. The merged guest feed is
            // capped at 1000 rows so the additional `filter` is trivial; the
            // alternative — pushing exclusion into the SQL `selectFeed` query —
            // would force a SQLDelight migration (`channel.is_ignored INTEGER`)
            // for a feature whose primary cost is a tiny in-memory set test.
            // chatId comparison (not username) so the same hidden set works
            // unchanged across TDLib + guest modes (see [IgnoredChannelsStore]
            // for the cross-mode chatId design).
            ignoredChannels?.ignored ?: flowOf(persistentSetOf()),
        ) { feed, ignored ->
            if (ignored.isEmpty()) feed
            else feed.filter { it.chatId !in ignored }.toPersistentList()
        }
            // WhileSubscribed(5s) instead of Eagerly so authenticated users
            // who never enter guest mode don't pay for the SQL query at
            // AppGraph construction time. 5s grace matches the rest of the
            // TDLib-mode SharedFlow / StateFlow lifecycle (CommentsRepository,
            // PostsRepository) so a brief tab-switch doesn't tear down and
            // re-establish the SQLDelight subscription.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), kotlinx.collections.immutable.persistentListOf())

    /** All known channels, with subscription + fetch status for UI affordances. */
    val channels: StateFlow<PersistentList<ChannelEntry>> =
        repository.observeAllChannels()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), kotlinx.collections.immutable.persistentListOf())

    /**
     * Per-channel read cursors mirrored from the local `channel_read_cursor`
     * table. Direct parallel to [dev.lyo.hortay.data.posts.PostsRepository.chatReadCursors]
     * — drives the [dev.lyo.hortay.ui.timeline.UnreadStrip] on PostCard in guest
     * mode by feeding [dev.lyo.hortay.ui.timeline.LocalReadCursors]. Cursors are
     * advanced through [WebRepository.markChannelRead] from the same 1-second
     * dwell collector that TDLib mode uses, so the two modes feel identical at
     * the read-state layer.
     */
    val chatReadCursors: StateFlow<dev.lyo.hortay.data.ReadCursors> =
        repository.observeReadCursors()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), dev.lyo.hortay.data.EmptyReadCursors)

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    @Volatile
    private var lastSuccessfulRefreshAtMs: Long = 0L

    /**
     * Set once [syncBookmarks] has mirrored the bookmark set into
     * `post.is_bookmarked` in this process. [maybeVacuum] waits for it: vacuum
     * keeps only flagged rows, so running it before the first mirror (e.g. the
     * first launch after upgrading) would delete saved posts outright.
     */
    @Volatile
    private var bookmarksSynced = false

    /**
     * Mirror the cross-mode bookmark key set into web.db (see
     * [WebRepository.syncBookmarks]). Driven by the guest scaffold, which
     * collects [dev.lyo.hortay.data.BookmarkStore.bookmarks] — every toggle site
     * (feed, Saved, channel, search, post detail) writes that one store, so one
     * collector covers them all, and its first emission is the backfill.
     */
    suspend fun syncBookmarks(keys: Set<String>) {
        repository.syncBookmarks(keys)
        bookmarksSynced = true
    }

    /** Wall-clock of the last [WebRepository.vacuum]; process-local, so the first good sweep of a launch runs it. */
    @Volatile
    private var lastVacuumAtMs: Long = 0L

    // Adaptive backoff signal for [WebFeedScheduler]. Incremented on each
    // sweep that ingested NO new posts (every channel returned 304 NotModified
    // or the same seq it already had). Reset to 0 when a sweep brings new
    // content or the user explicitly forces a refresh. Scheduler uses this to
    // double its interval — a feed where nothing's been published in an hour
    // shouldn't pay the radio cost of a full sweep every 5 minutes.
    private val _consecutiveNoOpSweeps = MutableStateFlow(0)
    val consecutiveNoOpSweeps: StateFlow<Int> = _consecutiveNoOpSweeps.asStateFlow()

    /**
     * Guest-mode activity signal, attached by [WebFeedScheduler.bind] (the one
     * place that already knows foreground + auth + guest flag). Gates the
     * self-initiated first-emit sweep in [handleSubscriptionsChanged]: a
     * signed-in user with leftover guest subscriptions in DataStore must not hit
     * t.me on every cold start. Null (not yet attached) reads as "inactive" —
     * the scheduler's own first iteration covers the sweep once it binds.
     */
    @Volatile
    private var autoRefreshGate: Flow<Boolean>? = null

    internal fun attachAutoRefreshGate(gate: Flow<Boolean>) {
        autoRefreshGate = gate
    }

    init {
        // Mirror DataStore subscription set into the channel table on every change.
        // Distinct so a no-op DataStore write (which does happen — every edit emits
        // even if the set didn't actually mutate) doesn't trigger a sync round.
        subscriptions.subscriptions
            .distinctUntilChanged()
            .onEach { handleSubscriptionsChanged(it) }
            .launchIn(scope)
        // Recover from any 'loading' status rows left behind by a prior process
        // kill mid-fetch. fetchOne stamps Loading on entry and clears it on exit;
        // without this sweep, a row that crashed mid-fetch sticks at Loading and
        // surfaces a permanent spinner in the Channels tab.
        scope.launch { repository.clearStaleLoading() }
    }

    private suspend fun handleSubscriptionsChanged(latest: Set<String>) {
        // The DB diff is applied under [subscriptionsMutex]; the network refresh it
        // may call for is launched AFTER the lock is released. Awaiting a
        // 200-channel sweep inline used to hold the mutex (and stall this
        // collector) for the whole sweep, so an unsubscribe tapped meanwhile only
        // reached the channel table after the sweep — or never, if the process
        // died first.
        val refresh: Boolean? = subscriptionsMutex.withLock {
            // Diff against the LAST DataStore value we saw, not against the channel
            // table — see field doc on [lastKnownSubscriptionsSet]. First emit is
            // a special case: previous=null means "we have nothing to compare
            // against yet", so the table is reconciled against DataStore instead.
            val previous = lastKnownSubscriptionsSet
            lastKnownSubscriptionsSet = latest
            if (previous == null) {
                // First emit on this process: two-way reconcile. DataStore is the
                // source of truth for intent — every subscribe path writes it
                // ([subscribeAndRefresh]), every unsubscribe path removes from it
                // ([unsubscribe], the migration coordinator). A row subscribed in
                // the table but absent from DataStore is an unsubscribe whose table
                // write was lost (process death between the two writes); a name in
                // DataStore missing from the table is the mirror-image add.
                val currentDb = repository.subscribedUsernames().toSet()
                for (username in latest - currentDb) {
                    repository.subscribe(username)
                }
                for (username in currentDb - latest) {
                    repository.unsubscribe(username)
                }
                // Populate a stale empty feed if we have any subs — non-forced, and
                // only while guest mode is actually active (see [autoRefreshGate]).
                return@withLock if (latest.isNotEmpty() && lastSuccessfulRefreshAtMs == 0L) false else null
            }
            val added = latest - previous
            val removed = previous - latest
            for (username in added) {
                repository.subscribe(username)
            }
            for (username in removed) {
                repository.unsubscribe(username)
            }
            if (added.isNotEmpty()) true else null
        }
        when (refresh) {
            true -> scope.launch { doRefresh(force = true) }
            false -> scope.launch {
                if (autoRefreshGate?.first() == true) doRefresh(force = false)
            }
            null -> Unit
        }
    }

    /**
     * Unsubscribe path for the Channels tab. Mirrors [subscribeAndRefresh]: the
     * channel table flips immediately (feed JOIN drops the channel's posts within
     * one emit) instead of waiting for the DataStore round-trip, and
     * [lastKnownSubscriptionsSet] is pre-updated so the imminent DataStore emit is
     * a no-op delta.
     */
    suspend fun unsubscribe(username: String) = withContext(NonCancellable) {
        // NonCancellable: called from a composition scope; leaving the screen
        // between the table write and the DataStore write must not split them.
        val normalized = username.lowercase()
        repository.unsubscribe(normalized)
        subscriptionsMutex.withLock {
            val previous = lastKnownSubscriptionsSet
            if (previous != null) {
                lastKnownSubscriptionsSet = previous - normalized
            }
        }
        subscriptions.remove(normalized)
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
                val outcomes = coroutineScope {
                    targets.map { username ->
                        async {
                            val mediaStale = username in staleMediaSet
                            fetchOne(
                                username = username,
                                forceNetwork = force || mediaStale,
                                fetchedAtMs = System.currentTimeMillis(),
                                // Only bypass the ingest fingerprint guard for channels
                                // whose media aged past the TTL — NOT for a blanket
                                // pull-to-refresh (force), which would erode the guard's
                                // ~80% skip rate across every post. A plateaued post whose
                                // media rotated between plain refreshes still self-heals
                                // reactively via [refetchForStalePost].
                                forceMediaRefresh = mediaStale,
                            )
                        }
                    }.awaitAll()
                }
                _consecutiveNoOpSweeps.value = nextNoOpStreak(outcomes, _consecutiveNoOpSweeps.value)
                lastSuccessfulRefreshAtMs = System.currentTimeMillis()
                _refreshState.value = RefreshState.Idle
                maybeVacuum()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "refresh failed: ${t.message}")
                _refreshState.value = RefreshState.Error(t.message ?: "refresh failed")
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    /** Re-fetch a single channel — used after an explicit error chip tap. */
    fun retry(username: String): Job {
        val key = username.lowercase()
        // Dedup: if a retry for this username is still in flight, hand back
        // the existing Job. Prevents two fast taps from launching parallel
        // fetches that race on the same channel's status / media writes.
        inFlightRetries[key]?.let { existing ->
            if (!existing.isCompleted) return existing
        }
        val job = scope.launch {
            fetchOne(key, forceNetwork = true, fetchedAtMs = System.currentTimeMillis())
        }
        inFlightRetries[key] = job
        job.invokeOnCompletion { inFlightRetries.remove(key, job) }
        return job
    }

    /**
     * Re-fetch the channel a post belongs to after Coil reports an expired media
     * URL (HTTP 401/403/410). Marks the specific post's media stale (zeroes its
     * `fetched_at_ms`) and forces a fresh channel fetch that re-ingests rotated CDN
     * URLs. Coalesced per channel via [staleRefetchCooldown]: a viewport full of one
     * channel's expired images fires ONE re-fetch, not one per card — the channel's
     * post tokens rotate together, so the channel is the right dedup key. A post
     * older than the channel's newest page is repaired from its own `?before=`
     * page instead, under a per-page cooldown ([claimOlderPageRefetch]). Returns
     * null only for a malformed id; the launched job itself does nothing while the
     * relevant cooldown is active.
     */
    fun refetchForStalePost(postId: String): Job? {
        val username = postId.substringBefore('/')
        if (username.isBlank()) return null
        val seq = postId.substringAfterLast('/').toLongOrNull()
        return scope.launch {
            // A post older than the channel's newest page is NOT on page 1, so
            // re-downloading page 1 would never deliver its fresh URL — the image
            // stayed broken and re-triggered this path every cooldown. Fetch the
            // page that actually holds it (`?before=<seq+1>`) instead.
            val newestPageFloor = repository.newestPageFloorSeq(username)
            if (seq != null && newestPageFloor != null && seq < newestPageFloor) {
                if (!claimOlderPageRefetch(username, seq)) return@launch
                repository.markMediaStale(postId)
                refetchOlderPage(username, seq)
                return@launch
            }
            val now = System.currentTimeMillis()
            val previous = staleRefetchCooldown[username]
            if (previous != null && now - previous < STALE_REFETCH_COOLDOWN_MS) return@launch
            // Atomic claim: concurrent reports for one channel race here, only one wins.
            val claimed = if (previous == null) {
                staleRefetchCooldown.putIfAbsent(username, now) == null
            } else {
                staleRefetchCooldown.replace(username, previous, now)
            }
            if (!claimed) return@launch
            repository.markMediaStale(postId)
            // forceMediaRefresh so the WHOLE channel's rotated URLs are re-ingested,
            // not just the one post markMediaStale flagged — its siblings' images are
            // expiring in lockstep and the per-channel cooldown blocks a second pass.
            fetchOne(
                username = username,
                forceNetwork = true,
                fetchedAtMs = System.currentTimeMillis(),
                forceMediaRefresh = true,
            )
        }
    }

    /**
     * Cooldown for older-page refetches. One `?before=<seq+1>` page holds up to
     * [OLDER_PAGE_SPAN] posts at or below `seq`, so a claim covers that seq range:
     * a viewport of expired images from the same older page fires one fetch, while
     * an expired post on a different older page still gets its own.
     */
    private fun claimOlderPageRefetch(username: String, seq: Long): Boolean = synchronized(staleOlderPageCooldown) {
        val now = System.currentTimeMillis()
        staleOlderPageCooldown.entries.removeAll { now - it.value >= STALE_REFETCH_COOLDOWN_MS }
        val covered = staleOlderPageCooldown.keys.any { (user, top) ->
            user == username && seq <= top && seq > top - OLDER_PAGE_SPAN
        }
        if (covered) return false
        staleOlderPageCooldown[username to seq] = now
        true
    }

    /**
     * Re-fetch the older page holding [seq] and rewrite only the posts already
     * stored (see [WebRepository.refreshExistingPosts]). Status handling is
     * deliberately silent: the channel's row describes its newest page, and a
     * failed media repair must not paint an error chip over a healthy channel.
     */
    private suspend fun refetchOlderPage(username: String, seq: Long) {
        try {
            val result = fetchSemaphore.withPermit {
                client.fetchChannelPage(username, before = (seq + 1).toString(), useCache = false)
            }
            if (result is FetchResult.Page) {
                repository.refreshExistingPosts(result.page.posts, System.currentTimeMillis())
            } else {
                Log.w(TAG, "older-page media refetch for $username before ${seq + 1}: $result")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "older-page media refetch failed: ${t.message}")
        }
    }

    /**
     * Entry point for the UI image-error path: [dev.lyo.hortay.ui.media.TdMediaImage]
     * only knows the failed remote URL, not the post id, so we resolve the URL back
     * to its post ([WebRepository.postIdForMediaUrl]) before delegating to
     * [refetchForStalePost]. Deduped per URL so the same broken image re-mounting /
     * retrying doesn't launch repeat DB lookups; the channel-level cooldown in
     * [refetchForStalePost] then bounds the actual network re-fetches. Returns null
     * when the URL was reported again within the cooldown; otherwise the lookup job
     * (which itself may resolve to no work if the URL matches no stored post).
     */
    fun refetchForStaleMediaUrl(remoteUrl: String): Job? {
        if (remoteUrl.isBlank()) return null
        val now = System.currentTimeMillis()
        val previous = staleUrlCooldown[remoteUrl]
        if (previous != null && now - previous < STALE_REFETCH_COOLDOWN_MS) return null
        staleUrlCooldown[remoteUrl] = now
        return scope.launch {
            val postId = repository.postIdForMediaUrl(remoteUrl) ?: return@launch
            refetchForStalePost(postId)
        }
    }

    /**
     * @return [FetchOutcome] describing the sweep's contribution. The caller
     *   ([doRefresh]) aggregates these to drive the adaptive backoff counter
     *   exposed via [consecutiveNoOpSweeps].
     *
     * The Loading status set on entry is cleared by every branch of `when` —
     * AND additionally by the outer try/catch, so a thrown exception (process
     * kill is handled by [WebRepository.clearStaleLoading] on init; everything
     * else lands here) doesn't leave a stuck spinner in the Channels tab.
     */
    private suspend fun fetchOne(
        username: String,
        forceNetwork: Boolean,
        fetchedAtMs: Long,
        forceMediaRefresh: Boolean = false,
    ): FetchOutcome {
        repository.markFetchStatus(username, ChannelFetchStatus.Loading)
        try {
            val result = fetchSemaphore.withPermit {
                client.fetchChannelPage(username, useCache = !forceNetwork)
            }
            when (result) {
                is FetchResult.Page -> {
                    // A page with zero posts for a channel we already hold posts for
                    // is a degraded parse (selectors silently stopped matching), not
                    // a channel that deleted its history — surface it as such instead
                    // of stamping "ok" over a feed that has stopped updating.
                    if (result.page.posts.isEmpty() && repository.hasPosts(username)) {
                        repository.markFetchStatus(
                            username = username,
                            status = ChannelFetchStatus.ParseFailure,
                            error = "parse failed",
                        )
                        return FetchOutcome.NoOp
                    }
                    captureArchiveDiff(username, result.page.posts, fetchedAtMs)
                    repository.ingestPage(
                        page = result.page,
                        etag = result.etag,
                        lastModified = result.lastModified,
                        fetchedAtMs = fetchedAtMs,
                        // Bypass the ingest fingerprint guard only when this fetch was
                        // triggered specifically to refresh media (TTL sweep or reactive
                        // stale-URL re-fetch), so a plateaued post whose CDN URL rotated
                        // still persists the fresh URL. A plain pull-to-refresh keeps the
                        // guard — see the callers in doRefresh / refetchForStalePost.
                        forceMediaRefresh = forceMediaRefresh,
                    )
                    return FetchOutcome.Fresh
                }
                FetchResult.NotModified -> {
                    repository.markNotModified(username, fetchedAtMs)
                    return FetchOutcome.NoOp
                }
                FetchResult.NotFound -> {
                    repository.markFetchStatus(username, ChannelFetchStatus.NotFound, error = null)
                    return FetchOutcome.NoOp
                }
                FetchResult.PrivateChannel -> {
                    repository.markFetchStatus(username, ChannelFetchStatus.Private)
                    return FetchOutcome.NoOp
                }
                is FetchResult.RateLimited -> {
                    repository.markFetchStatus(
                        username = username,
                        status = ChannelFetchStatus.RateLimited,
                        retryAfterMs = result.retryAfterMs,
                    )
                    return FetchOutcome.Transient
                }
                is FetchResult.NetworkError -> {
                    repository.markFetchStatus(
                        username = username,
                        status = ChannelFetchStatus.Error,
                        error = result.cause.message,
                    )
                    return FetchOutcome.Transient
                }
                FetchResult.ParseFailure -> {
                    repository.markFetchStatus(
                        username = username,
                        status = ChannelFetchStatus.ParseFailure,
                        error = "parse failed",
                    )
                    return FetchOutcome.NoOp
                }
            }
        } catch (t: Throwable) {
            // Belt: an exception thrown anywhere between the Loading marker and
            // a successful when-branch would otherwise leave the row stuck at
            // Loading until the next process restart. Demote to Error so the
            // Channels tab paints a real failure chip instead of a permanent
            // spinner. We re-throw cancellation so the caller's structured
            // concurrency stays intact.
            if (t is kotlinx.coroutines.CancellationException) {
                // A cancelled fetch (sweep / scope cancelled) would otherwise leave the
                // row at Loading — a permanent spinner until the next process start's
                // clearStaleLoading. NonCancellable: we're already cancelled, so a plain
                // suspending write would throw before reaching the DB.
                withContext(NonCancellable) {
                    runCatching { repository.clearLoading(username) }
                }
                throw t
            }
            repository.markFetchStatus(
                username = username,
                status = ChannelFetchStatus.Error,
                error = t.message,
            )
            return FetchOutcome.Transient
        }
    }

    /**
     * Run [WebRepository.vacuum] at most once per [VACUUM_INTERVAL_MS]. Called after a
     * successful sweep (still under [refreshMutex], so it never races a sweep's
     * ingest). A failure is logged and retried on the next interval — maintenance
     * must never fail the refresh that triggered it.
     */
    private suspend fun maybeVacuum() {
        if (!bookmarksSynced) return
        val now = System.currentTimeMillis()
        if (now - lastVacuumAtMs < VACUUM_INTERVAL_MS) return
        lastVacuumAtMs = now
        try {
            val foldDone = subscriptions.isSearchTextFoldDone()
            repository.vacuum(foldSearchText = !foldDone)
            if (!foldDone) subscriptions.markSearchTextFoldDone()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "vacuum failed: ${t.message}")
        }
    }

    /**
     * Diff the freshly-fetched [freshPosts] list against the previously-stored
     * snapshot for [username] and fire archive captures for any edits before
     * the ingest overwrites them.
     *
     * Edit detection: a post whose content changed (text, media filenames, web
     * preview, or forwarded-from) triggers [captureWebVersion] with the OLD
     * snapshot. [WebPostDiff] intentionally ignores CDN URL token rotation
     * (same filename, different query-string) and view/reaction churn.
     *
     * Web-mode deletion capture is intentionally not surfaced yet: ghost-feed
     * reconstruction is wired only for the TDLib path (see
     * [ArchiveRepository.observeTdlibTombstones]); writing WEB-source DELETED
     * rows that never reach the user would just waste storage.
     *
     * All captures are fire-and-forget via [scope].launch — they must NOT block
     * the ingest path. A capture failure only means the archive misses one event;
     * the feed itself is unaffected.
     */
    private suspend fun captureArchiveDiff(
        username: String,
        freshPosts: List<WebPost>,
        fetchedAtMs: Long,
    ) {
        val archive = archiveRepository ?: return
        val chat = dev.lyo.hortay.data.archive.ChatRef.web(username)
        // Bail out before decoding the channel's stored history: with the archive
        // off (the default) or this channel excluded, the decode is pure waste on
        // every sweep. captureWebVersion re-checks the same settings at write time.
        if (!archive.capturesEditsFor(chat)) return
        val previousById = repository.readWebPostsForChannel(username)
        if (previousById.isEmpty()) return

        for (fresh in freshPosts) {
            val previous = previousById[fresh.id] ?: continue
            if (webPostDiff.detectChange(previous, fresh) != null) {
                scope.launch {
                    archive.captureWebVersion(
                        chat = chat,
                        messageKey = previous.seq.toString(),
                        previous = previous,
                        seenAtOverrideMs = fetchedAtMs,
                    )
                }
            }
        }
    }

    internal enum class FetchOutcome { Fresh, NoOp, Transient }

    /**
     * Convenience "subscribe and refresh" that's the common path from the
     * Add-channel screen. Idempotent: subscribing to an already-followed channel
     * still triggers a fetch (user might be expecting fresh content) but the DB
     * upsert is a no-op.
     *
     * Writes go to BOTH stores:
     *   • [repository] direct write — channel table mirrors immediately so the
     *     UI (Channels tab row, FAB collapse, feed JOIN) reflects the new
     *     subscription within one frame, no DataStore round-trip required.
     *   • [subscriptions] DataStore — the source of truth for the migration
     *     coordinator (post sign-in, the user is offered each anonymous
     *     subscription as a TDLib JoinChat candidate). Without this write,
     *     migration would silently surface zero candidates regardless of how
     *     many channels the user accumulated in guest mode.
     */
    suspend fun subscribeAndRefresh(username: String, placeholderTitle: String = username) {
        // Lower-case at the entry boundary: SQLite's PK on `channel.username` is
        // case-sensitive by default, and a duplicate subscription with mixed
        // casing (`@Durov` vs `@durov`) would surface as two rows pointing at
        // the same Telegram channel. parseUsernameFromInput normalises too, but
        // direct callers (deep-link arrivals, curated rows, future code paths)
        // might pass a raw handle — this defends the data layer regardless.
        val normalized = username.lowercase()
        repository.subscribe(normalized, placeholderTitle)
        // Pre-populate [lastKnownSubscriptionsSet] under the same lock that
        // [handleSubscriptionsChanged] takes BEFORE we write DataStore. Without
        // this, the imminent DataStore emission lands in handleSubscriptionsChanged
        // with the new username appearing as "added" to the diff, which kicks
        // a force-refresh of EVERY subscribed channel. The user's intent was
        // "subscribe + fetch this one channel"; the side-effect was a full
        // N-channel sweep every time a single Add-Channel sheet completed.
        // The targeted fetchOne below handles the actual content load — and
        // it's already direct against the new username, so nothing is missed.
        subscriptionsMutex.withLock {
            val previous = lastKnownSubscriptionsSet
            if (previous != null) {
                lastKnownSubscriptionsSet = previous + normalized
            }
            // previous == null: first-emit path will fan out a force refresh
            // anyway via the normal flow; pre-populating the set on cold start
            // would suppress the legitimate first-load case. Leave alone.
        }
        subscriptions.add(normalized)
        // Targeted fetch of just this one channel. Detached from the suspension
        // chain via scope.launch so the Add-Channel UI can dismiss without
        // awaiting the network round-trip — see [PostsRepository.refresh]'s
        // similar fire-and-forget rationale; the user expects "added — feed
        // updates as content lands" not a blocking spinner.
        scope.launch {
            fetchOne(normalized, forceNetwork = true, fetchedAtMs = System.currentTimeMillis())
        }
    }

    /**
     * Wipe cached posts + channel payload, then trigger a forced refresh so
     * the user immediately sees fresh content instead of an empty feed
     * (cached) followed by a 5-minute scheduler tick before anything appears.
     * Subscriptions survive the wipe — they're user intent, not cache. Mirrors
     * the user expectation of a "Clear cache" button: short blank moment,
     * then the same channels re-populate.
     */
    suspend fun clearCacheAndRefresh() {
        repository.clearAllCache()
        // Reset the staleness gate so the upcoming refresh isn't bounced by
        // the 30-second cooldown — the user just asked for fresh data.
        lastSuccessfulRefreshAtMs = 0L
        doRefresh(force = true)
    }

    sealed interface RefreshState {
        data object Idle : RefreshState
        data class Refreshing(val channelCount: Int) : RefreshState
        data class Error(val message: String) : RefreshState
    }

    companion object {
        private const val TAG = "WebFeedSource"

        /** Minimum gap between [WebRepository.vacuum] runs. */
        private const val VACUUM_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * Adaptive-backoff aggregator. Three outcome buckets matter:
         *   - any [FetchOutcome.Fresh] → reset; the user wants prompt
         *     updates after silence breaks.
         *   - any [FetchOutcome.Transient] (RateLimited / NetworkError)
         *     without Fresh → leave the counter alone. A 429-burst that
         *     returned zero new content is NOT a "feed has been quiet"
         *     signal — conflating it would push the scheduler to 30-min
         *     idle intervals after a single t.me throttle blip, costing
         *     an hour of update lag for what's actually a transient
         *     network condition. Edge already told us via Retry-After
         *     when to come back; the gate in [WebTelegramClient] honours
         *     that on the next fetch.
         *   - all [FetchOutcome.NoOp] (304 / ParseFailure / NotFound /
         *     Private) → increment, this really was a quiet sweep.
         *
         * Pure function: extracted from [doRefresh] so the bucket logic
         * is testable without spinning up an HTTP / SQLite stack.
         */
        /**
         * Union of the feed window and the bookmarked rows. The common case — every
         * saved post is already inside the feed window — returns [feed] untouched;
         * otherwise the extra saved posts are appended and the list re-sorted in
         * the feed's own order (date DESC, then seq DESC). Pure for testability.
         */
        internal fun mergeSaved(
            feed: PersistentList<TimelinePost>,
            saved: PersistentList<TimelinePost>,
        ): PersistentList<TimelinePost> {
            if (saved.isEmpty()) return feed
            val inFeed = feed.mapTo(HashSet(feed.size)) { it.chatId to it.id }
            val extra = saved.filter { (it.chatId to it.id) !in inFeed }
            if (extra.isEmpty()) return feed
            return (feed + extra)
                .sortedWith(compareByDescending<TimelinePost> { it.date }.thenByDescending { it.id })
                .toPersistentList()
        }

        internal fun nextNoOpStreak(outcomes: List<FetchOutcome>, current: Int): Int = when {
            outcomes.any { it == FetchOutcome.Fresh } -> 0
            outcomes.any { it == FetchOutcome.Transient } -> current
            else -> current + 1
        }

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

        /**
         * Coalescing window for the reactive stale-media re-fetch. A channel whose
         * CDN tokens just expired will surface many near-simultaneous Coil errors
         * (every visible card of that channel); one re-fetch inside this window is
         * enough to refresh them all, so repeat reports are dropped. A few minutes
         * balances "recover promptly" against "don't re-fetch on every scroll".
         */
        const val STALE_REFETCH_COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes

        /**
         * Posts per t.me/s/ page. Seq gaps (deleted posts) only make a real page
         * span MORE seqs, so treating a page as covering this many is conservative.
         */
        private const val OLDER_PAGE_SPAN = 20L

    }
}
