package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Twitter-style "новi пости" semantics, anchored on a per-channel **date** high-water mark
 * rather than a set of message ids. A post is "pending new" iff `post.date > seenHighWater[chatId]`.
 *
 * Why date and not ids:
 * The repository writes `_posts` from many sources, only one of which is "actually new":
 *   • [FeedSource.refresh] — top-N per channel; on cold start everything here is the bootstrap
 *     baseline (and on PTR everything is acked immediately by [acceptPending]).
 *   • [PostsRepository.handleNewMessage] via [TdApi.UpdateNewMessage] — *real* new posts; their
 *     date is strictly greater than what the user has already seen.
 *   • [PostsRepository.loadOlder] — pagination scroll-down; intentionally *older* posts.
 *   • [PostsRepository.loadChannelHistory] — channel-filter open or fresh-join deep load;
 *     *older* posts back-filled into the per-channel slice.
 *   • [FeedSource.restoreFromSnapshot] — cold-start cache rehydration.
 *
 * The previous id-set model classified anything not in [seenPostIds] as pending, so paths
 * 3 / 4 / 5 (older posts, never-seen-before older posts) all surfaced under the "новi пости"
 * pill the moment they landed — the user-reported "якось дивно, рандомно" symptom: scroll
 * down, suddenly the pill claims "12 нових постів" pointing at posts weeks old.
 *
 * Date-based high-water makes pagination semantically invisible to the pill (older arrivals
 * never satisfy `date > hw`) while preserving correct behaviour for `UpdateNewMessage`
 * (newer date → pending). Telegram-Android, X, Mastodon all use the same per-channel
 * date / id high-water pattern.
 *
 * Bootstrap: on the first stable [livePosts] emission (`!refreshing && livePosts.isNotEmpty()`),
 * every channel is seeded with its current max-date. After bootstrap, brand-new chatIds
 * appearing in [livePosts] (e.g. user opens a channel filter that triggers
 * [PostsRepository.loadChannelHistory] for a channel never previously in the merged feed)
 * are auto-seeded with their initial max-date — so those 80 back-filled posts don't all
 * flash as pending, while a *subsequent* [UpdateNewMessage] for that same channel still
 * lands above the seeded mark and registers as pending.
 *
 * Bootstrap is gated on `!isRefreshing` because [PostsRepository.refreshLocked] streams
 * per-channel results (UX win: posts visible within ~100ms of cold start instead of after
 * the full ~5s drain). Seeding on the first non-empty emission would mark only one
 * channel's content as "seen" and tag every subsequent channel's streamed posts as pending.
 */
