package dev.lyo.hortay.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-channel ViewModel created (and keyed) by [ChannelScreen] on entry.
 * Each chatId gets its own instance via `viewModel(key = "channel:$chatId")`,
 * so the all-feed [TimelineViewModel] and every channel view have
 * independent state machines.
 *
 * Design decisions that differ from [TimelineViewModel]:
 *
 *   - **Single [data] state.** Channel posts and the "still loading"
 *     bit live in one [ChannelData] union exposed as one StateFlow.
 *     Compose can never observe an inconsistent (posts, loading) pair
 *     across the asynchronous gap between two StateFlow updates — the
 *     previous design's race is structurally absent. See [ChannelData]
 *     KDoc for the full rationale.
 *
 *   - No pending-new / high-water semantics. A single-channel view shows
 *     ALL posts for that channel including real-time arrivals — there is
 *     no Twitter-style "X new posts" pill concept when the user is
 *     already inside the channel.
 *
 *   - [paginationLoading] coalesces rapid near-bottom scroll events so
 *     [PostsRepository.loadOlder] is never called while a previous load
 *     for this channel is still in flight.
 *
 *   - [channelTitle] is derived reactively from the channel slice
 *     (preferred, same canonical-identity rule as the old TimelineScreen
 *     filter bar) with a suspend [PostsRepository.chatTitle] fallback
 *     for channels not yet in the merged feed.
 *     [channelSubscribers] uses [PostsRepository.channelSubscribers]
 *     which is a one-shot TDLib cache hit in steady state.
 *
 *   - Search is fully owned here — 300 ms debounce on [searchQuery],
 *     scoped to [chatId] via [PostsRepository.searchInChannel]. Results
 *     live in [searchResults]; the UI reads [searchActive] to decide
 *     which list to render.
 *
 * No need for `restoreFromSnapshot` / `refreshIfStale` here —
 * [PostsRepository.posts] is the single upstream and is populated by
 * [TimelineViewModel] via the feed bootstrap. [loadChannelHistory] gives
 * the deep per-channel slice the feed does not have on cold start.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val repo: PostsRepository,
    private val bookmarks: BookmarkStore,
    val chatId: Long,
    val scrollToMessageId: Long?,
) : ViewModel() {

    // Cold flow that filters the global posts stream down to this channel.
    // Used by every internal collector that needs the channel slice — the
    // live-ingest pump for [_data], the title/avatar resolver, etc. Cold
    // so each collector starts independently; `distinctUntilChanged`
    // avoids redundant work when an unrelated channel emits.
    private val channelSlice: Flow<PersistentList<TimelinePost>> = repo.posts
        .map { all -> all.filter { it.chatId == chatId }.toPersistentList() }
        .distinctUntilChanged()

    // Single source of truth for channel data state. Compose observes only
    // [data]; `posts` and `historyLoading` are derived from the snapshot
    // it returns (see [ChannelScreen]). That makes inconsistent
    // (posts, loading) pairs literally not representable.
    //
    // Seeded SYNCHRONOUSLY at VM construction:
    //   - Warm re-entry ([PostsRepository.hasWarmChannelHistory] == true):
    //     a previous successful [loadChannelHistory] is still within
    //     `DEEP_LOAD_COOLDOWN_MS`, so the in-memory slice already has the
    //     full head. Start as [ChannelData.Loaded] with the current slice
    //     — the LazyColumn paints content on frame one with no skeleton
    //     transition.
    //   - Cold first entry: start as [ChannelData.Loading]. The init
    //     block below kicks off [loadChannelHistory] and atomically
    //     transitions to [ChannelData.Loaded] with the freshly-loaded
    //     slice once it returns. No intermediate "1 cold-harvest post"
    //     state is ever visible to Compose.
    private val _data = MutableStateFlow<ChannelData>(initialData())
    val data: StateFlow<ChannelData> = _data.asStateFlow()

    private fun initialData(): ChannelData =
        if (repo.hasWarmChannelHistory(chatId)) {
            ChannelData.Loaded(channelSliceNow())
        } else {
            ChannelData.Loading
        }

    private fun channelSliceNow(): PersistentList<TimelinePost> =
        repo.posts.value.filter { it.chatId == chatId }.toPersistentList()

    // Deep-link around-load attempt flag. Starts false; flipped to true
    // by the init block after [repo.loadHistoryAround] resolves (or the
    // 1500 ms grace elapses). Consumed by [buildChannelUiState] — once
    // true and the target still isn't in the slice, the UI transitions
    // to [ChannelUiState.Missing] and falls back to index 0. No-op when
    // [scrollToMessageId] is null (no deep-link).
    private val _attemptedAround = MutableStateFlow(false)
    val attemptedAround: StateFlow<Boolean> = _attemptedAround.asStateFlow()

    // Pagination single-flight guard: prevents concurrent [loadOlder]
    // calls from a rapid-scroll listener (near-bottom LaunchedEffect
    // fires many times before the first call completes).
    private val _paginationLoading = MutableStateFlow(false)
    val paginationLoading: StateFlow<Boolean> = _paginationLoading.asStateFlow()

    // PTR in-flight state.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Channel header: title resolved from the post stream first (canonical
    // identity rule from TimelineScreen: channelContext?.name wins for
    // personal-author posts, else senderName, else TDLib chatTitle). Falls
    // back to a suspend chatTitle() lookup for channels not yet in the
    // merged feed (e.g. a deep link to a non-subscribed public channel
    // where [loadChannelHistory] hasn't completed yet).
    private val _channelTitle = MutableStateFlow<String?>(null)
    val channelTitle: StateFlow<String?> = _channelTitle.asStateFlow()

    // Subscriber count: seeded SYNCHRONOUSLY from [PostsRepository]'s
    // in-memory [TdApi.UpdateSupergroup] mirror so the [ChannelHeaderBar]
    // subtitle paints with the count on its first frame instead of as a
    // null that recomposes a beat later. For channels the user has already
    // seen in the merged feed (every non-deep-link entry point) both the
    // chat and supergroup updates have landed by the time this VM is
    // constructed, so the synchronous read returns the live count
    // immediately. The cold-cache fallback (deep-link into a never-seen
    // channel) is handled by the [init] launcher below.
    private val _channelSubscribers = MutableStateFlow(repo.channelSubscribersCached(chatId))
    val channelSubscribers: StateFlow<Int?> = _channelSubscribers.asStateFlow()

    // Channel avatar source for the top-bar TdAvatar — same minithumb /
    // fileId pair ChannelsScreen rows use. Resolved reactively from the
    // post stream (every channel post carries the channel's identity in
    // its sender fields) with a one-shot [PostsRepository.chatAvatar]
    // fallback for channels not yet in the merged feed. Until both
    // resolve, [TdAvatar] paints the initial-letter placeholder on its
    // primaryContainer disc.
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

    // Results: debounced + flatMapped so a new keystroke cancels the
    // in-flight RPC. Empty when search is inactive or query is blank.
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
        // OpenChat for the lifetime of this VM, with the history loads
        // issued inside the open window. Per TDLib (Aliaksei Levin /
        // [tdlib/td#2937] + the [TdApi.OpenChat] docstring):
        //
        //   "Informs TDLib that the chat is opened by the user. Many
        //    useful activities depend on the chat being opened or closed
        //    (e.g., in supergroups and channels all updates are received
        //    only for opened chats)."
        //
        // Without [PostsRepository.openChat], [TdApi.GetChatHistory] for
        // a channel the user hasn't viewed before is served from a cold
        // local cache and returns an empty list even though the server
        // has posts — the symptom was an "empty channel" hero on first
        // entry. OpenChat (a) primes TDLib's server-side history sync
        // for [chatId] so the very next GetChatHistory hits a warm
        // cache, and (b) flips this chat into the "updates streamed
        // here" set so new posts arrive live while the user reads
        // (UpdateNewMessage / UpdateMessageInteractionInfo).
        //
        // [PostsRepository.openChat] / [closeChat] route through
        // [ChatPresence], which refcounts per [chatId] so a concurrent
        // open from elsewhere (TimelineScreen's focus-chat tracker,
        // CommentsRepository.withOpenChat on a comments thread anchored
        // here) safely overlaps. The merged-feed focus tracker is gated
        // by `MainScaffold.coveredByOverlay`, so it releases its OpenChat
        // the moment ChannelScreen is mounted — Levin's canonical
        // "usually one chat opened" invariant (tdlib/td#2695) is
        // preserved.
        //
        // [CloseChat] runs under [NonCancellable] so a fast back-press
        // still flushes the close — same discipline TimelineScreen's
        // focus tracker and CommentsRepository use.
        //
        // History note: the pre-refactor TimelineScreen did this dance
        // inline (LaunchedEffect: openChat → loadChannelHistory →
        // awaitCancellation → closeChat); commit d2e3509 extracted the
        // screen but dropped the open/close pair. This block restores it.
        viewModelScope.launch {
            repo.openChat(chatId)
            try {
                // Cold-load: transition Loading → Loaded atomically once
                // [loadChannelHistory] returns. The slice is read from
                // [repo.posts.value] inside the finally block, so by the
                // time the [_data.value = Loaded(...)] write happens
                // the deep slice IS in the repository. Compose's next
                // recomposition sees [ChannelData.Loaded(80)] directly;
                // no intermediate [ChannelData.Loaded(staleSlice)]
                // emission is possible because [_data] is never written
                // with a partial slice — Loading is the only pre-load
                // state, and Loaded only ever holds the current full
                // slice. The whole race window between "posts updated"
                // and "loading flipped" that the old two-flow design
                // suffered from is gone by construction.
                //
                // Skipped when we're already Loaded (warm re-entry from
                // [initialData] — see [ChannelData] KDoc).
                if (_data.value is ChannelData.Loading) {
                    launch {
                        try {
                            repo.loadChannelHistory(chatId)
                        } finally {
                            _data.value = ChannelData.Loaded(channelSliceNow())
                        }
                    }
                }

                // Live-ingest: keep [_data] in sync with [repo.posts] for
                // the lifetime of the VM. Drops emissions while we're in
                // Loading so the screen doesn't paint a partial slice
                // before the cold-load transition above — once Loaded
                // lands, every subsequent slice change (UpdateNewMessage,
                // interaction updates, pagination, PTR) propagates as a
                // new [ChannelData.Loaded] value.
                launch {
                    channelSlice.collect { slice ->
                        val current = _data.value
                        if (current is ChannelData.Loaded && current.posts != slice) {
                            _data.value = ChannelData.Loaded(slice)
                        }
                    }
                }

                // Deep-link around-load: if the caller supplied a
                // scrollToMessageId, check whether the target is already
                // in the global feed slice (from the cold-start
                // harvest). If not, issue loadHistoryAround exactly
                // once. The repo returns `true` when the around-window
                // landed (the target post should reach the posts flow
                // shortly), `false` when the chat is inaccessible /
                // FLOOD_WAIT exhausted / permission revoked — no point
                // waiting on a post that will never come, so skip the
                // timeout and flip [_attemptedAround] immediately so
                // [buildChannelUiState] can transition to Missing.
                if (scrollToMessageId != null) {
                    launch {
                        val initialMatch = repo.posts.value.any { p ->
                            p.chatId == chatId &&
                                (p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds)
                        }
                        if (!initialMatch) {
                            val landed = runCatching {
                                repo.loadHistoryAround(chatId, scrollToMessageId)
                            }.getOrDefault(false)
                            if (landed) {
                                withTimeoutOrNull(1_500L) {
                                    repo.posts.first { all ->
                                        all.any { p ->
                                            p.chatId == chatId &&
                                                (p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds)
                                        }
                                    }
                                }
                            }
                        }
                        _attemptedAround.value = true
                    }
                }
                // Hold OpenChat for the screen's lifetime — TDLib keeps
                // streaming updates until CloseChat. The launches above
                // complete on their own; awaitCancellation lets them
                // finish then suspends here until viewModelScope is
                // cancelled.
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { repo.closeChat(chatId) }
            }
        }

        // Channel title: watch the post stream for a post whose
        // senderName / channelContext gives the canonical channel
        // identity; fall back to a one-shot chatTitle() for channels
        // not yet in the merged feed. Lazy-evaluated so we don't block
        // the VM constructor for a TDLib round-trip.
        viewModelScope.launch {
            // Fast synchronous path from the already-populated post stream.
            val anchor = repo.posts.value.firstOrNull { it.chatId == chatId }
            val titleFromPosts = anchor?.let { it.channelContext?.name ?: it.senderName }
            if (titleFromPosts != null) {
                _channelTitle.value = titleFromPosts
            } else {
                // Suspension fallback: TDLib serves this from its local
                // chat cache, which is warm after UpdateNewChat has
                // fired for this chat.
                _channelTitle.value = repo.chatTitle(chatId)
            }
            // Avatar minithumb/fileId from the same anchor when present.
            // Non-anonymous posts (admin posting under their own
            // identity, or as one of their other channels) carry the
            // AUTHOR's avatar in [avatarThumb]/[avatarFileId]; the host
            // channel's avatar lives in [channelContext]. Prefer the
            // channelContext when set so a channel where the latest
            // post happens to be non-anonymous doesn't surface the
            // admin's photo as the channel's own header avatar. Falls
            // back to [chatAvatar] one-shot for the cold-link case
            // where loadChannelHistory hasn't materialised the first
            // post yet.
            val avatarFromPosts = anchor?.let {
                (it.channelContext?.avatarFileId ?: it.avatarFileId) to
                    (it.channelContext?.avatarThumb ?: it.avatarThumb)
            }
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
            // Keep title AND avatar up-to-date as posts arrive (e.g.
            // non-subscribed channel whose first post lands after
            // loadChannelHistory completes, or a profile-photo change
            // pushed via UpdateChatPhoto downstream).
            //
            // Anchor preference: scan for ANY post whose channelContext
            // is populated so a string of non-anonymous posts at the
            // head doesn't drag the channel header into showing one
            // admin's avatar. Within a channel, every channelContext
            // refers to this same chat, so picking the first hit is
            // equivalent to picking the most recent — and it lets a
            // single channel-as-sender post anywhere in the slice
            // anchor the header.
            channelSlice.collect { channelPosts ->
                val anchor = channelPosts.firstOrNull() ?: return@collect
                val channelLike = channelPosts.firstNotNullOfOrNull { it.channelContext }
                val resolvedName = channelLike?.name ?: anchor.senderName
                if (resolvedName != _channelTitle.value) {
                    _channelTitle.value = resolvedName
                }
                val resolvedFileId = channelLike?.avatarFileId
                    ?: anchor.channelContext?.avatarFileId
                    ?: anchor.avatarFileId
                val resolvedThumb = channelLike?.avatarThumb
                    ?: anchor.channelContext?.avatarThumb
                    ?: anchor.avatarThumb
                if (resolvedFileId != _channelAvatarFileId.value) {
                    _channelAvatarFileId.value = resolvedFileId
                }
                if (resolvedThumb !== _channelAvatarThumb.value) {
                    _channelAvatarThumb.value = resolvedThumb
                }
            }
        }
        // Subscriber count cold-cache fallback. The synchronous seed
        // above covers every channel the merged feed has already
        // touched; this launcher runs the suspend variant only when
        // that returned null (deep-link into a never-seen channel;
        // freshly joined channel whose [TdApi.UpdateSupergroup] is
        // still in flight).
        if (_channelSubscribers.value == null) {
            viewModelScope.launch {
                _channelSubscribers.value = repo.channelSubscribers(chatId)
            }
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
     * Paginate older history for this channel. Guards against concurrent
     * calls — if a [loadOlder] is already in flight, the next call is a
     * no-op. The repository's own [PostsRepository.loadOlder] single-
     * flight handles the actual dedup, but adding the local guard here
     * avoids queuing dozens of redundant calls from the near-bottom
     * snapshotFlow.
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
         * How long after the last keystroke before issuing a
         * [PostsRepository.searchInChannel] RPC. Matches
         * [TimelineScreen.SEARCH_DEBOUNCE_MS] for consistent UX cadence.
         */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
