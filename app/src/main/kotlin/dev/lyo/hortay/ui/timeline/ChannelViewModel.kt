package dev.lyo.hortay.ui.timeline

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val scrollToMessageId: Long?,
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

    // Deep-link around-load attempt flag. Starts false; flipped to true by the init
    // block after [repo.loadHistoryAround] resolves (or the 1500 ms grace elapses).
    // Consumed by [buildChannelUiState] — once true and the target still isn't in
    // [posts], the UI transitions to [ChannelUiState.Missing] and falls back to index 0.
    // No-op when [scrollToMessageId] is null (no deep-link).
    private val _attemptedAround = MutableStateFlow(false)
    val attemptedAround: StateFlow<Boolean> = _attemptedAround.asStateFlow()

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

    // Channel avatar source for the top-bar TdAvatar — same minithumb / fileId pair
    // ChannelsScreen rows use. Resolved reactively from the post stream (every
    // channel post carries the channel's identity in its sender fields) with a
    // one-shot [PostsRepository.chatAvatar] fallback for channels not yet in the
    // merged feed. Until both resolve, [TdAvatar] paints the initial-letter
    // placeholder on its primaryContainer disc.
    private val _channelAvatarFileId = MutableStateFlow<Int?>(null)
    val channelAvatarFileId: StateFlow<Int?> = _channelAvatarFileId.asStateFlow()

    private val _channelAvatarThumb = MutableStateFlow<ByteArray?>(null)
    val channelAvatarThumb: StateFlow<ByteArray?> = _channelAvatarThumb.asStateFlow()

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
        // Deep-link around-load: if the caller supplied a scrollToMessageId, check
        // whether the target is already in the global feed slice (from the cold-start
        // harvest). If not, issue loadHistoryAround exactly once. The repo returns
        // `true` when the around-window landed (the target post should reach the
        // posts flow shortly), `false` when the chat is inaccessible / FLOOD_WAIT
        // exhausted / permission revoked — no point waiting on a post that will
        // never come, so skip the timeout and flip [_attemptedAround] immediately
        // so [buildChannelUiState] can transition to Missing.
        if (scrollToMessageId != null) {
            viewModelScope.launch {
                val initialMatch = posts.value.any { p ->
                    p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds
                }
                if (!initialMatch) {
                    val landed = runCatching {
                        repo.loadHistoryAround(chatId, scrollToMessageId)
                    }.getOrDefault(false)
                    if (landed) {
                        withTimeoutOrNull(1_500L) {
                            posts.first { ps ->
                                ps.any { p ->
                                    p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds
                                }
                            }
                        }
                    }
                }
                _attemptedAround.value = true
            }
        }
        // Channel title: watch the post stream for a post whose senderName / channelContext
        // gives the canonical channel identity; fall back to a one-shot chatTitle() for
        // channels not yet in the merged feed. Lazy-evaluated so we don't block the VM
        // constructor for a TDLib round-trip.
        viewModelScope.launch {
            // Fast synchronous path from the already-populated post stream.
            val anchor = repo.posts.value.firstOrNull { it.chatId == chatId }
            val titleFromPosts = anchor?.let { it.channelContext?.name ?: it.senderName }
            if (titleFromPosts != null) {
                _channelTitle.value = titleFromPosts
            } else {
                // Suspension fallback: TDLib serves this from its local chat cache,
                // which is warm after UpdateNewChat has fired for this chat.
                _channelTitle.value = repo.chatTitle(chatId)
            }
            // Avatar minithumb/fileId from the same anchor when present. TimelinePost
            // already carries them (populated by the channel-info hook at ingest); a
            // channel post's sender IS the channel, so these are the channel's own
            // avatar. Falls back to a [chatAvatar] one-shot for the cold-link case
            // where loadChannelHistory hasn't materialised the first post yet.
            val avatarFromPosts = anchor?.let { it.avatarFileId to it.avatarThumb }
            if (avatarFromPosts != null && (avatarFromPosts.first != null || avatarFromPosts.second != null)) {
                _channelAvatarFileId.value = avatarFromPosts.first
                _channelAvatarThumb.value = avatarFromPosts.second
            } else {
                val cached = repo.chatAvatar(chatId)
                if (cached != null) {
                    _channelAvatarFileId.value = cached.first
                    _channelAvatarThumb.value = cached.second
                }
            }
        }
        viewModelScope.launch {
            // Keep title AND avatar up-to-date as posts arrive (e.g. non-subscribed
            // channel whose first post lands after loadChannelHistory completes,
            // or a profile-photo change pushed via UpdateChatPhoto downstream).
            posts.collect { channelPosts ->
                val anchor = channelPosts.firstOrNull() ?: return@collect
                val resolvedName = anchor.channelContext?.name ?: anchor.senderName
                if (resolvedName != _channelTitle.value) {
                    _channelTitle.value = resolvedName
                }
                if (anchor.avatarFileId != _channelAvatarFileId.value) {
                    _channelAvatarFileId.value = anchor.avatarFileId
                }
                if (anchor.avatarThumb !== _channelAvatarThumb.value) {
                    _channelAvatarThumb.value = anchor.avatarThumb
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
