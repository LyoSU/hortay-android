@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /**
     * Signal from the push-site that the wait-for-content preload exceeded
     * its grace window. When true, the resolving-state skeleton paints
     * immediately (no [rememberDeferredLoading] grace) — the user already
     * waited the source-side grace, so stacking another wait on the target
     * reads as a freeze. When false, the standard 600 ms grace applies so a
     * fast resolve (deep-link target landing within that window) doesn't
     * flash a skeleton.
     */
    instantSkeleton: Boolean = false,
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

    val posts by vm.posts.collectAsStateWithLifecycle()
    val historyLoading by vm.historyLoading.collectAsStateWithLifecycle()
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

    // Source-of-truth for what the LazyColumn renders. While search is active
    // we render the raw results list (no threading). Outside search we apply the
    // Threads-style grouping from groupReplies, same as the all-feed path. The
    // user-selected [feedOrder] is honoured here so OldestUnreadFirst flips the
    // channel into the reverse-feed layout exactly like TimelineScreen does on
    // the all-feed — search results stay in their RPC relevance order regardless.
    val cursorHolder = LocalReadCursors.current
    val displayedItems = remember(posts, searchActive, searchResults, feedOrder) {
        val list = if (searchActive) searchResults.map<TimelinePost, FeedItem>(FeedItem::Single)
        else groupReplies(posts.orderedFor(feedOrder))
        list.toPersistentList()
    }

    // [ChannelUiState] is the single source of truth for what gets mounted on
    // this screen. The VM owns the deep-link around-load — when the candidate
    // resolves to [ChannelUiState.Resolving], the channel paints a [SkeletonFeed]
    // instead of flashing the head post at index 0 for a frame before the deep
    // link lands. On [ChannelUiState.Missing] the screen falls back to the
    // normal newest-first view and surfaces a snackbar via [onScrollMissed].
    //
    // Cursors snapshot is latched on (chatId, feedOrder) — the only inputs
    // that change the boundary semantics for a single-channel feed (channel
    // identity + sort direction). [continueReadingIndex] inside
    // [buildChannelUiState] is one-shot per Ready latch in
    // [rememberLatchedChannelUiState], so we don't need live cursors here;
    // capturing once per channel open also avoids the per-recomposition
    // `map.toMap().toPersistentMap()` allocation the inline call had.
    val channelCursors = remember(chatId, feedOrder) { cursorHolder.snapshot() }
    val candidateChannelUiState = buildChannelUiState(
        items = displayedItems,
        historyLoading = historyLoading,
        scrollToMessageId = vm.scrollToMessageId,
        attemptedAround = attemptedAround,
        searchActive = searchActive,
        chatId = chatId,
        feedOrder = feedOrder,
        cursors = channelCursors,
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
            listState.scrollToItem(idx)
            pendingScrollToMessage = null
        },
        onMissed = {
            pendingScrollToMessage = null
            onScrollMissed()
        },
    )

    // Pagination: near-bottom snapshotFlow → VM.loadOlderIfPossible(). The VM
    // guards against concurrent calls; the threshold (6 items from the end)
    // matches PAGINATION_PREFETCH_THRESHOLD in TimelineScreen.
    LaunchedEffect(listState, chatId) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to lastVisible
        }
            .distinctUntilChanged()
            .collect { (total, last) ->
                if (total == 0 || last < 0) return@collect
                if (last >= total - CHANNEL_PAGINATION_THRESHOLD) {
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
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId, null)
                    !sourceHandle.isNullOrBlank() -> {
                        // Username-only: route through HortayUriHandler so the public
                        // channel handle resolves via SearchPublicChat.
                        uriHandler.openUri("https://t.me/${sourceHandle.removePrefix("@")}")
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
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
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
                // [rememberDeferredLoading] short-circuits "fast open" cases.
                // Most channel entries land Ready in 100-400 ms (per-channel
                // history is local-cache served by TDLib when the channel has
                // ever surfaced in the merged feed) — without a grace window,
                // [SkeletonFeed] paints for one or two frames, then unmounts
                // as Ready takes over. That's read as a flicker: "скелет при
                // відкритті каналу швидкому все одно є". The grace keeps the
                // skeleton off-screen during the common-case sub-grace
                // resolve. Anything past 600 ms is a slow open that genuinely
                // needs feedback (cold-cache deep-link, FLOOD_WAIT, post-DC
                // migration) — skeleton paints then. Shared
                // [LOADING_OVERLAY_GRACE_MS] (600 ms) keeps the threshold
                // identical to the comments overlay and media indicators —
                // one app-wide contract for "slow enough to surface".
                //
                // [instantSkeleton] overrides the grace: when the push-site
                // already burned the wait-for-content preload window
                // (`primeChannelForOpen` timed out) the user has already
                // waited 300 ms on the source view, so stacking another grace
                // on the target would surface as a freeze. The skeleton
                // paints on the first Resolving frame in that case.
                val deferredSkeleton = rememberDeferredLoading(
                    pending = isResolving,
                    key = chatId,
                )
                val showSkeleton = isResolving && (instantSkeleton || deferredSkeleton)
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
                        when {
                            searchActive && searchQuery.isNotBlank() -> ChannelSearchEmpty()
                            historyLoading -> ChannelPreviewSkeleton()
                            else -> ChannelEmptyState()
                        }
                    }
                    else -> {
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

                        CompositionLocalProvider(LocalScrollGate provides scrollGate) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = displayedList,
                                    key = { it.key },
                                    contentType = { if (it is FeedItem.Thread) "thread" else "single" },
                                ) { item ->
                                    val isCenteredState = remember(item.key) {
                                        derivedStateOf { centeredItemKeyState.value == item.key }
                                    }
                                    val highlighted = highlightedPostKey?.let { (cid, mid) ->
                                        item.posts().any { p ->
                                            p.chatId == cid && (p.id == mid || mid in p.albumMessageIds)
                                        }
                                    } == true
                                    CompositionLocalProvider(
                                        LocalIsCenteredItem provides isCenteredState,
                                        LocalIsHighlightedItem provides highlighted,
                                    ) {
                                        when (item) {
                                            is FeedItem.Single -> PostCard(
                                                post = item.post,
                                                interactions = interactions,
                                            )
                                            is FeedItem.Thread -> ThreadedPostPair(
                                                parent = item.parent,
                                                reply = item.reply,
                                                interactions = interactions,
                                            )
                                        }
                                    }
                                }
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

/**
 * Loading skeleton for a freshly entered channel — three placeholder cards roughed to the
 * shape of a real [PostCard]. Matches [TimelineScreen]'s ChannelPreviewSkeleton exactly;
 * moved here since it is now only needed in the channel view, not the all-feed.
 *
 * Shimmer is a single infinite Animatable driving the surfaceContainerHighest /
 * surfaceContainerLow alpha sweep — cheap (no allocations per frame, one Animatable for
 * the whole skeleton). MotionScheme isn't used here because the animation is a deliberate
 * slow loop (1.2s linear), not a state-change spring.
 */
@Composable
private fun ChannelPreviewSkeleton() {
    val infinite = rememberInfiniteTransition(label = "preview-skeleton")
    val shimmer by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "preview-skeleton-alpha",
    )
    val shimmerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = shimmer)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) { SkeletonCard(shimmerColor) }
    }
}

