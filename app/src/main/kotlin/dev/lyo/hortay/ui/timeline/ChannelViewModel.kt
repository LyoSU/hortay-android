package dev.lyo.hortay.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.coroutines.withContext
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
 *     trip completes (or fails). Drives the [ChannelUiState.Resolving] gate so the
 *     LazyColumn only mounts once the deep slice has landed — avoids the
 *     "one-post-then-the-rest-pop-in" jump that the cold-start harvest used to cause.
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
    //
    // Initial value is seeded SYNCHRONOUSLY from [repo.posts.value] at VM
    // construction time, not the conventional empty `persistentListOf()`. That
    // matters because of the wait-for-content navigation path: [MainScaffold]'s
    // `pushChannel` awaits [repo.loadChannelHistory] (with a short timeout)
    // BEFORE pushing [NavEntry.Channel], so by the time this VM is constructed
    // the per-channel slice is already in `_posts` for any channel that has
    // surfaced in the merged feed (or just successfully pre-warmed by the
    // awaited prefetch). A stale empty initial would have [ChannelScreen]'s
    // first composition read `items = emptyList()` → `buildChannelUiState`
    // returns
    // Resolving → the Scaffold renders its background colour over a blank body
    // until upstream's first WhileSubscribed emission lands one frame later, at
    // which point the LazyColumn paints over it — visible as "спочатку біле
    // бачу, а потім зявляється пост". Seeding the StateFlow with the live
    // snapshot lets the first composition see the items the preload already
    // populated, and the LazyColumn paints content on frame one.
    val posts: StateFlow<PersistentList<TimelinePost>> = repo.posts
        .map { all -> all.filter { it.chatId == chatId }.toPersistentList() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            repo.posts.value.filter { it.chatId == chatId }.toPersistentList(),
        )

    // First-load guard: true from init until [loadChannelHistory] resolves — drives
    // [ChannelPreviewSkeleton] AND the [ChannelUiState.Resolving] gate in
    // [buildChannelUiState]. Reset to false regardless of success/failure so an
    // inaccessible channel doesn't freeze the screen on the skeleton forever.
    //
    // Seeded SYNCHRONOUSLY from [PostsRepository.hasWarmChannelHistory] so warm
    // re-entries (deep history already landed this session, cooldown still
    // active) land Ready on frame one — no Resolving flash, no blank skeleton
    // grace window. Cold first entries start true and stay there until the
    // deep load resolves, so the LazyColumn never paints with a sparse slice
    // that's about to be back-filled by older posts above the visible row
    // (the "stretching jump" symptom on first channel open from the feed:
    // cold-start [PostsRepository.refreshLocked] populates exactly one post
    // per channel from `Chat.lastMessage`, and without this gate the channel
    // mounted with that one post, then 79 older posts merged in above it
    // mid-scroll once [loadChannelHistory] returned).
    private val _historyLoading = MutableStateFlow(!repo.hasWarmChannelHistory(chatId))
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

    // Subscriber count: seeded SYNCHRONOUSLY from [PostsRepository]'s in-memory
    // [TdApi.UpdateSupergroup] mirror so the [ChannelHeaderBar] subtitle paints
    // with the count on its first frame instead of as a null that recomposes a
    // beat later. For channels the user has already seen in the merged feed
    // (every non-deep-link entry point) both the chat and supergroup updates
    // have landed by the time this VM is constructed, so the synchronous read
    // returns the live count immediately. The cold-cache fallback (deep-link
    // into a never-seen channel) is handled by the [init] launcher below.
    private val _channelSubscribers = MutableStateFlow(repo.channelSubscribersCached(chatId))
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
        // OpenChat for the lifetime of this VM, with the history loads issued
        // inside the open window. Per TDLib (Aliaksei Levin /
        // [tdlib/td#2937] + the [TdApi.OpenChat] docstring):
        //
        //   "Informs TDLib that the chat is opened by the user. Many useful
        //    activities depend on the chat being opened or closed (e.g., in
        //    supergroups and channels all updates are received only for
        //    opened chats)."
        //
        // Without [PostsRepository.openChat], [TdApi.GetChatHistory] for a
        // channel the user hasn't viewed before is served from a cold local
        // cache and returns an empty list even though the server has posts —
        // the symptom was an "empty channel" hero on first entry. OpenChat
        // (a) primes TDLib's server-side history sync for [chatId] so the
        // very next GetChatHistory hits a warm cache, and (b) flips this chat
        // into the "updates streamed here" set so new posts arrive live while
        // the user reads (UpdateNewMessage / UpdateMessageInteractionInfo).
        //
        // [PostsRepository.openChat] / [closeChat] route through [ChatPresence],
        // which refcounts per [chatId] so a concurrent open from elsewhere
        // (TimelineScreen's focus-chat tracker, CommentsRepository.withOpenChat
        // on a comments thread anchored here) safely overlaps. The merged-feed
        // focus tracker is gated by `MainScaffold.coveredByOverlay`, so it
        // releases its OpenChat the moment ChannelScreen is mounted — Levin's
        // canonical "usually one chat opened" invariant (tdlib/td#2695) is
        // preserved.
        //
        // [CloseChat] runs under [NonCancellable] so a fast back-press still
        // flushes the close — same discipline TimelineScreen's focus tracker
        // and CommentsRepository use.
        //
        // History note: the pre-refactor TimelineScreen did this dance inline
        // (LaunchedEffect: openChat → loadChannelHistory → awaitCancellation
        // → closeChat); commit d2e3509 extracted the screen but dropped the
        // open/close pair. This block restores it.
        viewModelScope.launch {
            repo.openChat(chatId)
            try {
                // First-load: deep-dive channel history so the single-channel
                // view has more than the one post per channel that the global
                // cold-start harvest provides. `historyLoading` clears
                // regardless of outcome — an inaccessible channel shows the
                // empty hero, not a frozen skeleton.
                //
                // Ordering barrier: between `loadChannelHistory` returning
                // and the `_historyLoading.value = false` write, we wait for
                // [posts] (the per-channel filtered StateFlow) to reflect
                // the just-loaded slice. Without this, Compose can observe
                // a frame where `historyLoading=false` but [posts] is still
                // on its stale pre-load value:
                //
                //   - `_posts.update` lands on the repo's Default-dispatcher
                //     coroutine, notifying subscribers.
                //   - [posts] is `repo.posts.map { filter }.stateIn(...)` —
                //     its collector runs in viewModelScope (Main.immediate)
                //     and updates the inner StateFlow asynchronously.
                //   - The outer `loadChannelHistory.await()` continuation
                //     ALSO resumes on viewModelScope's Main.immediate.
                //   - Both are queued on Main but as separate continuations.
                //     In practice the stateIn collector runs first (it was
                //     scheduled when `_posts.update` notified subscribers,
                //     before the deferred's `complete()` scheduled the
                //     awaiter), but coroutine scheduling does not formally
                //     guarantee that ordering.
                //
                // If `historyLoading=false` reaches Compose before [posts]
                // does, `buildChannelUiState` returns Ready(items=staleSlice,
                // initialIndex=lastIndex(staleSlice)=0 for the cold-harvest
                // single post). `reduceChannelUiState` then LATCHES that
                // initialIndex through subsequent Ready→Ready transitions
                // (the deep-link / scroll-pin contract), so even after
                // [posts] catches up to 80 items, the LazyColumn anchors at
                // index 0 — and in OldestUnreadFirst (asc-by-date) that's
                // the OLDEST post. From the user's POV: the new post they
                // tapped sits at the bottom (key-anchored by LazyColumn's
                // own machinery), and 78 older posts "appear above it" as
                // soon as Compose paints the freshly-grown list.
                //
                // The fix is structural: `posts.first { it.size >= expected }`
                // suspends this coroutine until the filtered slice reflects
                // the just-loaded data. Only then do we flip
                // `_historyLoading`, so Compose ALWAYS observes the (posts,
                // historyLoading) pair as consistent.
                launch {
                    try {
                        repo.loadChannelHistory(chatId)
                        val expectedCount = repo.posts.value.count { it.chatId == chatId }
                        posts.first { it.size >= expectedCount }
                    } finally {
                        _historyLoading.value = false
                    }
                }
                // Deep-link around-load: if the caller supplied a
                // scrollToMessageId, check whether the target is already in
                // the global feed slice (from the cold-start harvest). If
                // not, issue loadHistoryAround exactly once. The repo returns
                // `true` when the around-window landed (the target post
                // should reach the posts flow shortly), `false` when the chat
                // is inaccessible / FLOOD_WAIT exhausted / permission revoked
                // — no point waiting on a post that will never come, so skip
                // the timeout and flip [_attemptedAround] immediately so
                // [buildChannelUiState] can transition to Missing.
                if (scrollToMessageId != null) {
                    launch {
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
                // Hold OpenChat for the screen's lifetime — TDLib keeps
                // streaming updates until CloseChat. The two history launches
                // above complete on their own; awaitCancellation lets them
                // finish then suspends here until viewModelScope is cancelled.
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { repo.closeChat(chatId) }
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
            // Avatar minithumb/fileId from the same anchor when present. Non-anonymous
            // posts (admin posting under their own identity, or as one of their other
            // channels) carry the AUTHOR's avatar in [avatarThumb]/[avatarFileId]; the
            // host channel's avatar lives in [channelContext]. Prefer the channelContext
            // when set so a channel where the latest post happens to be non-anonymous
            // doesn't surface the admin's photo as the channel's own header avatar.
            // Falls back to [chatAvatar] one-shot for the cold-link case where
            // loadChannelHistory hasn't materialised the first post yet.
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
            // Keep title AND avatar up-to-date as posts arrive (e.g. non-subscribed
            // channel whose first post lands after loadChannelHistory completes,
            // or a profile-photo change pushed via UpdateChatPhoto downstream).
            //
            // Anchor preference: scan for ANY post whose channelContext is populated so
            // a string of non-anonymous posts at the head doesn't drag the channel
            // header into showing one admin's avatar. Within a channel, every
            // channelContext refers to this same chat, so picking the first hit is
            // equivalent to picking the most recent — and it lets a single
            // channel-as-sender post anywhere in the slice anchor the header.
            posts.collect { channelPosts ->
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
        // Subscriber count cold-cache fallback. The synchronous seed above
        // covers every channel the merged feed has already touched; this
        // launcher runs the suspend variant only when that returned null
        // (deep-link into a never-seen channel; freshly joined channel whose
        // [TdApi.UpdateSupergroup] is still in flight).
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
