package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
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

    private val livePosts: StateFlow<PersistentList<TimelinePost>> = repo.posts
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
                val now = System.currentTimeMillis() / 1000
                val cutoff = now - PENDING_NEW_RECENCY_WINDOW_S
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
                // they're really just sync catch-up.
                val now = System.currentTimeMillis() / 1000  // TdApi.Message.date is Unix seconds
                val cutoff = now - PENDING_NEW_RECENCY_WINDOW_S
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

    init {
        // Bootstrap: first stable emission seeds [seenHighWater] for every chatId in
        // livePosts with that channel's max date, then flips [bootstrapped] true.
        // Two arrival paths satisfy the gate:
        //   1. Cold path: refreshing flips false → true → false; once back at false,
        //      livePosts holds the streamed result.
        //   2. Warm path: refreshIfStale skipped (data warm) — refreshing stays false,
        //      livePosts populated from snapshot only.
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
        // Cold-start path: restore the persisted snapshot first so the user sees real
        // content within ~100ms (TDLib serves GetMessage from local DB synchronously),
        // then kick off the freshness check in parallel. Both write through the same
        // race-safe `_posts.update` merge in PostsRepository, so whichever finishes
        // first becomes visible and the other layers on top without clobbering.
        viewModelScope.launch { repo.restoreFromSnapshot() }
        refreshIfStale()
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

    /** Soft refresh used on VM construction; the repo skips if data is still warm. */
    private fun refreshIfStale() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            repo.refreshIfStale()
            _refreshing.value = false
        }
    }

    fun toggleBookmark(post: TimelinePost) {
        viewModelScope.launch { bookmarks.toggle(post) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        // Pending-pill freshness gate. Posts whose `date` is older than this
        // window are sync catch-up, not genuine new arrivals — they go
        // straight to [posts] without surfacing in the "X new posts" pill.
        // 6 h covers typical offline-and-back-in-the-evening cases while
        // catching deeper backfills (a user opening a channel they hadn't
        // looked at in days) as stale.
        const val PENDING_NEW_RECENCY_WINDOW_S = 6L * 60L * 60L
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
