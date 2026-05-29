@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lyo.hortay.R
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.isUnplayableVideo
import dev.lyo.hortay.data.orderedFor
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.channels.ChannelInfoSheet
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.rememberFloatingTopBarBehavior
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.text.LocalShowFullPost
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1 even though Flow.debounce(Duration) graduated.
// Remove only when the Long overload is stabilised upstream.

/**
 * Single-channel post feed. Replaces the `channelFilter != null` branch that used to
 * live inside [TimelineScreen] — each channel now gets a proper dedicated Composable
 * with its own [ChannelViewModel] instance (keyed on [chatId]) and its own list state,
 * so navigating between channels or back to the all-feed never shares stale scroll /
 * search / loading state.
 *
 * Top bar follows the same two-zone status-bar pattern as [TimelineScreen] and
 * [CommentsScreen]: a persistent background strip for the system status bar (zone 1),
 * then the floating-bar layout-shrinker (zone 2). [rememberFloatingTopBarBehavior]
 * with `enabled = { !searchActive }` keeps the bar pinned while the BasicTextField is
 * visible — identical semantics to the old in-channel search pin in [TimelineScreen].
 *
 * When [searchActive] is true, a Compact bar with a BasicTextField replaces the normal
 * Medium bar via a `when { }` switch — mirrors the pattern from [TimelineScreen]'s
 * [TimelineTopBar].
 *
 * Scroll-to-message, highlight, read-ack, pagination, and prefetch all follow the
 * corresponding [TimelineScreen] patterns verbatim; differences are called out in
 * their inline comments.
 *
 * @param onChannelOpen Called when the user taps a channel header, a forward-source
 *   chip, or an inline reply / quote card whose target is a DIFFERENT channel than the
 *   one currently displayed. The back-stack router in [MainScaffold] pushes the new
 *   chatId and creates a fresh [ChannelScreen]. The second parameter is the optional
 *   messageId to land on inside the destination channel — used by the cross-channel
 *   quote-tap path so the freshly pushed screen highlights the replied-to message
 *   instead of opening cold. Same-channel taps are no-ops (already here).
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ChannelScreen(
    chatId: Long,
    repo: PostsRepository,
    commentsRepo: CommentsRepository,
    translations: TranslationsStore,
    channelActions: ChannelActionsRepository,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenComments: (TimelinePost) -> Unit,
    onShowFullPost: (dev.lyo.hortay.data.TimelinePost, Int) -> Unit = { _, _ -> },
    onChannelOpen: (chatId: Long, scrollToMessageId: Long?) -> Unit,
    scrollToMessage: Pair<Long, Long>? = null,
    onScrollHandled: () -> Unit = {},
    onScrollMissed: () -> Unit = {},
    onReportClick: (TimelinePost) -> Unit = {},
    canReport: (TimelinePost) -> Boolean = { false },
    /**
     * Channel-level Report entry point — invoked when the user taps the Report row
     * inside [ChannelInfoSheet]. The scaffold routes it to the same ReportFlowSheet
     * the long-press path uses, with `messageId = null` (TDLib's reportChat flow
     * accepts a channel-level report against the whole chat). Null hides the row.
     */
    onReportChannel: (() -> Unit)? = null,
    /**
     * Hidden-channels store. When non-null, propagated to [ChannelInfoSheet]
     * which renders a "Hide from feed" toggle row. Optional so the screen
     * still composes from call sites that haven't been wired yet.
     */
    ignoredChannels: IgnoredChannelsStore? = null,
    /**
     * Per-user feed ordering, from [dev.lyo.hortay.data.SettingsStore.feedOrder]. Mirrors
     * the same setting [TimelineScreen] respects on the all-feed: [FeedOrder.Newest]
     * is the canonical newest-at-top arrangement; [FeedOrder.OldestUnreadFirst]
     * (the default) sorts ascending by date (oldest read posts on top, unread
     * queue below, newest at the bottom — chat-app idiom) and the cold-entry
     * effect below lands the user at the read→unread boundary so the channel
     * opens "where you left off".
     */
    feedOrder: FeedOrder = FeedOrder.OldestUnreadFirst,
    /**
     * Process-wide cold-start gate, TDLib mode only. While in
     * [StartupCoordinator.Phase.Booting] the comments-thread prefetch collector
     * silently skips its work to keep the TDLib RPC pipe clear for TDLib's own
     * initial sync — matches the gate [TimelineScreen] applies on the all-feed
     * path. A deep-link drill into a channel within the first ~3 s after auth
     * would otherwise bypass that budget. Null = unguarded (guest mode / tests).
     */
    startupPhase: kotlinx.coroutines.flow.StateFlow<dev.lyo.hortay.data.StartupCoordinator.Phase>? = null,
) {
    // Per-channel VM. viewModel() keys the instance by (class, key), so each chatId
    // gets its own VM rather than sharing the all-feed TimelineViewModel. The factory is
    // consulted only on first creation — once the VM is live, subsequent compositions
    // with the same key reuse the existing instance regardless of the factory parameter.
    val vm: ChannelViewModel = viewModel(
        key = "channel:$chatId",
        factory = remember(repo, bookmarks, chatId, scrollToMessage) {
            viewModelFactory {
                initializer {
                    ChannelViewModel(
                        repo = repo,
                        bookmarks = bookmarks,
                        chatId = chatId,
                        scrollToMessageId = scrollToMessage?.second,
                    )
                }
            }
        },
    )

    // Single-state read: `data` is the channel's `Loading | Loaded(posts)`
    // sealed union. `posts` is derived from `data` inside the same Compose
    // snapshot, so consumers can never observe an inconsistent (posts,
    // loading) pair across two state updates — see [ChannelData] KDoc for
    // the race the previous two-flow design exposed.
    val data by vm.data.collectAsStateWithLifecycle()
    val posts = when (val d = data) {
        is ChannelData.Loaded -> d.posts
        ChannelData.Loading -> kotlinx.collections.immutable.persistentListOf()
    }
    val attemptedAround by vm.attemptedAround.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val channelTitle by vm.channelTitle.collectAsStateWithLifecycle()
    val channelSubscribers by vm.channelSubscribers.collectAsStateWithLifecycle()
    val channelAvatarFileId by vm.channelAvatarFileId.collectAsStateWithLifecycle()
    val channelAvatarThumb by vm.channelAvatarThumb.collectAsStateWithLifecycle()
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()
    val searchActive by vm.searchActive.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val translationsMap = translations.translations.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val viewer = LocalMediaViewer.current

    // Info sheet: hoisted local state, dismissed by setting false.
    var infoSheetVisible by remember { mutableStateOf(false) }

    // Search-mode back-handler. When search is active, system back / predictive-back
    // should collapse the search overlay back to the channel's normal Medium top bar
    // — NOT pop the channel itself off the back-stack. Without this BackHandler, the
    // gesture bubbles up to MainScaffold's nav-stack popNav() and yanks the
    // user out to the originating tab (typically Channels), losing both the search
    // and the channel context. Composable-local BackHandler near the leaf takes
    // priority over parent BackHandlers, which is exactly the dispatch rule we need.
    BackHandler(enabled = searchActive) { vm.setSearchActive(false) }

    // Pinned-only top bar on the channel detail surface. Standard M3 pinned
    // behaviour keeps the surface tint reactive to scroll without moving the
    // bar — the header stays visible at all times so "where am I" + search
    // affordance never become hidden gestures.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // Source-of-truth for what the LazyColumn renders. One TimelinePost → one
    // [FeedItem] row — see [FeedItem] for why row identity is kept 1:1 with the
    // backing post instead of folding reply chains into stacked Thread slots.
    // [feedOrder] is honoured here so OldestUnreadFirst flips the channel into
    // the reverse-feed layout exactly like TimelineScreen does on the all-feed;
    // search results stay in their RPC relevance order regardless.
    val cursorHolder = LocalReadCursors.current

    // Recency floor for OldestUnreadFirst boundary placement — mirrors the
    // merged-feed wiring in [TimelineScreen]. Captured once per mount so the
    // cutoff is stable across recompositions; on re-entry the channel
    // re-samples, which is correct because "recent" is defined relative to
    // when the user opened this channel.
    val recencyCutoffMs = remember { System.currentTimeMillis() - BOUNDARY_RECENCY_WINDOW_MS }

    // Frozen cursor snapshot + epoch counter for the boundary row. Mirrors the
    // pattern in [TimelineScreen]; the channel-screen analogue rotates on the
    // discrete session-anchor events:
    //   1. Channel identity / feedOrder change (`remember` key list below).
    //   2. Pull-to-refresh completion (`refreshing` falls true → false).
    // Per-card dwell-acks update the LIVE [cursorHolder] but neither rotate
    // the latched snapshot nor advance the epoch — so the [FeedItem.Boundary]
    // divider row keeps a stable LazyColumn identity ("here is where you came
    // in"), not a live read-edge that migrates under the user's scroll. See
    // [TimelineScreen]'s `boundaryCursorsState` KDoc for the full rationale —
    // same shape, same reasoning, different rotation triggers (no cold-start
    // cursor-settle dance: a channel only opens once cursors are live).
    val boundaryCursorsState = remember(chatId, feedOrder) {
        mutableStateOf(cursorHolder.snapshot())
    }
    val cursorEpochState = remember(chatId, feedOrder) {
        mutableLongStateOf(0L)
    }
    LaunchedEffect(chatId, feedOrder, refreshing) {
        // Re-latch only on PTR-completion edges (refreshing : true → false).
        // The initial latch happens at `remember` construction above, so the
        // first frame already has the right snapshot; this effect rotates on
        // explicit refresh round-trips that ask for a fresh view of the world.
        snapshotFlow { refreshing }
            .drop(1)
            .filter { !it }
            .collect {
                boundaryCursorsState.value = cursorHolder.snapshot()
                cursorEpochState.longValue = cursorEpochState.longValue + 1L
            }
    }
    val boundaryCursors = boundaryCursorsState.value
    val cursorEpoch = cursorEpochState.longValue

    val displayedItems = remember(
        posts, searchActive, searchResults, feedOrder,
        boundaryCursors, cursorEpoch, recencyCutoffMs,
    ) {
        val source = if (searchActive) searchResults else posts.orderedFor(feedOrder)
        val postItems = source.map(FeedItem::Post)
        val withDivider = if (!searchActive && feedOrder == FeedOrder.OldestUnreadFirst) {
            withBoundary(
                items = postItems,
                cursors = boundaryCursors,
                order = feedOrder,
                epoch = cursorEpoch,
                recencyCutoffMs = recencyCutoffMs,
            )
        } else {
            postItems
        }
        withDivider.toPersistentList()
    }

    // [ChannelUiState] is the single source of truth for what gets mounted on
    // this screen. The VM owns the deep-link around-load — when the candidate
    // resolves to [ChannelUiState.Resolving], the channel paints a [SkeletonFeed]
    // instead of flashing the head post at index 0 for a frame before the deep
    // link lands. On [ChannelUiState.Missing] the screen falls back to the
    // normal newest-first view and surfaces a snackbar via [onScrollMissed].
    //
    // Cursors flow through [boundaryCursors] above — same latched snapshot the
    // [FeedItem.Boundary] divider uses, so [continueReadingIndex] inside
    // [buildChannelUiState] and the divider always agree on the read/unread
    // split. The cold-start cursor-settle dance from [TimelineScreen] is not
    // needed here: by the time a channel is opened, cursors are live and the
    // initial `remember` seed already captures them.
    val candidateChannelUiState = buildChannelUiState(
        data = data,
        items = displayedItems,
        scrollToMessageId = vm.scrollToMessageId,
        attemptedAround = attemptedAround,
        searchActive = searchActive,
        chatId = chatId,
        feedOrder = feedOrder,
        cursors = boundaryCursors,
        recencyCutoffMs = recencyCutoffMs,
    )
    val channelUiState = rememberLatchedChannelUiState(
        candidate = candidateChannelUiState,
        routeKey = chatId,
    )
    // Notify the host that the deep-link request has been consumed by the VM —
    // ChannelViewModel reads [scrollToMessage] from its constructor and drives
    // the around-load itself. Fires once on first composition with a non-null
    // request, matching the previous LaunchedEffect(scrollToMessage) contract.
    LaunchedEffect(scrollToMessage) {
        if (scrollToMessage != null) onScrollHandled()
    }
    // Missing → snackbar via the existing scaffold-wide route. Single fire per
    // latched Missing transition; reusing [onScrollMissed] keeps the snackbar
    // plumbing untouched (the host already surfaces a localized message and
    // dedups against concurrent posts).
    LaunchedEffect(channelUiState) {
        if (channelUiState is ChannelUiState.Missing) onScrollMissed()
    }

    // [highlightedPostKey] has two producers:
    //   1. Deep-link landing: derived from the latched [Ready.highlightedMessageId]
    //      so the target pulses after the LazyColumn mounts at the resolved index.
    //   2. In-channel quote-tap: [onQuotedSourceClick] sets [pendingScrollToMessage]
    //      below; [rememberPendingScrollToMessage] resolves it and writes the key
    //      on its [onLanded] callback.
    // Newest-mode (no deep link) and the Missing fallback produce null on path 1.
    // Auto-clear after CHANNEL_HIGHLIGHT_DURATION_MS for both paths.
    var highlightedPostKey by remember(chatId) { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(channelUiState, chatId) {
        val mid = (channelUiState as? ChannelUiState.Ready)?.highlightedMessageId ?: return@LaunchedEffect
        highlightedPostKey = chatId to mid
    }
    LaunchedEffect(highlightedPostKey) {
        if (highlightedPostKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(CHANNEL_HIGHLIGHT_DURATION_MS)
        highlightedPostKey = null
    }
    // In-channel quote-tap pending target. Deep-link scroll is owned by the VM
    // (see [ChannelViewModel.scrollToMessageId] + [attemptedAround] → builder
    // gates Ready behind it), so this state holds ONLY the quoted-message
    // jump path — same-channel reply navigation from a [PostInteractions.onQuotedSourceClick]
    // tap. [rememberPendingScrollToMessage] resolves the target and clears it.
    var pendingScrollToMessage by remember(chatId) { mutableStateOf<Pair<Long, Long>?>(null) }

    // Scroll state. First paint lands at the correct row in one frame
    // (cold-start landing) AND drill-out/drill-in preserves user scroll, even
    // when boundary moved while the user was elsewhere. The trick: pin the
    // seed at the FIRST Ready transition via [rememberSaveable], use it as
    // both the [LazyListState] constructor arg and the [rememberSaveable] key
    // — so subsequent boundary movements don't yank the saver bundle out from
    // under the restoration path. The previous design keyed on the LIVE
    // boundary, which lost scroll on drill-back if cursors had moved while
    // the user was inside another channel. See the matching pattern in
    // [TimelineScreen]'s home-feed listState — same problem, same shape.
    val pinnedChannelSeed = rememberSaveable(chatId) {
        androidx.compose.runtime.mutableIntStateOf(-1)
    }
    val candidateInitialIndex = (channelUiState as? ChannelUiState.Ready)?.initialIndex
    LaunchedEffect(chatId, candidateInitialIndex) {
        if (pinnedChannelSeed.intValue < 0 && candidateInitialIndex != null) {
            pinnedChannelSeed.intValue = candidateInitialIndex
        }
    }
    val initialIndexSeed = when {
        pinnedChannelSeed.intValue >= 0 -> pinnedChannelSeed.intValue
        candidateInitialIndex != null -> candidateInitialIndex
        else -> 0
    }
    val listState = rememberSaveable(
        chatId, initialIndexSeed,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver,
    ) {
        androidx.compose.foundation.lazy.LazyListState(initialIndexSeed, 0)
    }

    // Resolve in-channel quote-tap scroll-to-message once the target row appears.
    // Shared with [TimelineScreen]; deep-link landings DO NOT use this path — VM
    // owns those. On miss the helper invokes [onScrollMissed] so the host posts a
    // "link not found" snackbar instead of leaving the user staring at nothing.
    rememberPendingScrollToMessage(
        displayedItems = displayedItems,
        pendingTarget = pendingScrollToMessage,
        loadHistoryAround = { cid, mid -> repo.loadHistoryAround(cid, mid) },
        onLanded = { cid, mid, idx ->
            highlightedPostKey = cid to mid
            // Top-aligned landing — tall posts must show the header. Plain
            // scrollToItem(idx, 0) bottom-anchors in reverseLayout and clips the
            // header above the viewport. See [scrollToTopAligned] KDoc.
            listState.scrollToTopAligned(idx)
            pendingScrollToMessage = null
        },
        onMissed = {
            pendingScrollToMessage = null
            onScrollMissed()
        },
    )

    // Pagination: near-older-edge snapshotFlow → VM.loadOlderIfPossible().
    // Post data is always descending (newest = index 0, oldest = index total-1), so
    // older history is always at the HIGH-index end regardless of reverseLayout.
    // A single `last >= total - threshold` condition covers both feed orders.
    // (The previous per-order branch with `first <= threshold` for OldestUnreadFirst
    // was an ascending-layout artifact; see git history for the runaway-pagination
    // bug it caused.)
    LaunchedEffect(listState, chatId, feedOrder) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(total, firstVisible, lastVisible)
        }
            .distinctUntilChanged()
            .collect { (total, first, last) ->
                if (total == 0 || first < 0 || last < 0) return@collect
                // Older history is always the high-index (oldest) end of the
                // descending data, independent of reverseLayout — one condition
                // for both orders.
                val nearOlderEdge = shouldLoadOlder(first, last, total, CHANNEL_PAGINATION_THRESHOLD)
                if (nearOlderEdge) {
                    vm.loadOlderIfPossible()
                }
            }
    }

    // Read-state acks: viewport-stable dwell → viewMessages. Extracted to
    // [rememberReadAckDwell] — shared with [TimelineScreen]. Scoped to chatId. The
    // returned set is the same one [markPostReadState] below extends with
    // explicit-tap acks.
    val ackedRead = rememberReadAckDwell(
        listState = listState,
        displayedItems = displayedItems,
        ackKey = chatId,
        markAsRead = { fresh ->
            fresh.groupBy { it.chatId }.forEach { (cid, group) ->
                // Expand albums to every member id so TDLib's
                // lastReadInboxMessageId advances past the highest member,
                // matching the explicit-tap path below ([markPostReadState]).
                val ids = group.flatMap { post ->
                    post.albumMessageIds.ifEmpty { listOf(post.id) }
                }.distinct()
                vm.viewMessages(cid, ids)
            }
        },
        scope = scope,
        dwellMs = CHANNEL_READ_DWELL_MS,
    )

    // Comments-thread prefetch — extracted to [rememberCommentsPrefetch], shared
    // with [TimelineScreen]. Same cap (1) and debounce (1200 ms); same cold-start
    // gate so a deep-link drill in the first ~3 s after auth doesn't bypass the
    // post-auth RPC budget.
    rememberCommentsPrefetch(
        listState = listState,
        displayedItems = displayedItems,
        startupPhase = startupPhase,
        prefetchThread = commentsRepo::prefetchThread,
        debounceMs = CHANNEL_PREFETCH_DEBOUNCE_MS,
        maxConcurrent = CHANNEL_COMMENTS_PREFETCH_LIMIT,
    )

    // --- State wrappers for lambdas (same rememberUpdatedState pattern as TimelineScreen) ---
    val bookmarkedState = rememberUpdatedState(bookmarkedKeys)
    val translationsState = rememberUpdatedState(translationsMap)
    val onChannelOpenState = rememberUpdatedState(onChannelOpen)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)
    val onShowFullState = rememberUpdatedState(onShowFullPost)
    val markPostReadState = rememberUpdatedState({ post: TimelinePost ->
        val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
        val unacked = ids.filter { (post.chatId to it) !in ackedRead }
        if (unacked.isNotEmpty()) {
            unacked.forEach { ackedRead.add(post.chatId to it) }
            scope.launch { vm.viewMessages(post.chatId, unacked) }
        }
    })

    // Translation lookup helper — same album-scan fallback as TimelineScreen.
    fun lookupTranslation(post: TimelinePost): dev.lyo.hortay.data.FormattedText? {
        val map = translationsState.value
        val lang = translations.currentTargetLanguage()
        map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, post.id, lang)]?.let { return it }
        post.albumMessageIds.forEach { id ->
            map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, id, lang)]?.let { return it }
        }
        return null
    }

    // PostInteractions — keyed on the long-lived dependencies to avoid stale captures
    // across logout/login, mirrors TimelineScreen's keying rationale.
    val interactions = remember(vm, viewer, translations, channelActions, repo, bookmarks, onReportClick, canReport) {
        PostInteractions(
            onMediaClick = { post, idx ->
                markPostReadState.value(post)
                val items = (post.content as? dev.lyo.hortay.data.PostContent.PhotoAlbum)?.items.orEmpty()
                if (items.getOrNull(idx)?.isUnplayableVideo == true) {
                    scope.launch { PostActions.openInTelegram(context, repo, post) }
                } else {
                    viewer.openFor(post.content, idx)
                }
            },
            onChannelClick = { post ->
                // Same-channel tap: already here, no-op. Different-channel: drill in.
                if (post.chatId != chatId) onChannelOpenState.value(post.chatId, null)
            },
            onAuthorChatClick = { id ->
                // Foreign-chat-as-sender header tap. Same-id is impossible (the
                // mapper only sets `senderChatId` when it differs from the host),
                // but stay defensive — drill in only when distinct.
                if (id != chatId) onChannelOpenState.value(id, null)
            },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceChatId
                    is ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is ForwardOrigin.Channel -> origin.sourceHandle
                    is ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                // Only Channel origins carry a permalink message id — group/chat
                // forwards have no per-message anchor on the TDLib side.
                val sourceMessageId = (origin as? ForwardOrigin.Channel)?.sourceMessageId
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId, sourceMessageId)
                    !sourceHandle.isNullOrBlank() -> {
                        // Username-only: route through HortayUriHandler so the public
                        // channel handle resolves via SearchPublicChat. Append the
                        // message id when available so the resolver can drill straight
                        // to the original post.
                        val handle = sourceHandle.removePrefix("@")
                        val url = if (sourceMessageId != null) "https://t.me/$handle/$sourceMessageId"
                            else "https://t.me/$handle"
                        uriHandler.openUri(url)
                    }
                }
            },
            onQuotedSourceClick = { post ->
                post.reply?.let { r ->
                    // `replyToChatId` is normalised at the mapping boundary
                    // ([MessageMapper.mapReply]): TDLib's "unknown chat"
                    // sentinel `chat_id = 0` for same-chat replies is rewritten
                    // to the host post's own chatId before it reaches the UI.
                    if (r.replyToChatId == chatId) {
                        // Same channel: queue an in-place scroll to the target message.
                        pendingScrollToMessage = chatId to r.replyToMessageId
                    } else {
                        // Different channel: drill in WITH the replied-to messageId
                        // baked into the new NavEntry.Channel — the freshly mounted
                        // ChannelScreen lands at the target and pulses the highlight
                        // there. Without the messageId the new screen would open cold
                        // (newest-first) and the user would have to scroll-hunt for
                        // the thing they tapped on.
                        onChannelOpenState.value(r.replyToChatId, r.replyToMessageId)
                    }
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> scope.launch { PostActions.share(context, repo, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post ->
                markPostReadState.value(post)
                scope.launch { PostActions.openInTelegram(context, repo, post) }
            },
            onTranslateClick = { post ->
                scope.launch {
                    val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                    translations.translate(post.chatId, ids.first())
                }
            },
            onClearTranslationClick = { post ->
                val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                ids.forEach { translations.clear(post.chatId, it) }
            },
            isTranslated = { post -> lookupTranslation(post) != null },
            translationFor = ::lookupTranslation,
            translateEnabled = true,
            onReactionToggle = { post, item ->
                // Optimistic UI: flip the chip first via [PostsRepository]
                // ([TimelineScreen.onReactionToggle] uses the same pattern), then
                // dispatch the RPC, then revert on failure. Keeps the channel-drill
                // tap latency identical to the feed.
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                val nowChosen = !item.isChosen
                repo.applyOptimisticReaction(post.chatId, target, item.kind, nowChosen)
                scope.launch {
                    val ok = channelActions.toggleReaction(
                        chatId = post.chatId,
                        messageId = target,
                        kind = item.kind,
                        isChosen = item.isChosen,
                    )
                    if (!ok) repo.applyOptimisticReaction(post.chatId, target, item.kind, item.isChosen)
                }
            },
            availableReactions = { post ->
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                channelActions.availableReactions(post.chatId, target)
            },
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
            },
            onShowFull = { post, off ->
                markPostReadState.value(post)
                onShowFullState.value(post, off)
            },
            // See [TimelineScreen.onPollVote] for the full rationale of the optimistic-flip
            // → RPC → clearPending pattern.
            onPollVote = { post, indices ->
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                repo.applyOptimisticPollAnswer(post.chatId, target, indices)
                scope.launch {
                    val ok = channelActions.setPollAnswer(post.chatId, target, indices)
                    repo.clearPollPending(post.chatId, target, revert = !ok)
                }
            },
            pollVotingEnabled = true,
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
            onReportClick = onReportClick,
            canReport = canReport,
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Pinned: status-bar strip + ChannelTopBar in a Column. No layout
            // shrinker, no nested-scroll offset — the bar stays fully visible
            // throughout the user's scroll. The status-bar strip continues to
            // own the system-bar inset so we never double-pad.
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars),
                )
                ChannelTopBar(
                    channelTitle = channelTitle,
                    channelSubscribers = channelSubscribers,
                    channelAvatarFileId = channelAvatarFileId,
                    channelAvatarThumb = channelAvatarThumb,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onBack = onBack,
                    onSearchToggle = { vm.setSearchActive(!searchActive) },
                    onSearchQueryChange = { vm.setSearchQuery(it) },
                    onTitleTap = { infoSheetVisible = true },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                // Render gate. The latched [ChannelUiState] is the single source of
                // truth for what mounts:
                //   • Resolving → [SkeletonFeed]: history-loading or deep-link
                //     around-load in flight. No LazyColumn = no flash of the
                //     channel's head post before the deep-link target lands.
                //   • Missing   → snackbar is fired by the LaunchedEffect above;
                //     the LazyColumn renders the normal newest-first view at
                //     index 0 so the user isn't stuck on a skeleton.
                //   • Ready     → LazyColumn mounted at [Ready.initialIndex], so
                //     deep-link / OldestUnreadFirst landings hit the correct row
                //     in one frame.
                val displayedList = when (channelUiState) {
                    is ChannelUiState.Ready -> channelUiState.items
                    ChannelUiState.Missing -> displayedItems
                    ChannelUiState.Resolving -> displayedItems
                }
                val isResolving = channelUiState is ChannelUiState.Resolving
                // Anti-flicker grace for the resolving-state skeleton. Most
                // channel entries land Ready in 50-200 ms — local-cache
                // history when the channel has surfaced in the merged feed,
                // helped by `pushChannel` which awaits
                // [PostsRepository.loadChannelHistory] before pushing
                // [NavEntry.Channel]. Without a grace window
                // [SkeletonFeed] paints for one or two frames and unmounts
                // — read as flicker. Gated on [SCREEN_MOUNT_GRACE_MS]
                // (120 ms) so fast resolves paint zero skeleton; only
                // genuinely slow opens (cold deep-link, FLOOD_WAIT, post-DC
                // migration) cross the threshold and surface feedback.
                // Animation-duration-scale aware via
                // [effectiveSkeletonGrace] inside `rememberDeferredLoading`:
                // when the user has disabled animations the grace becomes 0
                // and the skeleton paints on the first Resolving frame —
                // there's no transition to hide behind.
                val showSkeleton = isResolving && rememberDeferredLoading(
                    pending = isResolving,
                    key = chatId,
                    graceMs = dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS,
                )
                when {
                    showSkeleton -> {
                        SkeletonFeed(modifier = Modifier.fillMaxSize())
                    }
                    isResolving -> {
                        // Inside the grace window: hold the body blank rather
                        // than flashing [SkeletonFeed] or falling through to
                        // [ChannelEmptyState]. The header has already painted
                        // (title + subtitle reserve their slot regardless of
                        // resolve state), so the user sees a continuous
                        // "channel is opening" frame, not a flash of the wrong
                        // affordance. If resolve completes before the grace
                        // elapses the body transitions straight to Ready.
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    displayedList.isEmpty() && !refreshing -> {
                        // [ChannelData.Loading] is exhausted by the Resolving gate
                        // above — when this branch fires the channel has resolved
                        // Ready with an empty slice (genuinely empty channel /
                        // search miss), never a load-in-flight state.
                        when {
                            searchActive && searchQuery.isNotBlank() -> ChannelSearchEmpty()
                            else -> ChannelEmptyState()
                        }
                    }
                    else -> {
                        // Cold-entry top-align reveal (reverseLayout only). The
                        // LazyColumn mounts seeded at `initialIndex`, which under
                        // reverseLayout glues that item's BOTTOM to the viewport
                        // bottom — for the boundary case the divider + unread
                        // queue would be stranded off-screen below; for the
                        // deep-link case (channel-name tap landing at a specific
                        // post) a TALL target would have its header clipped
                        // above the viewport and the user would see the bottom
                        // of the post. The reveal keeps the [SkeletonFeed] cover
                        // painted while the list measures the target row's
                        // height underneath, then one instant measured-delta
                        // ([topAlignDelta]) reposition lands it at the
                        // viewport top with no wrong-frame flash.
                        //
                        // Enabled for BOTH boundary and deep-link landings —
                        // both share the bottom-anchored cold-mount problem and
                        // the same fix. Newest mode skips the reveal because
                        // forward layout's scrollOffset=0 already top-aligns.
                        // See [rememberBoundaryReveal] for the reveal mechanics.
                        val readyState = channelUiState as? ChannelUiState.Ready
                        val boundaryRevealed = rememberBoundaryReveal(
                            listState = listState,
                            boundaryIndex = readyState?.initialIndex ?: 0,
                            enabled = feedOrder.reverseLayout,
                            routeKey = chatId,
                        )
                        // Scroll gate: defer media ensure() while the list is scrolling.
                        // See TimelineScreen for reasoning; identical gate, same intent.
                        val scrollGate = remember(listState) {
                            derivedStateOf { !listState.isScrollInProgress }
                        }

                        // Viewport-centre priority key for the dominant card — same
                        // VisibleCenter promotion as TimelineScreen.
                        // Kept as State<Any?> so per-item subscribers read the
                        // value inside their own derivedStateOf — see
                        // TimelineScreen.TimelineFeedColumn for the full
                        // rationale (avoids invalidating every items() body
                        // on every centre flip during fling).
                        val centeredItemKeyState: androidx.compose.runtime.State<Any?> =
                            remember(listState) {
                                derivedStateOf {
                                    val info = listState.layoutInfo
                                    val visible = info.visibleItemsInfo
                                    if (visible.isEmpty()) return@derivedStateOf null
                                    val viewportCenter =
                                        (info.viewportStartOffset + info.viewportEndOffset) / 2
                                    visible.minByOrNull { item ->
                                        val itemCenter = item.offset + item.size / 2
                                        kotlin.math.abs(itemCenter - viewportCenter)
                                    }?.key
                                }
                            }

                        // Eager prefetch: warm [CHANNEL_PREFETCH_AHEAD] posts ahead of
                        // the viewport at [DownloadPriority.Prefetch] (lane 8). Visible
                        // posts self-ensure at VisibleMedia (16) via rememberMediaBinding,
                        // so the priority gap prevents LIFO contention on the TDLib pool —
                        // same rationale as TimelineScreen's prefetch block.
                        val cache = LocalMediaCache.current
                        val prefetchAnchor by remember(listState) {
                            derivedStateOf {
                                if (listState.isScrollInProgress) null
                                else listState.firstVisibleItemIndex
                            }
                        }
                        LaunchedEffect(prefetchAnchor, displayedList) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= displayedList.size) return@LaunchedEffect
                            val end = (firstVisible + CHANNEL_PREFETCH_AHEAD)
                                .coerceAtMost(displayedList.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val item = displayedList.getOrNull(idx) ?: continue
                                for (post in item.posts()) {
                                    for (fileId in post.content.posterFileIds()) {
                                        cache.ensure(fileId, DownloadPriority.Prefetch)
                                    }
                                    if (idx == firstVisible + 1) {
                                        for (fileId in post.content.playbackFileIds()) {
                                            cache.ensure(fileId, DownloadPriority.Prefetch)
                                        }
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalScrollGate provides scrollGate) {
                            LazyColumn(
                                state = listState,
                                reverseLayout = feedOrder.reverseLayout,
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = displayedList,
                                    key = { it.key },
                                    contentType = { item ->
                                        when (item) {
                                            is FeedItem.Boundary -> "boundary"
                                            is FeedItem.Post -> "post"
                                        }
                                    },
                                ) { item ->
                                    when (item) {
                                        is FeedItem.Boundary -> UnreadBoundaryRow()
                                        is FeedItem.Post -> {
                                            val isCenteredState = remember(item.key) {
                                                derivedStateOf { centeredItemKeyState.value == item.key }
                                            }
                                            val post = item.post
                                            val highlighted = highlightedPostKey?.let { (cid, mid) ->
                                                post.chatId == cid && (post.id == mid || mid in post.albumMessageIds)
                                            } == true
                                            // Captured ABSOLUTE screen-Y of this card's top (see
                                            // TimelineFeedColumn for the rationale) — tap / "Показати
                                            // більше" pin the full-post hero to this exact Y.
                                            val topY = remember(item.key) { floatArrayOf(0f) }
                                            val showFull = remember(post, interactions, topY) {
                                                { interactions.onShowFull(post, topY[0].toInt()) }
                                            }
                                            val itemInteractions = remember(post, interactions, topY) {
                                                interactions.copy(onPostClick = { interactions.onShowFull(post, topY[0].toInt()) })
                                            }
                                            CompositionLocalProvider(
                                                LocalIsCenteredItem provides isCenteredState,
                                                LocalIsHighlightedItem provides highlighted,
                                                LocalShowFullPost provides showFull,
                                            ) {
                                                Box(modifier = Modifier.onGloballyPositioned { topY[0] = it.positionInWindow().y }) {
                                                    PostCard(post = post, interactions = itemInteractions)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Cover the seeded-at-bottom mount until the boundary is
                        // repositioned (see [rememberBoundaryReveal]). Reuses the
                        // same SkeletonFeed the Resolving gate shows, so the
                        // skeleton → content transition is the one users already
                        // know — no new affordance, no wrong-frame flash.
                        if (!boundaryRevealed) {
                            SkeletonFeed(modifier = Modifier.fillMaxSize())
                        }
                        }
                    }
                }
            }
        }
    }

    if (infoSheetVisible) {
        ChannelInfoSheet(
            chatId = chatId,
            actions = channelActions,
            onDismiss = { infoSheetVisible = false },
            onReport = onReportChannel,
            ignoredChannels = ignoredChannels,
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * Top bar for the channel screen. Two modes driven by [searchActive]:
 *
 *   - Normal ([searchActive] = false): Medium-size bar with avatar + title row
 *     in the title slot and subscriber count as subtitle. Navigation icon = back.
 *     Actions: search toggle, info.
 *
 *   - Search ([searchActive] = true): Compact bar with a BasicTextField in the title
 *     slot (auto-focuses on mount) and a clear icon when the query is non-empty.
 *     Navigation icon = back (also closes search). Mirrors the in-channel search
 *     bar that previously lived in TimelineScreen's TimelineTopBar.
 *
 * Window insets are [WindowInsets.Zero] here because the caller ([ChannelScreen]) already
 * owns a persistent zone-1 status-bar strip above this composable — passing zero
 * prevents double-padding (same contract as TimelineScreen's TimelineTopBar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelTopBar(
    channelTitle: String?,
    channelSubscribers: Int?,
    channelAvatarFileId: Int?,
    channelAvatarThumb: ByteArray?,
    searchActive: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTitleTap: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val barInsets = WindowInsets(0)
    // M3E motion: search-mode swap rides MotionScheme spring instead of a hard
    // instant snap. Both bar variants are the same Compact size (64 dp), so there
    // is no height delta to negotiate — just the content inside the title slot
    // crossfades. Crossfade (vs AnimatedContent) is the right primitive here:
    // no enter/exit slide, no SizeTransform, just an alpha swap. Matches the
    // FloatingNavBar / ConnectionBanner motion vocabulary.
    androidx.compose.animation.Crossfade(
        targetState = searchActive,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "channel-bar-search-swap",
    ) { isSearch ->
        // Both variants render as the canonical M3 Compact (Small) top app bar
        // — 64 dp, single row. This is the right typeform for a chat-detail
        // surface per Material 3 guidance: Medium / Large Flexible bars are for
        // top-level destinations with prominent brand identity (Feed, Settings),
        // while a channel screen is a secondary detail surface that reads as a
        // sibling of every other "open a thing, see its content" navigation
        // step (CommentsScreen, future post-detail). Mirrors Telegram-Android
        // and X / Twitter chat-screen header sizing.
        if (isSearch) {
            HortayTopBar(
                size = HortayTopBarSize.Compact,
                title = {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.timeline_search_in_channel),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                },
                navigationIcon = {
                    // Search-mode back-arrow collapses the search overlay back to
                    // the normal channel header — it does NOT pop the channel
                    // off the back-stack. Standard chat-search UX (TG / X / Gmail
                    // all do this) — back-arrow + search-overlay = close overlay,
                    // back-arrow + normal-state = pop screen. The system-back
                    // gesture is wired the same way via the leaf-scoped
                    // BackHandler(enabled = searchActive) in [ChannelScreen].
                    IconButton(onClick = onSearchToggle) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Symbol(
                                name = "close",
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = barInsets,
            )
        } else {
            val subtitleText = channelSubscribers?.let {
                stringResource(R.string.timeline_subscribers, formatSubscribers(it))
            }
            // Telegram / X chat-screen header convention: avatar + name on the
            // first line, subscribers on the second, entire title row tappable
            // for the info sheet. Shared with the guest-mode WebChannelScreen
            // via [ChannelHeaderBar] — single source of truth for chat-screen
            // chrome so any visual tweak lands in both modes together.
            ChannelHeaderBar(
                titleText = channelTitle.orEmpty(),
                subtitleText = subtitleText,
                avatar = ChannelHeaderAvatar.Td(
                    fileId = channelAvatarFileId,
                    thumb = channelAvatarThumb,
                    name = channelTitle ?: "?",
                ),
                onBack = onBack,
                onTitleTap = onTitleTap,
                scrollBehavior = scrollBehavior,
                actions = {
                    // Only the search action stays as a dedicated icon — info
                    // is triggered by tapping the title row. Keeps the action
                    // bar uncluttered and gives the title region a real
                    // affordance instead of decorative chrome.
                    IconButton(onClick = onSearchToggle) {
                        Symbol(
                            name = "search",
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty-state + skeleton composables
// ---------------------------------------------------------------------------

@Composable
private fun ChannelSearchEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "search_off",
            shape = HortayExpressive.FolderSelected,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.timeline_search_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChannelEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveEmptyHero(
            symbol = "forum",
            shape = HortayExpressive.EmptyStateMask,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.channel_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.channel_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Constants local to ChannelScreen
// ---------------------------------------------------------------------------

/** How many items from the end of the channel list trigger an older-history fetch. */
private const val CHANNEL_PAGINATION_THRESHOLD = 6

/** Viewport-stable dwell before marking posts as read. Matches TimelineScreen's READ_DWELL_MS. */
private const val CHANNEL_READ_DWELL_MS = 500L

/** How long the surface-tint highlight lingers after scroll-to-message. Matches TimelineScreen. */
private const val CHANNEL_HIGHLIGHT_DURATION_MS = 2200L

/** Viewport-stable debounce before triggering comments prefetch. Matches TimelineScreen. */
private const val CHANNEL_PREFETCH_DEBOUNCE_MS = 1200L

/** Cap on prefetchThread fan-out per viewport-stable burst. Matches TimelineScreen. */
private const val CHANNEL_COMMENTS_PREFETCH_LIMIT = 1

/** Posts ahead of first visible to eagerly prefetch. Matches TimelineScreen's PREFETCH_AHEAD. */
private const val CHANNEL_PREFETCH_AHEAD = 2

/**
 * Recency floor for the OldestUnreadFirst boundary picker — posts older than
 * `now - BOUNDARY_RECENCY_WINDOW_MS` don't qualify as the oldest-unread
 * landing target / [FeedItem.Boundary] anchor. Matches the merged-feed
 * window in [TimelineScreen]; same dormant-channel rationale.
 */
private const val BOUNDARY_RECENCY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