@Composable
private fun SkeletonCard(barColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(140.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(barColor),
                )
                Box(
                    modifier = Modifier
                        .height(10.dp)
                        .width(80.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(barColor),
        )
    }
}

// ---------------------------------------------------------------------------
// Constants local to ChannelScreen
// ---------------------------------------------------------------------------

/** How many items from the end of the channel list trigger an older-history fetch. */
private const val CHANNEL_PAGINATION_THRESHOLD = 6

/** Viewport-stable dwell before marking posts as read. Matches TimelineScreen's READ_DWELL_MS. */
private const val CHANNEL_READ_DWELL_MS = 1000L

/** How long the surface-tint highlight lingers after scroll-to-message. Matches TimelineScreen. */
private const val CHANNEL_HIGHLIGHT_DURATION_MS = 2200L

/** Viewport-stable debounce before triggering comments prefetch. Matches TimelineScreen. */
private const val CHANNEL_PREFETCH_DEBOUNCE_MS = 1200L

/** Cap on prefetchThread fan-out per viewport-stable burst. Matches TimelineScreen. */
private const val CHANNEL_COMMENTS_PREFETCH_LIMIT = 1

/** Posts ahead of first visible to eagerly prefetch. Matches TimelineScreen's PREFETCH_AHEAD. */
private const val CHANNEL_PREFETCH_AHEAD = 2