class TimelineViewModel(
    private val repo: FeedSource,
    private val bookmarks: BookmarkStore,
) : ViewModel() {

    // Merged feed reads `subscribedPosts` so transient rows fetched into
    // `repo.posts` by a single-channel drill (e.g. tapping a comment-link into
    // a channel the user is NOT subscribed to → `loadChannelHistory` writes
    // 80 rows into `_posts`) don't leak into TimelineScreen. ChannelScreen
    // keeps using `repo.posts` directly with its own per-chat filter.
    private val livePosts: StateFlow<PersistentList<TimelinePost>> = repo.subscribedPosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    // chatId → max post.date the user has acknowledged seeing in this channel. Pending =
    // posts in livePosts whose date strictly exceeds this mark for their chatId. Empty
    // map until bootstrap completes — see [bootstrapped] below.
    private val seenHighWater = MutableStateFlow<Map<Long, Long>>(emptyMap())
    // True once the first stable livePosts emission has seeded [seenHighWater]. Both
    // pendingNew and posts gate on this so a fast first-paint (snapshot restore) doesn't
    // briefly classify the whole feed as either "all pending" (empty hw + filter `>`) or
    // "all visible" (empty hw + filter passthrough) before bootstrap settles.
    private val bootstrapped = MutableStateFlow(false)

    // Visible feed: pre-bootstrap, show everything live (avoid blank-screen during cold
    // start); post-bootstrap, hide posts that exceed the per-channel mark — those are the
    // user's pending. New chatIds (`mark == null`) are visible by default; they're auto-
    // seeded immediately after first sighting (see init block) so subsequent UpdateNewMessage
    // events on that channel are correctly pending.
    val posts: StateFlow<PersistentList<TimelinePost>> =
        combine(livePosts, seenHighWater, bootstrapped) { live, hw, ready ->
            if (!ready) live
            else {
                // Stale arrivals (post.date > hw but older than the recency
                // window) skip pendingNew and land directly in `posts` — see
                // [pendingNew] doc for why. They're slotted into the feed
                // in their natural date position (the consumer sorts by date)
                // rather than buffered under a "X новi постiв" pill that
                // would mislead the user about freshness.
                val cutoff = System.currentTimeMillis() - PENDING_NEW_RECENCY_WINDOW_MS
                live.filter { p ->
                    val mark = hw[p.chatId] ?: return@filter true
                    p.date <= mark || p.date < cutoff
                }.toPersistentList()
            }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    val pendingNew: StateFlow<PersistentList<TimelinePost>> =
        combine(livePosts, seenHighWater, bootstrapped) { live, hw, ready ->
            if (!ready) persistentListOf()
            else {
                // Recency window: a post is "new" only when its server-side
                // timestamp is within [PENDING_NEW_RECENCY_WINDOW_MS] of now.
                // TDLib re-broadcasts UpdateNewMessage for messages it has had
                // in local cache but hadn't surfaced yet — typical triggers:
                // user opening a channel they hadn't visited in a while, TDLib
                // post-reconnect resync, or fetching linked-discussion-group
                // parents. Without the window, days-old posts would inflate
                // the "X нових" pill counter and surface as freshness when
                // they're really just sync catch-up. Both timestamps are in
                // milliseconds — [MessageMapper.toChannelPost] does the
                // `* 1000L` conversion on `TdApi.Message.date` (Unix seconds)
                // so `TimelinePost.date` is consistently ms.
                val cutoff = System.currentTimeMillis() - PENDING_NEW_RECENCY_WINDOW_MS
                live.filter { p ->
                    val mark = hw[p.chatId] ?: return@filter false
                    p.date > mark && p.date >= cutoff
                }.toPersistentList()
            }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    val bookmarkedKeys: StateFlow<Set<String>> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Latched cold-start cursors-settled flag. Flips `false → true` exactly once
     * per ViewModel lifetime, when [TimelineScreen]'s grace timer fires for the
     * first time (TDLib's `UpdateChatReadInbox` burst has landed and the
     * boundary picker can run against a stable map). Stays true for the rest of
     * the session — including across [dev.lyo.hortay.ui.main.TabContentSwitcher]
     * AnimatedContent unmount / remount.
     *
     * Why VM-resident, not screen-local `remember`: the previous
     * `remember(feed, feedOrder) { mutableStateOf(false) }` reset on every
     * Feed-tab remount, so a Feed → Channels → Feed swap re-armed the
     * `COLD_START_CURSOR_GRACE_MS` grace and the candidate [TimelineUiState]
     * fell back to [TimelineUiState.Loading] for the duration — a visible
     * `SkeletonFeed` flash on every tab return. The flag is actually a
     * **session-scoped** property of "has TDLib delivered the cursor map
     * yet" — once observed true, it can never legitimately go back to false
     * within a session.
     *
     * ViewModel lifetime is the correct scope: Activity-scoped (survives tab
     * swaps, config changes, overlay re-mounts) and discarded on process
     * death (correct — a stale flag from the previous process is meaningless
     * before refresh + cursor map land in the new one). Same rationale as
     * [pinnedScrollSeedKeys] below.
     */
    private val _coldStartCursorsSettled = MutableStateFlow(false)
    val coldStartCursorsSettled: StateFlow<Boolean> = _coldStartCursorsSettled.asStateFlow()

    fun markColdStartCursorsSettled() {
        if (!_coldStartCursorsSettled.value) _coldStartCursorsSettled.value = true
    }

    /**
     * Per-route cold-start scroll anchor for [TimelineScreen]'s LazyColumn.
     * Captured the first time the screen renders a [TimelineUiState.Ready] for
     * the route, then held constant so re-mounts (deep nav, where
     * [dev.lyo.hortay.ui.main.NavOverlayRenderer]'s `takeLast(2)` window
     * evicts the feed) land the user back where they came from.
     *
     * Stored as a [dev.lyo.hortay.ui.timeline.FeedItem] key, NOT a row index.
     * Indices are positional and silently rot under any ingest: a channel
     * drill calling [dev.lyo.hortay.data.posts.PostsRepository.loadChannelHistory]
     * injects ~80 historical posts into `_posts` while the overlay is up; on
     * pop they merge above the previous anchor in asc-by-date sort and
     * "index N" now names a completely different row. Keys survive that —
     * `FeedItem.key` is `(chatId, mediaAlbumId)` for albums and
     * `(chatId, post.id)` for solos, both stable across the whole session.
     *
     * Why VM-resident, not `rememberSaveable`: the anchor must survive tab
     * swaps within a process AND deep nav re-mounts (TimelineScreen disposes
     * when the stack goes past 2) but MUST NOT survive process death — a
     * stale cold-start anchor from the previous process is almost always
     * wrong by the time refresh + cursor map land in the new one. ViewModel
     * lifetime matches that exactly: scoped to the Activity ViewModelStore
     * (alive through config changes, tab swaps, and overlay re-mounts),
     * discarded when the process ends.
     *
     * `mutableStateMapOf` so Composable reads subscribe to per-key changes —
     * setting an entry triggers exactly the recomposition that wants it.
     */
    private val pinnedScrollSeedKeys: SnapshotStateMap<Any, String> = mutableStateMapOf()

    fun pinnedScrollSeedKey(routeKey: Any): String? = pinnedScrollSeedKeys[routeKey]

    /** One-shot capture — subsequent boundary movements never override the anchor. */
    fun capturePinnedScrollSeedKey(routeKey: Any, key: String) {
        if (routeKey !in pinnedScrollSeedKeys) pinnedScrollSeedKeys[routeKey] = key
    }

    init {
        // Bootstrap: first stable emission seeds [seenHighWater] for every chatId in
        // livePosts with that channel's max date, then flips [bootstrapped] true.
        // Gate is `livePosts.isNotEmpty() && !refreshing` — under the event-driven
        // ingest design `refreshing` only flips during a user-initiated PTR (the
        // session-start drain runs out of [AppGraph], not this ViewModel), so the
        // common cold path is "livePosts streams in, `refreshing` stays false,
        // bootstrap fires on the first non-empty emission". PTR keeps the
        // `refreshing=true` guard so the bootstrap doesn't run on a transient
        // mid-PTR snapshot.
        viewModelScope.launch {
            combine(livePosts, refreshing) { posts, isRefreshing ->
                posts.takeIf { it.isNotEmpty() && !isRefreshing }
            }
                .filterNotNull()
                .first()
                .let { stable ->
                    if (!bootstrapped.value) {
                        seenHighWater.value = stable
                            .groupBy { it.chatId }
                            .mapValues { (_, ps) -> ps.maxOf { it.date } }
                        bootstrapped.value = true
                    }
                }
        }
        // Auto-seed brand-new channels: after bootstrap, any chatId entering livePosts
        // that's not yet in the high-water map gets its current max date stamped in.
        // Drives the "user opens a fresh channel filter → loadChannelHistory back-fills 80
        // older posts → none of those should be pending, but a later UpdateNewMessage on
        // that channel should be" path.
        viewModelScope.launch {
            bootstrapped.first { it }
            livePosts.collect { live ->
                val knownChats = seenHighWater.value.keys
                val unseenChats = HashSet<Long>()
                for (p in live) {
                    if (p.chatId !in knownChats) unseenChats += p.chatId
                }
                if (unseenChats.isEmpty()) return@collect
                seenHighWater.update { current ->
                    val updated = HashMap(current)
                    for (chatId in unseenChats) {
                        val maxDate = live.asSequence()
                            .filter { it.chatId == chatId }
                            .maxOfOrNull { it.date } ?: continue
                        updated.putIfAbsent(chatId, maxDate)
                    }
                    updated
                }
            }
        }
        // Snapshot restore is fired immediately. The session-start
        // [PostsRepository.triggerInitialSync] now runs out of [AppGraph]
        // against `auth.Ready` — this ViewModel does NOT kick another
        // refresh from `init`. The previous shape racing
        // `refreshIfStale() + first { !it }; restore` layered redundant
        // LoadChats on top of AppGraph's drain and put a `refreshing=true`
        // gate around the first-paint window, delaying the snapshot
        // fallback for offline / RPC-error cases.
        //
        // [restoreFromSnapshot] is idempotent against an ingest stream
        // racing in (its `current.isNotEmpty()` early-return makes the live
        // refresh result win the first paint). Offline path: snapshot
        // replaces the empty feed within ~100 ms of construction.
        viewModelScope.launch { repo.restoreFromSnapshot() }
    }

    /** Pull-to-refresh — always fires regardless of staleness. Reveals pending afterwards. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            repo.refresh()
            acceptPending()
            _refreshing.value = false
        }
    }

    /**
     * Reveal all pendingNew posts (used after PTR, where the whole list is fresh).
     * Bumps each channel's high-water to the live max-date — never lowers it, so a
     * concurrent [acceptIds] firing from auto-accept-at-top during the PTR window
     * (refresh takes seconds; UpdateNewMessage events can stream in and trigger
     * auto-accept while it runs) can't be clobbered by this full-rebuild.
     */
    fun acceptPending() {
        val live = livePosts.value
        if (live.isEmpty()) return
        val maxByChat = HashMap<Long, Long>()
        for (p in live) {
            val prev = maxByChat[p.chatId]
            if (prev == null || p.date > prev) maxByChat[p.chatId] = p.date
        }
        if (maxByChat.isEmpty()) return
        seenHighWater.update { current ->
            val updated = HashMap(current)
            for ((chatId, date) in maxByChat) {
                val existing = updated[chatId]
                updated[chatId] = if (existing == null) date else maxOf(existing, date)
            }
            updated
        }
        if (!bootstrapped.value) bootstrapped.value = true
    }

    /**
     * Mark a specific subset of posts as seen — used by the pill / at-top auto-accept to
     * ack only the posts that are actually visible in the user's current scope (e.g.
     * tapping the pill in "All" must not silently clear pending counts that belong to the
     * Archive tab the user hasn't even opened yet). The call site passes (chatId, id)
     * pairs; we look up each pair's date in livePosts and bump that channel's high-water
     * to the max acked date — any older post the caller might have included is already
     * covered by the bump, no per-id state needed.
     */
    fun acceptIds(ids: Collection<Pair<Long, Long>>) {
        if (ids.isEmpty()) return
        val live = livePosts.value
        if (live.isEmpty()) return
        val targets = ids.toHashSet()
        val maxDateByChat = HashMap<Long, Long>()
        for (p in live) {
            if ((p.chatId to p.id) !in targets) continue
            val prev = maxDateByChat[p.chatId]
            if (prev == null || p.date > prev) maxDateByChat[p.chatId] = p.date
        }
        if (maxDateByChat.isEmpty()) return
        seenHighWater.update { current ->
            val updated = HashMap(current)
            for ((chatId, date) in maxDateByChat) {
                val existing = updated[chatId]
                updated[chatId] = if (existing == null) date else maxOf(existing, date)
            }
            updated
        }
    }

    fun toggleBookmark(post: TimelinePost) {
        viewModelScope.launch { bookmarks.toggle(post) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        // Pending-pill freshness gate, in milliseconds (matches
        // [TimelinePost.date]). Posts whose `date` is older than this
        // window are sync catch-up, not genuine new arrivals — they go
        // straight to [posts] without surfacing in the "X new posts" pill.
        // 6 h covers typical offline-and-back-in-the-evening cases while
        // catching deeper backfills (a user opening a channel they hadn't
        // looked at in days) as stale.
        const val PENDING_NEW_RECENCY_WINDOW_MS = 6L * 60L * 60L * 1000L
    }
}

@Immutable
data class ChannelBadge(
    val chatId: Long,
    val title: String,
    val thumb: ByteArray?,
    val fileId: Int?,
    /**
     * Web/guest-mode CDN avatar URL. Null in TDLib mode (where [fileId] /
     * [thumb] do the work); set in guest mode where TDLib services aren't
     * available. Without it the new-posts pill rendered guest-mode channels
     * as plain initial-letter circles.
     */
    val avatarUrl: String? = null,
    val latestPostDate: Long,
)
