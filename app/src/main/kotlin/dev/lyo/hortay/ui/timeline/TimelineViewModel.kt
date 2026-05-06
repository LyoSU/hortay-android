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
 * Twitter-style "новi пости" semantics: the visible feed is frozen on what the user has
 * already seen ([seenPostIds]); anything the live repo has on top of that is held back as
 * [pendingNew] until the user explicitly reveals it (pill tap or pull-to-refresh).
 */
class TimelineViewModel(
    private val repo: FeedSource,
    private val bookmarks: BookmarkStore,
) : ViewModel() {

    private val livePosts: StateFlow<PersistentList<TimelinePost>> = repo.posts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    // Empty set means "first launch — show everything live"; once bootstrapped, the set is
    // the snapshot of (chatId, id) pairs the user is currently looking at, and additions to
    // livePosts beyond it are pendingNew. Encoded as packed Long to avoid Pair allocation
    // on the hot filter path: each TimelinePost.chatId is a Telegram int64 (channel ids are
    // negative supergroup-ish values; comments use real chat ids), and its id is the message
    // id (≤ 2^32 in practice). XOR'ing the two gives a distribution-good 64-bit fingerprint
    // that's effectively collision-free across one user's subscriptions, and avoids
    // boxing two Longs into a Pair on every filter pass over the 1000-post feed.
    private val seenPostIds = MutableStateFlow<Set<Long>>(emptySet())

    val posts: StateFlow<PersistentList<TimelinePost>> = combine(livePosts, seenPostIds) { live, seen ->
        if (seen.isEmpty()) live
        else live.filter { encodeKey(it) in seen }.toPersistentList()
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    val pendingNew: StateFlow<PersistentList<TimelinePost>> = combine(livePosts, seenPostIds) { live, seen ->
        if (seen.isEmpty()) persistentListOf()
        else live.filter { encodeKey(it) !in seen }.toPersistentList()
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    private fun encodeKey(p: TimelinePost): Long = p.chatId xor (p.id * 0x9E3779B97F4A7C15UL.toLong())

    val bookmarkedKeys: StateFlow<Set<String>> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // Seed seenPostIds with whatever the feed has on first non-empty emission, so the
        // user starts looking at the existing feed (not at an "all are new" pill).
        viewModelScope.launch {
            livePosts.first { it.isNotEmpty() }.let { initial ->
                if (seenPostIds.value.isEmpty()) {
                    seenPostIds.value = initial.mapTo(hashSetOf()) { encodeKey(it) }
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

    /** Reveal all pendingNew posts (used after PTR, where the whole list is fresh). */
    fun acceptPending() {
        seenPostIds.value = livePosts.value.mapTo(hashSetOf()) { encodeKey(it) }
    }

    /**
     * Mark a specific subset of posts as seen — used by the pill / at-top auto-accept
     * to ack only the posts that are actually visible in the user's current scope
     * (e.g. tapping the pill in "All" must not silently clear pending counts that
     * belong to the Archive tab the user hasn't even opened yet).
     */
    fun acceptIds(ids: Collection<Pair<Long, Long>>) {
        if (ids.isEmpty()) return
        val encoded = ids.mapTo(hashSetOf()) { (chatId, id) ->
            chatId xor (id * 0x9E3779B97F4A7C15UL.toLong())
        }
        seenPostIds.update { it + encoded }
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
