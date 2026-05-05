package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.channels.ChannelInfoSheet
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.BrandRow
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1 even though Flow.debounce(Duration) graduated.
// Remove only when the Long overload is stabilised upstream.
/**
 * Mode-agnostic timeline screen. Drives both the authenticated TDLib mode and
 * the anonymous (guest) web mode through a single Composable.
 *
 *   - [feed] — required, mode-defining data source (TDLib's [PostsRepository]
 *     or web's [dev.lyo.hortay.data.web.WebFeedSource]). Both implement
 *     [FeedSource] so the inner [TimelineViewModel] is mode-blind.
 *   - All other parameters except [bookmarks] are nullable: when null, the
 *     corresponding affordance is hidden. Guest mode passes [tdlibRepo] /
 *     [commentsRepo] / [folders] / [translations] / [channelActions] = null
 *     and gets a clean feed view. TDLib mode passes them all.
 *
 * What's gated by nullability:
 *   - Folders bar — needs [folders]
 *   - In-channel search — needs [tdlibRepo] (search uses TDLib SearchChatMessages)
 *   - Comments tap, channel-info sheet — need [commentsRepo] / [tdlibRepo]
 *   - Translation chip — needs [translations]
 *   - Channel actions (mute/unmute) — needs [channelActions]
 *   - Channel filter, archived chats, view receipts — need [tdlibRepo]
 *
 * What's always present (works in both modes):
 *   - Pull-to-refresh, scroll gate, prefetch, bookmark, "new posts" pill,
 *     LargeTopAppBar with BrandRow / saved title, empty state.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TimelineScreen(
    feed: dev.lyo.hortay.data.FeedSource,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    showOnlyBookmarked: Boolean,
    channelFilter: Long?,
    onChannelFilterChange: (Long?) -> Unit,
    tdlibRepo: PostsRepository? = null,
    commentsRepo: CommentsRepository? = null,
    folders: ChatFoldersRepository? = null,
    translations: TranslationsStore? = null,
    channelActions: ChannelActionsRepository? = null,
    onOpenComments: (TimelinePost) -> Unit = {},
    homeTapTrigger: Long = 0L,
    onBrandTap: () -> Unit = {},
    /**
     * One-shot "scroll to this message in the active feed" request. TDLib-mode
     * deep-link dispatcher only; guest mode passes null.
     */
    scrollToMessage: Pair<Long, Long>? = null,
    onScrollHandled: () -> Unit = {},
    /**
     * When non-null, a search action is shown in the default top bar (no filter,
     * not bookmarked-only) and tapping it invokes this callback. Guest mode wires
     * it to a cross-channel local search overlay; TDLib mode already has its own
     * in-channel search bar (active only when [channelFilter] is set), so it
     * leaves this null and keeps the global feed bar minimal.
     */
    onSearchClick: (() -> Unit)? = null,
) {
    val vm: TimelineViewModel = viewModel(
        factory = remember(feed, bookmarks) {
            viewModelFactory { initializer { TimelineViewModel(feed, bookmarks) } }
        },
    )
    val context = LocalContext.current

    val posts by vm.posts.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val bookmarkedKeys by vm.bookmarkedKeys.collectAsStateWithLifecycle()
    val pendingNew by vm.pendingNew.collectAsStateWithLifecycle()
    val foldersList: List<org.drinkless.tdlib.TdApi.ChatFolderInfo> = folders?.folders
        ?.collectAsStateWithLifecycle()?.value
        ?: emptyList()
    val archivedChatIds: Set<Long> = tdlibRepo?.archivedChatIds
        ?.collectAsStateWithLifecycle()?.value
        ?: emptySet()
    val translationsMap = translations?.translations
        ?.collectAsStateWithLifecycle()?.value
        ?: emptyMap()
    var infoSheetChatId by remember { mutableStateOf<Long?>(null) }

    // Search state: only meaningful inside a channel filter. searchActive flips the top
    // bar into a TextField; query drives a debounced SearchChatMessages call; results
    // replace the normal feed in the list while the bar is in search mode.
    var searchActive by rememberSaveable(channelFilter) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(channelFilter) { mutableStateOf("") }
    var searchResults by remember(channelFilter) { mutableStateOf<List<TimelinePost>>(emptyList()) }
    if (channelFilter != null && tdlibRepo != null) {
        LaunchedEffect(searchActive, searchQuery, channelFilter) {
            if (!searchActive || searchQuery.isBlank()) {
                searchResults = emptyList()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            searchResults = tdlibRepo.searchInChannel(channelFilter, searchQuery.trim())
        }
    }

    // Pill is suppressed during a refresh: repo.refresh() replaces _posts, briefly making
    // the post-refresh delta look like "everything is new" until acceptPending() lands.
    // Without this guard the pill flashes a misleading huge count for ~1 frame.

    val scope = rememberCoroutineScope()
    // Two scroll-position holders so a channel-filter detour doesn't blow away where the
    // user was reading the global feed. The global state is rememberLazyListState (already
    // saveable across config changes); the filter state is keyed on [channelFilter] so
    // entering a new channel starts at the top, while the user is in that channel rotations
    // preserve their position. Returning to the global feed (channelFilter = null) lands
    // them exactly where they left off.
    val globalListState = rememberLazyListState()
    val filterListState = rememberSaveable(channelFilter, saver = LazyListState.Saver) {
        LazyListState()
    }
    val listState = if (channelFilter != null) filterListState else globalListState
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // One-shot "scroll to this messageId once it lands in the list". Two producers feed
    // this: in-app quote-card taps (see [PostInteractions.onQuotedSourceClick]) and
    // external deep links (the [scrollToMessage] parameter from MainScaffold). One
    // consumer — the LaunchedEffect below — resolves the target by scanning
    // displayedItems and clears the request on success. Cleared too on filter dismissal
    // (C2 fix) so a stale target from a previous channel doesn't yank the user later.
    var pendingScrollToMessage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(scrollToMessage) {
        if (scrollToMessage != null) {
            pendingScrollToMessage = scrollToMessage
            onScrollHandled()
        }
    }
    LaunchedEffect(channelFilter) {
        // Filter went back to "all" (or switched to a different chat than the queued
        // target referenced) — drop the stale request so we don't fire it later.
        val target = pendingScrollToMessage
        if (target != null && (channelFilter == null || target.first != channelFilter)) {
            pendingScrollToMessage = null
        }
    }

    // Selected folder/archive scope. Default: "All". Stored as a saveable so the user's
    // tab survives process death; folder tabs get rebuilt against the freshest folders
    // list each composition, so a folder removed in another client falls back gracefully.
    var selectedFolderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var archiveSelected by rememberSaveable { mutableStateOf(false) }
    val scope_filter: FilterScope = remember(selectedFolderId, archiveSelected, foldersList) {
        when {
            archiveSelected -> FilterScope.Archive
            selectedFolderId != null -> {
                val match = foldersList.firstOrNull { it.id == selectedFolderId }
                if (match == null) FilterScope.All
                else FilterScope.Folder(match.id, match.name?.text?.text.orEmpty())
            }
            else -> FilterScope.All
        }
    }
    // Pinned + included member ids for the active folder (null when not a Folder scope).
    var folderMemberIds by remember(scope_filter) { mutableStateOf<Set<Long>?>(null) }
    var folderIncludesAllChannels by remember(scope_filter) { mutableStateOf(false) }
    var folderExcludedIds by remember(scope_filter) { mutableStateOf<Set<Long>>(emptySet()) }
    // Real Telegram folder rules can hide archived chats; mirror that here so a folder
    // with excludeArchived=true doesn't leak archived channels into its tab. The other
    // exclude_* flags (excludeMuted, excludeRead) only meaningfully apply to user/group
    // chats — channels rarely have actionable mute or unread state, and Hortay scopes
    // its feed to channels only.
    var folderExcludeArchived by remember(scope_filter) { mutableStateOf(false) }
    LaunchedEffect(scope_filter) {
        val folderScope = scope_filter as? FilterScope.Folder
        if (folderScope == null) {
            folderMemberIds = null
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
            folderExcludeArchived = false
            return@LaunchedEffect
        }
        val full = folders?.fullFolder(folderScope.id)
        if (full == null) {
            folderMemberIds = emptySet()
            folderIncludesAllChannels = false
            folderExcludedIds = emptySet()
            folderExcludeArchived = false
        } else {
            folderMemberIds = (full.pinnedChatIds?.toSet().orEmpty() + full.includedChatIds?.toSet().orEmpty())
            folderIncludesAllChannels = full.includeChannels
            folderExcludedIds = full.excludedChatIds?.toSet().orEmpty()
            folderExcludeArchived = full.excludeArchived
        }
    }
    val viewer = LocalMediaViewer.current

    // True when the LazyColumn is at the very top — used to auto-collapse pending posts
    // (no pill needed if the user is already looking at the top of the feed).
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
        }
    }

    // Twitter-style "tap home twice": first tap scrolls to top, second one (already at top)
    // refreshes. The trigger is a monotonic timestamp from the parent, so a single bump
    // produces a single reaction.
    LaunchedEffect(homeTapTrigger) {
        if (homeTapTrigger == 0L) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atTop) vm.refresh() else listState.animateScrollToItem(0)
    }

    // Switching folders within the global feed jumps to the top — "show me the top of
    // this folder" is the expected behaviour. Channel-filter visits don't need an explicit
    // scroll: filterListState above is freshly remembered per channelFilter, so it starts
    // at the top by construction. Going back to the global feed (channelFilter = null)
    // restores globalListState, which was preserved while the user was off in the filter.
    LaunchedEffect(scope_filter) {
        if (channelFilter == null) {
            globalListState.scrollToItem(0)
        }
    }

    // Tell TDLib the filtered channel is in focus while the user is here, and eagerly pull
    // a deeper slice of history so the filtered list is not just the few entries the global
    // refresh fetched per channel.
    LaunchedEffect(channelFilter) {
        val id = channelFilter ?: return@LaunchedEffect
        val r = tdlibRepo ?: return@LaunchedEffect
        r.openChat(id)
        r.loadChannelHistory(id)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { r.closeChat(id) }
        }
    }

    // Pagination: when the user scrolls near the bottom of a single-channel feed, pull
    // older posts. Only fires inside a channelFilter context — paginating the global
    // mixed feed by oldest-of-each-channel would touch many channels at once and serves
    // no real "I want to read this channel further back" intent.
    if (channelFilter != null && tdlibRepo != null) {
        LaunchedEffect(listState, channelFilter) {
            androidx.compose.runtime.snapshotFlow {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total to lastVisible
            }
                .distinctUntilChanged()
                .collect { (total, last) ->
                    if (total == 0 || last < 0) return@collect
                    if (last >= total - PAGINATION_PREFETCH_THRESHOLD) {
                        tdlibRepo.loadOlder(channelFilter)
                    }
                }
        }
    }

    // Scope predicate shared by the visible feed and the "X нових постів" pill — so the
    // pill can't surface pending posts the user can't actually see (e.g. archived chats
    // while the user is in "Усі", or out-of-folder channels while a folder is active).
    val scopePredicate = remember(
        scope_filter, archivedChatIds, folderMemberIds, folderIncludesAllChannels,
        folderExcludedIds, folderExcludeArchived,
    ) {
        { p: TimelinePost ->
            val isArchived = p.chatId in archivedChatIds
            when (scope_filter) {
                FilterScope.All -> !isArchived
                FilterScope.Archive -> isArchived
                is FilterScope.Folder -> {
                    if (folderExcludeArchived && isArchived) false
                    else {
                        val included = folderMemberIds?.contains(p.chatId) == true ||
                            folderIncludesAllChannels
                        included && p.chatId !in folderExcludedIds
                    }
                }
            }
        }
    }

    val visiblePosts = remember(
        posts, scopePredicate, bookmarkedKeys, channelFilter, showOnlyBookmarked,
    ) {
        buildList {
            posts.forEach { p ->
                if (showOnlyBookmarked && p.bookmarkKey() !in bookmarkedKeys) return@forEach
                if (channelFilter != null && p.chatId != channelFilter) return@forEach

                // Mixed global feed: hide service / expired-media noise (pin / boost /
                // giveaway-created / ttl-expired). They're meaningful only in the context
                // of a single channel, where the per-channel filter view shows them as
                // the actual record of channel events.
                if (channelFilter == null) {
                    if (p.content is PostContent.Service) return@forEach
                    if (p.content is PostContent.ExpiredMedia) return@forEach
                }

                if (!scopePredicate(p)) return@forEach
                add(p)
            }
        }
    }

    // Threads-style grouping: when a post replies to another post that's ALSO present in the
    // visible feed, the two are merged into a single LazyColumn slot (parent stacked above
    // reply, joined by a connector line). Drives the main feed render; LaunchedEffects below
    // that map "visible item indices" → posts use [FeedItem.posts] to flatten threaded slots
    // back into individual TimelinePost entries.
    val feedItems = remember(visiblePosts) { groupReplies(visiblePosts) }

    // Source of truth for "what the LazyColumn is currently rendering". Search results stay
    // flat (threading a search hit by its parent would surface posts the user didn't search
    // for); outside search we reuse the grouped feedItems verbatim.
    val displayedItems: List<FeedItem> = remember(
        feedItems, searchActive, channelFilter, searchResults,
    ) {
        if (searchActive && channelFilter != null) searchResults.map(FeedItem::Single)
        else feedItems
    }

    // Resolve the queued "scroll to messageId" once the target row appears. Keyed on
    // [pendingScrollToMessage] alone (NOT on displayedItems) so a busy feed doesn't
    // restart the effect dozens of times per second on every list mutation — instead we
    // use snapshotFlow inside to react to displayedItems changes lazily, and stop
    // collecting as soon as we land the scroll. The lookup matches both the post's
    // canonical id AND any of its album member ids — TimelinePost collapses an album
    // into a single row keyed on the oldest member, but a quote card may point at any
    // member.
    LaunchedEffect(pendingScrollToMessage) {
        val (chatId, messageId) = pendingScrollToMessage ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { displayedItems }
            .collect { items ->
                val idx = items.indexOfFirst { item ->
                    item.posts().any { p ->
                        p.chatId == chatId && (p.id == messageId || messageId in p.albumMessageIds)
                    }
                }
                if (idx >= 0) {
                    listState.animateScrollToItem(idx)
                    pendingScrollToMessage = null
                    return@collect
                }
            }
    }

    // Pill state, scoped to the active tab. Counting pending posts the user can't see
    // would flash a misleading "5 нових" while archive/out-of-folder posts arrive in the
    // background.
    val scopedPendingNew = remember(pendingNew, scopePredicate) {
        pendingNew.filter(scopePredicate)
    }
    val scopedPendingChannels = remember(scopedPendingNew) {
        scopedPendingNew
            .groupBy { it.chatId }
            .map { (chatId, group) ->
                val anchor = group.maxBy { it.date }
                // Personal-author posts have the admin in senderName/avatar; the channel's
                // own identity lives in channelContext. Pick the first post in the group
                // that has a channelContext (= it's a personal-author post and the channel
                // info is right there) and otherwise fall back to anchor's own fields.
                val canonical = group.firstNotNullOfOrNull { it.channelContext }
                ChannelBadge(
                    chatId = chatId,
                    title = canonical?.name ?: anchor.senderName,
                    thumb = canonical?.avatarThumb ?: anchor.avatarThumb,
                    fileId = canonical?.avatarFileId ?: anchor.avatarFileId,
                    latestPostDate = anchor.date,
                )
            }
            .sortedByDescending { it.latestPostDate }
            .take(MAX_PILL_BADGES)
    }

    // While the user is at the top, fold pending live updates straight into the visible
    // feed. Keyed on the scoped pending list so we only ack what's actually visible in
    // the current tab — pending in other scopes (archive, other folders) stays unread
    // until the user navigates there.
    LaunchedEffect(atTop, scopedPendingNew) {
        if (atTop && scopedPendingNew.isNotEmpty()) {
            vm.acceptIds(scopedPendingNew.map { it.chatId to it.id })
        }
    }

    val activeChannelTitle = remember(channelFilter, posts) {
        channelFilter?.let { id ->
            // Same canonical-channel-identity rule as the channels list / pendingChannels:
            // if any post for this filter has a channelContext (personal-author mode), use
            // its name; otherwise fall back to the post's own senderName which IS the
            // channel name in standard channel-as-sender mode.
            val matches = posts.filter { it.chatId == id }
            matches.firstNotNullOfOrNull { it.channelContext?.name }
                ?: matches.firstOrNull()?.senderName
        }
    }
    var activeChannelSubscribers by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(channelFilter) {
        activeChannelSubscribers = channelFilter?.let { tdlibRepo?.channelSubscribers(it) }
    }

    // Warm the discussion-thread cache for posts that linger in the viewport. A cold
    // GetMessageThread is a server round-trip (~1.5–2s on first hit per channel); after
    // a single fetch TDLib answers from local cache (~200ms). Triggering this in the
    // background while the user is reading means the comments tap is effectively
    // instant for visible posts, and avoids burning bandwidth on posts the user just
    // scrolls past. CommentsRepository de-duplicates per anchor so this is safe to spam.
    if (commentsRepo != null) {
        LaunchedEffect(listState, commentsRepo) {
            androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .debounce(700)
                .collect { indices ->
                    val snapshot = feedItems
                    indices.flatMap { snapshot.getOrNull(it)?.posts().orEmpty() }
                        .filter { it.commentCount != null }
                        .forEach { post ->
                            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                            commentsRepo.prefetchThread(post.chatId, ids)
                        }
                }
        }
    }

    // Mark visible posts as viewed (server-side view counter increments). Two safeguards:
    //   • keyed on listState (not visiblePosts) — visiblePosts gets a fresh List on every
    //     update from TDLib (reactions, views, edits), which would otherwise restart this
    //     LaunchedEffect 50×/sec on a busy feed and re-fire viewMessages for every visible
    //     row → the FLOOD_WAIT we saw earlier.
    //   • distinctUntilChanged + debounce(500): a single drag scroll emits dozens of indices
    //     transitions; we only want to ack what stayed visible after the user paused.
    if (tdlibRepo != null) {
        LaunchedEffect(listState, tdlibRepo) {
            androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { indices ->
                    val snapshot = feedItems
                    if (snapshot.isEmpty() || indices.isEmpty()) return@collect
                    val grouped = indices.flatMap { idx -> snapshot.getOrNull(idx)?.posts().orEmpty() }
                        .groupBy { it.chatId }
                    for ((chatId, group) in grouped) {
                        tdlibRepo.viewMessages(chatId, group.map { it.id })
                    }
                }
        }
    }

    // Frequently-changing state that the interactions lambdas need to read at the time
    // of invocation (not at construction time). Wrapping in rememberUpdatedState gives
    // the lambdas a stable [State] handle whose `.value` is always up-to-date — so we
    // don't have to rebuild [PostInteractions] (and trigger a recomposition cascade
    // through every PostCard in the viewport) on every TDLib update. Before this, an
    // UpdateMessageInteractionInfo for a single post (reaction, view counter) churned
    // `posts`, which churned `interactions`, which invalidated all PostCard skips.
    val postsState = rememberUpdatedState(posts)
    val translationsState = rememberUpdatedState(translationsMap)
    val bookmarkedState = rememberUpdatedState(bookmarkedKeys)
    val onChannelFilterChangeState = rememberUpdatedState(onChannelFilterChange)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)

    val interactions = remember {
        // Album members share the same translation — TDLib stores translations against the
        // caption-carrying message id, but for the UI any post in the album should look
        // translated. Fall back to scanning album members when the lookup misses.
        //
        // The cache is keyed by language as well: the user's system locale can change
        // mid-session and we don't want to serve a stale translation in the wrong target
        // tongue. We resolve the active target on every lookup so a post's render reacts
        // to a locale change as soon as the next recomposition reads translationsState.
        fun lookup(post: TimelinePost): dev.lyo.hortay.data.FormattedText? {
            val t = translations ?: return null
            val map = translationsState.value
            val lang = t.currentTargetLanguage()
            map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, post.id, lang)]?.let { return it }
            post.albumMessageIds.forEach { id ->
                map[dev.lyo.hortay.data.TranslationsStore.Key(post.chatId, id, lang)]?.let { return it }
            }
            return null
        }
        PostInteractions(
            onMediaClick = { post, idx ->
                viewer.openFor(post.content, idx)
            },
            onChannelClick = { post -> onChannelFilterChangeState.value(post.chatId) },
            onForwardSourceClick = { post ->
                val origin = post.forwardOrigin
                val sourceId = when (origin) {
                    is dev.lyo.hortay.data.ForwardOrigin.Channel -> origin.sourceChatId
                    is dev.lyo.hortay.data.ForwardOrigin.Chat -> origin.sourceChatId
                    else -> null
                }
                val sourceHandle = when (origin) {
                    is dev.lyo.hortay.data.ForwardOrigin.Channel -> origin.sourceHandle
                    is dev.lyo.hortay.data.ForwardOrigin.Chat -> origin.sourceHandle
                    else -> null
                }
                // Already in our subscribed feed → switch the filter so the user lands on
                // that channel's posts. Otherwise hand off to the Telegram client.
                val subscribed = postsState.value.any { it.chatId == sourceId }
                when {
                    sourceId != null && subscribed -> onChannelFilterChangeState.value(sourceId)
                    !sourceHandle.isNullOrBlank() -> {
                        val handle = sourceHandle.removePrefix("@")
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    "tg://resolve?domain=$handle".toUri(),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            },
            onQuotedSourceClick = { post ->
                // In-app "open the original" — switch the channel filter (no Intent chooser
                // bounce) and queue a scroll-to-target. The scroll is one-shot: a
                // LaunchedEffect below resolves it as soon as the target message lands in
                // displayedItems, then clears the pending state. If the channel was already
                // loaded the scroll happens on the next frame; otherwise we wait for
                // loadChannelHistory to deliver, then snap.
                post.reply?.let { r ->
                    onChannelFilterChangeState.value(r.replyToChatId)
                    pendingScrollToMessage = r.replyToChatId to r.replyToMessageId
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> PostActions.share(context, post) },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post -> PostActions.openInTelegram(context, post) },
            onTranslateClick = { post ->
                val t = translations ?: return@PostInteractions
                scope.launch {
                    val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                    t.translate(post.chatId, ids.first())
                }
            },
            onClearTranslationClick = { post ->
                val t = translations ?: return@PostInteractions
                val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                ids.forEach { t.clear(post.chatId, it) }
            },
            isTranslated = { post -> lookup(post) != null },
            translationFor = ::lookup,
            onReactionToggle = { post, item ->
                val ca = channelActions ?: return@PostInteractions
                scope.launch {
                    val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                    ca.toggleReaction(
                        chatId = post.chatId,
                        messageId = target,
                        kind = item.kind,
                        isChosen = item.isChosen,
                    )
                }
            },
            onPostClick = { post -> onOpenCommentsState.value(post) },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TimelineTopBar(
                showOnlyBookmarked = showOnlyBookmarked,
                channelTitle = activeChannelTitle,
                channelSubscribers = activeChannelSubscribers,
                hasFilter = channelFilter != null,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchToggle = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it },
                onClearFilter = {
                    if (searchActive) {
                        searchActive = false
                        searchQuery = ""
                    } else {
                        onChannelFilterChange(null)
                    }
                },
                onBrandTap = onBrandTap,
                onTitleTap = { channelFilter?.let { infoSheetChatId = it } },
                onGlobalSearchClick = onSearchClick,
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // FoldersBar is hidden when there's nothing to switch between:
                    //   - User has no custom folders AND no archive
                    //   - Or we're in showOnlyBookmarked / channelFilter context
                    //   - Or we're in guest mode (folders == null → empty list)
                    // Showing a single "All" tab on its own is a vestigial control
                    // that takes vertical space without giving the user a choice.
                    val tabs = remember(foldersList) {
                        foldersList.map { FolderTab(it.id, it.name?.text?.text.orEmpty()) }
                    }
                    val hasFolderUi = tabs.isNotEmpty() || archivedChatIds.isNotEmpty()
                    if (!showOnlyBookmarked && channelFilter == null && hasFolderUi) {
                        FoldersBar(
                            selected = scope_filter,
                            folders = tabs,
                            showArchive = archivedChatIds.isNotEmpty(),
                            onSelected = { sel ->
                                when (sel) {
                                    FilterScope.All -> {
                                        selectedFolderId = null
                                        archiveSelected = false
                                    }
                                    FilterScope.Archive -> {
                                        selectedFolderId = null
                                        archiveSelected = true
                                    }
                                    is FilterScope.Folder -> {
                                        selectedFolderId = sel.id
                                        archiveSelected = false
                                    }
                                }
                            },
                        )
                    }

                    val displayed = if (searchActive && channelFilter != null) searchResults else visiblePosts
                    if (displayed.isEmpty() && !refreshing && !(searchActive && searchQuery.isBlank())) {
                        if (searchActive) SearchEmpty() else EmptyState(showOnlyBookmarked)
                    } else {
                        // Scroll gate: while the LazyColumn is mid-scroll (drag, fling,
                        // animateScrollToItem) media composables defer their ensure() so
                        // we don't saturate TDLib's 4-slot pool with intermediate posts
                        // that are about to scroll past anyway. Flips to "open" the moment
                        // scroll settles, at which point the genuinely-visible posts
                        // burst-ensure in one frame. Telegram-Android's RecyclerView uses
                        // SCROLL_STATE_IDLE for the same purpose.
                        val scrollGate = remember(listState) {
                            derivedStateOf { !listState.isScrollInProgress }
                        }

                        // Eager prefetch: while the user reads what's on screen, warm
                        // the next [PREFETCH_AHEAD] posts' posters at Prefetch priority.
                        // By the time the user scrolls down, those files are already
                        // partially or fully on disk and the loading overlay never paints.
                        // Gated on scroll-settled (prefetchAnchor=null while scrolling)
                        // so we don't fire ensure() while the gate above is closed.
                        val cache = LocalMediaCache.current
                        val prefetchAnchor by remember(listState) {
                            derivedStateOf {
                                if (listState.isScrollInProgress) null
                                else listState.firstVisibleItemIndex
                            }
                        }
                        LaunchedEffect(prefetchAnchor, displayedItems) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= displayedItems.size) return@LaunchedEffect
                            val end = (firstVisible + PREFETCH_AHEAD).coerceAtMost(displayedItems.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val item = displayedItems.getOrNull(idx) ?: continue
                                for (post in item.posts()) {
                                    for (fileId in post.content.posterFileIds()) {
                                        cache.ensure(fileId, DownloadPriority.Prefetch)
                                    }
                                    // Inline-playable media (short videos, GIF animations) get the
                                    // playback file pre-warmed too, but ONLY for the immediate next
                                    // slot. Beyond +1 we'd burn megabytes speculating on posts the
                                    // user may never reach (a 30 s autoplay video is already ~5 MB).
                                    // Posters stay cheap to prefetch farther, since they're tens of
                                    // KB; playback is the heavyweight step we cap tightly.
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
                                items(items = displayedItems, key = { it.key }) { item ->
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

            // Floating "X нових постів" pill. Hidden in the Saved tab, inside a single-
            // channel filter (frozen views), while a refresh is in flight (transient
            // delta), and while the user is already at the top of the feed (we auto-
            // accept pending in that case).
            val pillVisible = !showOnlyBookmarked && channelFilter == null &&
                !refreshing && !atTop && scopedPendingChannels.isNotEmpty()
            // Filter chips occupy the top ~56dp inside the same Box; offset the pill so
            // it lands just below them instead of overlapping.
            val chipsVisible = !showOnlyBookmarked && channelFilter == null
            val pillTopPadding = if (chipsVisible) 64.dp else 8.dp
            AnimatedVisibility(
                visible = pillVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = pillTopPadding),
            ) {
                NewPostsPill(
                    channels = scopedPendingChannels,
                    pendingCount = scopedPendingNew.size,
                    onClick = {
                        // Ack only scope-visible pending; archive / other-folder pending
                        // stays unread for those tabs.
                        vm.acceptIds(scopedPendingNew.map { it.chatId to it.id })
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                )
            }
        }
    }

    val ca = channelActions
    if (ca != null) {
        infoSheetChatId?.let { chatId ->
            ChannelInfoSheet(
                chatId = chatId,
                actions = ca,
                onDismiss = { infoSheetChatId = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    showOnlyBookmarked: Boolean,
    channelTitle: String?,
    channelSubscribers: Int?,
    hasFilter: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearFilter: () -> Unit,
    onBrandTap: () -> Unit,
    onTitleTap: () -> Unit,
    onGlobalSearchClick: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    when {
        hasFilter && searchActive -> TopAppBar(
            title = {
                val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
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
                IconButton(onClick = onClearFilter) {
                    Symbol(name = "arrow_back", contentDescription = "back")
                }
            },
            actions = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Symbol(name = "close", contentDescription = "clear")
                    }
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        hasFilter -> TopAppBar(
            title = {
                Column(modifier = Modifier.clickable(onClick = onTitleTap)) {
                    Text(
                        text = channelTitle.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    channelSubscribers?.let {
                        Text(
                            text = stringResource(R.string.timeline_subscribers, formatSubscribers(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onClearFilter) {
                    Symbol(name = "arrow_back", contentDescription = "back")
                }
            },
            actions = {
                IconButton(onClick = onSearchToggle) {
                    Symbol(name = "search", contentDescription = "search")
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        showOnlyBookmarked -> TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.timeline_saved_tab),
                    style = MaterialTheme.typography.headlineLarge,
                )
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
        else -> TopAppBar(
            title = {
                Box(modifier = Modifier.clickable(onClick = onBrandTap)) {
                    BrandRow()
                }
            },
            actions = {
                onGlobalSearchClick?.let { handler ->
                    IconButton(onClick = handler) {
                        Symbol(
                            name = "search",
                            contentDescription = stringResource(R.string.web_search_action),
                        )
                    }
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}



@Composable
private fun SearchEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "search_off",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 48.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.timeline_search_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(showingSaved: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = if (showingSaved) "bookmark" else "forum",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 56.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(if (showingSaved) R.string.timeline_empty_saved_title else R.string.timeline_empty_default_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (showingSaved) R.string.timeline_empty_saved_helper else R.string.timeline_empty_default_helper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Compact subscriber count formatter — Telegram convention. 12 345 → "12.3K", 1 050 000
 * → "1.1M". Round numbers drop the decimal so the label reads as "12K" rather than "12.0K".
 */
/** How many items from the end of the list trigger an older-history prefetch. */
private const val PAGINATION_PREFETCH_THRESHOLD = 6

/** How long after the last keystroke we issue the search round-trip. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Avatars in the "X нових постів" pill — same cap as the original VM-side limit. */
private const val MAX_PILL_BADGES = 3

/**
 * How many posts ahead of the first visible item to eagerly prefetch posters for. Tuned so
 * a single forward-flick (~3 cards in a 1080p phone viewport) lands on already-warm files.
 * Going much higher costs bandwidth on ramped-back scrolls; lower starts to feel laggy.
 */
private const val PREFETCH_AHEAD = 4

/**
 * Hard cap on inline-autoplay video duration we're willing to *speculatively* prefetch the
 * playback file for. Telegram's own autoplay threshold is 60 s, but at home-DC bitrates a
 * 60 s clip is ~10 MB — too much to gamble on a post the user may never scroll to. 30 s
 * keeps speculative cost ≤ ~5 MB per pre-warmed video, which on healthy Wi-Fi is sub-second
 * and on cellular is still tolerable. Longer autoplay clips fall back to the on-mount
 * download path; the user will see the standard loading overlay if needed.
 */
private const val INLINE_PREFETCH_MAX_DURATION_SEC = 30

/**
 * The fileIds whose **poster / preview** should be eagerly downloaded when this content is
 * about to enter viewport. Intentionally excludes playback files (full videos, audio,
 * documents) — those are too big for speculative download, and the user's tap is the right
 * trigger for them. The poster is what TdMediaImage paints behind the play badge / progress
 * overlay, so warming it is what makes "scrolled into view" feel instant.
 */
private fun PostContent.posterFileIds(): List<Int> = buildList {
    when (val content = this@posterFileIds) {
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Photo -> item.media.fileId?.let(::add)
                is AlbumItem.Video -> item.media.fileId?.let(::add)
                is AlbumItem.Animation -> item.media.fileId?.let(::add)
            }
        }
        is PostContent.Video -> content.media.fileId?.let(::add)
        is PostContent.Animation -> content.media.fileId?.let(::add)
        is PostContent.Document -> content.thumb?.fileId?.let(::add)
        is PostContent.Sticker -> {
            // Stickers are tiny (<100 KB) — pulling both thumb and the playback file
            // up-front means the inline animation starts the moment the post settles,
            // without the placeholder→media swap.
            content.thumb?.fileId?.let(::add)
            content.media.fileId?.let(::add)
        }
        is PostContent.AnimatedEmoji -> {
            content.thumb?.fileId?.let(::add)
            content.sticker?.fileId?.let(::add)
        }
        is PostContent.VideoNote -> content.thumb?.fileId?.let(::add)
        // Text/Audio/VoiceNote/Poll/Location/Contact/Dice/Checklist/Service/Expired/
        // Unsupported — no still preview to warm.
        else -> Unit
    }
}

/**
 * Playback file ids worth pre-warming for inline auto-play (short videos, GIF animations).
 * Honours the same spoiler/secret guards as the renderer — we never prefetch a file the
 * user hasn't explicitly opted into seeing yet, even speculatively. Returns the empty list
 * for content types that are *not* inline-played in the feed (long videos, photos, audio).
 */
private fun PostContent.playbackFileIds(): List<Int> = buildList {
    when (val content = this@playbackFileIds) {
        is PostContent.Video -> {
            if (!content.hasSpoiler && !content.isSecret &&
                content.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
            ) {
                add(content.playbackFileId)
            }
        }
        is PostContent.Animation -> {
            if (!content.hasSpoiler && !content.isSecret) {
                add(content.playbackFileId)
            }
        }
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Video -> {
                    if (!item.hasSpoiler && !item.isSecret &&
                        item.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
                    ) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Animation -> {
                    if (!item.hasSpoiler && !item.isSecret) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Photo -> Unit
            }
        }
        else -> Unit
    }
}

/**
 * One slot in the rendered feed. A [Single] is the standard one-post-per-row case; a [Thread]
 * is a Threads-style stacked pair where a reply and the post it's replying to are merged
 * into a single LazyColumn slot. `key` powers LazyColumn's [items] keying — different
 * shapes get different prefixes so a post toggling between Single↔Thread doesn't reuse the
 * old slot's saved state (scroll position of an album row, for example).
 */
@Immutable
sealed interface FeedItem {
    val key: String

    @Immutable
    data class Single(val post: TimelinePost) : FeedItem {
        override val key: String get() = "post_${post.chatId}_${post.id}"
    }

    @Immutable
    data class Thread(val parent: TimelinePost, val reply: TimelinePost) : FeedItem {
        override val key: String
            get() = "thread_${parent.chatId}_${parent.id}_${reply.chatId}_${reply.id}"
    }
}

/** Flatten a feed slot into its constituent posts (1 for Single, 2 for Thread). */
internal fun FeedItem.posts(): List<TimelinePost> = when (this) {
    is FeedItem.Single -> listOf(post)
    is FeedItem.Thread -> listOf(parent, reply)
}

/**
 * Two-pass grouping that collapses *fresh, consecutive* self-replies into [FeedItem.Thread]
 * pairs and leaves everything else as [FeedItem.Single] (with the existing inline quote
 * preview). The Threads-style stacked thread is reserved for the case it actually feels
 * like a continuation; older callbacks render as a regular post with a Twitter-style
 * quote pointing back to the original — which itself stays in the feed where it lives,
 * NOT consumed by the reply. The user reaches the original by tapping the quote.
 *
 * Two signals must both fire to thread:
 *   1. **Consecutive** — no other post of the same channel sits between the reply and the
 *      parent in the visible feed. A channel that posts unrelated B in the middle, then
 *      replies to old A, is doing a callback, not extending a thread.
 *   2. **Fresh** — `reply.date - parent.date ≤ THREAD_FRESH_WINDOW_MS` (1 h). Two-week-old
 *      parents thread with their replies looks like archaeology, not conversation.
 *
 * Cross-channel replies (parent in another channel) intentionally never thread — that's
 * a quote relationship, semantically a citation. They render as Single with the quote
 * preview pointing at the parent post.
 *
 * The feed is ordered newest-first; reply iterates BEFORE its parent. When threading
 * fires we consume both keys so the parent's later iteration is a no-op skip. When we
 * decide NOT to thread we leave the parent unconsumed — it shows as its own Single later,
 * unchanged, exactly where its date placed it.
 *
 * Long chains (A ← B ← C, all fresh & consecutive): iteration hits C first, consumes B as
 * its parent → Thread(B, C). A is then iterated and emitted as Single. B's inline quote
 * preview of A still renders inside the threaded slot (parent in a thread keeps its own
 * inline reply), giving a natural three-step visual without a triple-card stack.
 */
internal fun groupReplies(
    posts: List<TimelinePost>,
    freshWindowMs: Long = THREAD_FRESH_WINDOW_MS,
): List<FeedItem> {
    if (posts.size < 2) return posts.map(FeedItem::Single)
    // Index posts by every messageId they "own" — the canonical post.id PLUS every album
    // member id. Telegram albums are merged into a single TimelinePost whose id is the
    // oldest member's id, but a reply may target ANY member of the album (e.g. the 3rd
    // photo). Without indexing all member ids the lookup misses and the thread doesn't
    // form. This was the dominant cause of early "не ворк" reports for media-heavy channels.
    val byKey = HashMap<Pair<Long, Long>, TimelinePost>(posts.size * 2)
    val indexOf = HashMap<Pair<Long, Long>, Int>(posts.size * 2)
    for ((idx, p) in posts.withIndex()) {
        byKey[p.chatId to p.id] = p
        indexOf[p.chatId to p.id] = idx
        for (mid in p.albumMessageIds) {
            byKey[p.chatId to mid] = p
            indexOf[p.chatId to mid] = idx
        }
    }
    val consumed = HashSet<Pair<Long, Long>>(posts.size)
    val out = ArrayList<FeedItem>(posts.size)
    for ((idx, post) in posts.withIndex()) {
        val key = post.chatId to post.id
        if (key in consumed) continue
        val replyTo = post.reply
        val parent = if (replyTo != null) {
            byKey[replyTo.replyToChatId to replyTo.replyToMessageId]
        } else null
        // Same-channel only: cross-channel replies stay as Single with the quote preview
        // pointing at the parent — that's a citation, not a thread.
        if (parent != null && parent.chatId == post.chatId) {
            val parentKey = parent.chatId to parent.id
            if (parentKey != key && parentKey !in consumed) {
                val parentIdx = indexOf[parentKey] ?: -1
                val fresh = (post.date - parent.date) in 0..freshWindowMs
                // "Consecutive" = no other post of the same channel between reply (idx) and
                // parent (parentIdx > idx, since posts are newest-first). Posts of other
                // channels in between are fine; the user's experience is per-channel.
                val consecutive = parentIdx > idx && run {
                    var ok = true
                    for (i in (idx + 1) until parentIdx) {
                        if (posts[i].chatId == post.chatId) { ok = false; break }
                    }
                    ok
                }
                if (fresh && consecutive) {
                    out.add(FeedItem.Thread(parent = parent, reply = post))
                    consumed.add(parentKey)
                    consumed.add(key)
                    continue
                }
            }
        }
        out.add(FeedItem.Single(post))
        consumed.add(key)
    }
    return out
}

/**
 * How recent a parent must be (relative to the reply) to qualify as a "fresh thread".
 * Older parents render as a quote-card on the reply (Twitter-style), with the parent
 * staying as its own Single entry where its date placed it. 1 h matches the typical
 * news-channel cadence — anything slower than that reads as a callback, not continuation.
 */
private const val THREAD_FRESH_WINDOW_MS = 60L * 60L * 1000L

private fun formatSubscribers(count: Int): String {
    fun compact(value: Double, suffix: String): String {
        val rounded = ((value * 10).toLong()) / 10.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}$suffix"
        else "%.1f%s".format(rounded, suffix)
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count / 1_000.0, "K")
        else -> compact(count / 1_000_000.0, "M")
    }
}

