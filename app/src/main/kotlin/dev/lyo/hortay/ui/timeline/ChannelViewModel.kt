package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-channel ViewModel created (and keyed) by [ChannelScreen] on entry. Each chatId
 * gets its own instance via `viewModel(key = "channel:$chatId")`, so the all-feed
 * [TimelineViewModel] and every channel view have independent state machines.
 *
 * Design decisions that differ from [TimelineViewModel]:
 *
 *   - No pending-new / high-water semantics. A single-channel view shows ALL posts for
 *     that channel including real-time arrivals — there is no Twitter-style "X new posts"
 *     pill concept when the user is already inside the channel.
 *
 *   - [historyLoading] is true from VM init until the first [loadChannelHistory] round-
 *     trip completes (or fails). Drives the [ChannelPreviewSkeleton] in the UI — same
 *     idiom Telegram-Android uses on its public-channel preview screen.
 *
 *   - [paginationLoading] coalesces rapid near-bottom scroll events so [loadOlder] is
 *     never called while a previous load for this channel is still in flight.
 *
 *   - [channelTitle] is derived reactively from [PostsRepository.posts] (preferred, same
 *     canonical-identity rule as the old TimelineScreen filter bar) with a suspend
 *     [PostsRepository.chatTitle] fallback for channels not yet in the merged feed.
 *     [channelSubscribers] uses [PostsRepository.channelSubscribers] which is a one-shot
 *     TDLib cache hit in steady state.
 *
 *   - Search is fully owned here — 300 ms debounce on [searchQuery], scoped to [chatId]
 *     via [PostsRepository.searchInChannel]. Results live in [searchResults]; the UI reads
 *     [searchActive] to decide which list to render.
 *
 * No need for `restoreFromSnapshot` / `refreshIfStale` here — [PostsRepository.posts]
 * is the single upstream and is populated by [TimelineViewModel] via the feed bootstrap.
 * [loadChannelHistory] gives the deep per-channel slice the feed does not have on cold
 * start.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val repo: PostsRepository,
    private val bookmarks: BookmarkStore,
    val chatId: Long,
) : ViewModel() {

    // Live per-channel slice of the global [PostsRepository.posts] flow. Filters by
    // chatId, de-nests into a PersistentList so callers always read a stable
    // @Immutable reference. Service and ExpiredMedia rows are kept — the channel view
    // IS the scope, so we never drop posts the user explicitly asked to see (same
    // rationale as the old `channelFilter != null` path in TimelineScreen).
    val posts: StateFlow<PersistentList<TimelinePost>> = repo.posts
        .map { all -> all.filter { it.chatId == chatId }.toPersistentList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    // First-load guard: true from init until [loadChannelHistory] resolves — drives
    // [ChannelPreviewSkeleton]. Reset to false regardless of success/failure so an
    // inaccessible channel doesn't freeze the screen on the skeleton forever.
    private val _historyLoading = MutableStateFlow(true)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    // Pagination single-flight guard: prevents concurrent [loadOlder] calls from a
    // rapid-scroll listener (near-bottom LaunchedEffect fires many times before the
    // first call completes).
    private val _paginationLoading = MutableStateFlow(false)
    val paginationLoading: StateFlow<Boolean> = _paginationLoading.asStateFlow()

    // PTR in-flight state.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Channel header: title resolved from the post stream first (canonical identity rule
    // from TimelineScreen: channelContext?.name wins for personal-author posts, else
    // senderName, else TDLib chatTitle). Falls back to a suspend chatTitle() lookup for
    // channels not yet in the merged feed (e.g. a deep link to a non-subscribed public
    // channel where [loadChannelHistory] hasn't completed yet).
    private val _channelTitle = MutableStateFlow<String?>(null)
    val channelTitle: StateFlow<String?> = _channelTitle.asStateFlow()

    // Subscriber count: one-shot TDLib cache hit; populated after init.
    private val _channelSubscribers = MutableStateFlow<Int?>(null)
    val channelSubscribers: StateFlow<Int?> = _channelSubscribers.asStateFlow()

    // Bookmark set forwarded from the shared [BookmarkStore].
    val bookmarkedKeys: StateFlow<Set<String>> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    // --- Search state ---

    private val _searchActive = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Results: debounced + flatMapped so a new keystroke cancels the in-flight RPC.
    // Empty when search is inactive or query is blank.
    val searchResults: StateFlow<List<TimelinePost>> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { query ->
            flow {
                emit(
                    if (_searchActive.value && query.isNotBlank()) {
                        repo.searchInChannel(chatId, query.trim())
                    } else {
                        emptyList()
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        // First-load: deep-dive channel history so the single-channel view has more
        // than the one post per channel that the global cold-start harvest provides.
        // Runs in the VM scope so it survives composition changes. historyLoading
        // clears regardless of outcome — an inaccessible channel shows the empty hero,
        // not a frozen skeleton.
        viewModelScope.launch {
            try {
                repo.loadChannelHistory(chatId)
            } finally {
                _historyLoading.value = false
            }
        }
        // Channel title: watch the post stream for a post whose senderName / channelContext
        // gives the canonical channel identity; fall back to a one-shot chatTitle() for
        // channels not yet in the merged feed. Lazy-evaluated so we don't block the VM
        // constructor for a TDLib round-trip.
        viewModelScope.launch {
            // Fast synchronous path from the already-populated post stream.
            val fromPosts = repo.posts.value
                .filter { it.chatId == chatId }
                .let { matches ->
                    matches.firstNotNullOfOrNull { it.channelContext?.name }
                        ?: matches.firstOrNull()?.senderName
                }
            if (fromPosts != null) {
                _channelTitle.value = fromPosts
            } else {
                // Suspension fallback: TDLib serves this from its local chat cache,
                // which is warm after UpdateNewChat has fired for this chat.
                _channelTitle.value = repo.chatTitle(chatId)
            }
        }
        viewModelScope.launch {
            // Also keep the title up-to-date as posts arrive (e.g. non-subscribed
            // channel whose firstpost lands after loadChannelHistory completes).
            posts.collect { channelPosts ->
                val resolved = channelPosts
                    .let { list ->
                        list.firstNotNullOfOrNull { it.channelContext?.name }
                            ?: list.firstOrNull()?.senderName
                    }
                if (resolved != null && resolved != _channelTitle.value) {
                    _channelTitle.value = resolved
                }
            }
        }
        // Subscriber count: one-shot, TDLib serves from local supergroup cache.
        viewModelScope.launch {
            _channelSubscribers.value = repo.channelSubscribers(chatId)
        }
    }

    /** Pull-to-refresh for the channel view. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repo.loadChannelHistory(chatId)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Paginate older history for this channel. Guards against concurrent calls —
     * if a [loadOlder] is already in flight, the next call is a no-op. The
     * repository's own [PostsRepository.loadOlder] single-flight handles the actual
     * dedup, but adding the local guard here avoids queuing dozens of redundant
     * calls from the near-bottom snapshotFlow.
     */
    fun loadOlderIfPossible() {
        if (_paginationLoading.value) return
        viewModelScope.launch {
            _paginationLoading.value = true
            try {
                repo.loadOlder(chatId)
            } finally {
                _paginationLoading.value = false
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        if (!active) _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleBookmark(post: TimelinePost) {
        viewModelScope.launch { bookmarks.toggle(post) }
    }

    suspend fun viewMessages(chatId: Long, messageIds: List<Long>) {
        repo.viewMessages(chatId, messageIds)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How long after the last keystroke before issuing a [PostsRepository.searchInChannel]
         * RPC. Matches [TimelineScreen.SEARCH_DEBOUNCE_MS] for consistent UX cadence.
         */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
