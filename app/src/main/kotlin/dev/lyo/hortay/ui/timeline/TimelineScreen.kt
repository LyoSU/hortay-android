@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import dev.lyo.hortay.data.isUnplayableVideo
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
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.BrandRow
import dev.lyo.hortay.ui.main.rememberFloatingTopBarBehavior
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import dev.lyo.hortay.ui.theme.asComposeShape
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// FlowPreview opt-in stays: Flow.debounce(Long) is still preview-marked in
// kotlinx-coroutines 1.10.1 even though Flow.debounce(Duration) graduated.
// Remove only when the Long overload is stabilised upstream.
/**
 * Mode-agnostic home-feed screen. Drives both the authenticated TDLib mode and
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
 *   - Comments tap — needs [commentsRepo]
 *   - Translation chip — needs [translations]
 *   - Channel actions (mute/unmute) — needs [channelActions]
 *   - Archived chats, view receipts — need [tdlibRepo]
 *
 * What's always present (works in both modes):
 *   - Pull-to-refresh, scroll gate, prefetch, bookmark, "new posts" pill,
 *     HortayTopBar (Medium) with BrandRow / saved title, empty state.
 *
 * Single-channel view is now a separate [ChannelScreen] composable backed by
 * [ChannelViewModel]. [MainScaffold] routes channel drill-ins there instead of
 * calling this composable with a [channelFilter] parameter. The split removes
 * 30+ `if (channelFilter != null)` branches that used to live here and gives
 * each channel visit its own independent lazy-list state and search state.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TimelineScreen(
    feed: dev.lyo.hortay.data.FeedSource,
    bookmarks: BookmarkStore,
    contentPadding: PaddingValues,
    showOnlyBookmarked: Boolean,
    onChannelOpen: (Long) -> Unit = {},
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
     *
     * Note: deep-link scroll to a specific channel's message is now owned by
     * [ChannelScreen] (which gets [scrollToMessage] directly from [MainScaffold]).
     * This parameter remains for the all-feed case where a deep link needs the
     * feed to scroll to a post that happens to be in the merged view already.
     */
    scrollToMessage: Pair<Long, Long>? = null,
    onScrollHandled: () -> Unit = {},
    /**
     * Fired exactly once when a deep-link scroll request resolves dead — TDLib's
     * `GetChatHistory` returned an empty window around the anchor (chat became
     * inaccessible / message deleted / FLOOD_WAIT exhausted the retry budget /
     * permission revoked between link share and tap). Scaffold wires it to a
     * snackbar ("Пост недоступний") so the user gets the same feedback
     * Telegram-Android offers via its "Message is no longer available" toast,
     * instead of staring at a frozen preview skeleton.
     */
    onScrollMissed: () -> Unit = {},
    /**
     * When non-null, a search action is shown in the default top bar (no filter,
     * not bookmarked-only) and tapping it invokes this callback. Guest mode wires
     * it to a cross-channel local search overlay; TDLib mode leaves this null and
     * keeps the global feed bar minimal.
     */
    onSearchClick: (() -> Unit)? = null,
    /**
     * Optional trailing badge rendered in the default top bar (the brand-row
     * variant). Currently used by guest mode to surface a persistent "Guest
     * mode" chip so the user doesn't forget they're unauthenticated. TDLib
     * mode passes null (default) and the slot collapses.
     */
    topBarBadge: (@Composable () -> Unit)? = null,
    /**
     * Process-wide cold-start gate, TDLib mode only. While in
     * [StartupCoordinator.Phase.Booting] the comments-thread prefetch
     * collector silently skips its work to keep the TDLib RPC pipe clear for
     * TDLib's own initial sync. Guest mode passes null and the collector runs
     * unguarded — it doesn't make TDLib RPC anyway. See [StartupCoordinator] KDoc.
     */
    startupPhase: kotlinx.coroutines.flow.StateFlow<dev.lyo.hortay.data.StartupCoordinator.Phase>? = null,
) {
    // viewModel() keys the cached instance by VM class only; the `factory`
    // parameter is consulted *just* on first creation. With both MainScaffold
    // (feed = postsRepository) and WebModeScaffold (feed = webFeedSource)
    // mounting TimelineScreen in the same Activity-scoped ViewModelStore, the
    // mode the user enters first wins — every subsequent switch reuses that
    // cached VM and silently observes the wrong feed (web posts ingested but
    // VM watches PostsRepository.posts, or vice versa). User-visible: "added a
    // channel in web mode, no posts appear" and "after sign-in I still see
    // anon posts". Distinct-key per feed class lets both VMs coexist; the
    // routing (MainActivity) already shows only one tree at a time, so they
    // never collide visually.
    val vm: TimelineViewModel = viewModel(
        key = feed.javaClass.name,
        factory = remember(feed, bookmarks) {
            viewModelFactory { initializer { TimelineViewModel(feed, bookmarks) } }
        },
    )
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

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

    // Pill is suppressed during a refresh: repo.refresh() replaces _posts, briefly making
    // the post-refresh delta look like "everything is new" until acceptPending() lands.
    // Without this guard the pill flashes a misleading huge count for ~1 frame.

    val scope = rememberCoroutineScope()
    // Single scroll-position holder. Scroll state preservation is now parent-owned:
    // MainScaffold wraps each TimelineScreen mount in a
    //   SaveableStateProvider(key = "feed-channel:<chatId>")   for per-channel views
    //   SaveableStateProvider(key = "feed-channel:__all__")    for the all-feed view
    // so this rememberLazyListState() automatically participates in the correct scope.
    // Each channel (and the all-feed) therefore gets its own independent list state —
    // the dual-state-with-key dance (globalListState / filterListState) is no longer
    // needed and was incorrect (it tried to do at the screen level what must happen at
    // the route level to be process-death-safe and tab-switch-safe).
    //
    // Guest / web mode mounts TimelineScreen without a per-channel provider but with a
    // top-level tab provider from WebModeScaffold, which is equivalent: the all-feed
    // state is preserved across tab switches.
    val listState = rememberLazyListState()

    // Cold-start scroll clamp. [listState] persists `firstVisibleItemIndex` across
    // process death via Compose's standard Saver (honoured by the parent's
    // SaveableStateProvider). After the 2026-05-11 cold-start rework the merged feed
    // shrank from up to MAX_FEED_SIZE=1000 (per-channel GetChatHistory × N) to ~1 post
    // per channel (Chat.lastMessage harvest). A saved index from a previous session can
    // now be ≥ the new feed size — Compose silently clamps to the last item, landing
    // the user at the OLDEST post in the feed instead of the top. Detect this on the
    // very first non-empty paint AFTER process start and scroll to top.
    //
    // Gating: a process-level [coldStartClampDone] flag (file-private below). Saveable
    // composition flags would be wrong here: rememberSaveable would survive process
    // death and skip the clamp on the very situation it's meant to handle, while plain
    // remember would re-arm on every TimelineScreen REMOUNT — including the in-process
    // drill-into-channel → pop-back case, where the feed is fresh in memory and the
    // user's restored scroll position is intentional. Process-level scope is the only
    // correct lifetime. Reset on logout via [TdClient.loggedOut] in the file footer
    // is unnecessary — a logout already wipes the feed and the next session starts
    // with a fresh process anyway (TDLib re-init).
    LaunchedEffect(Unit) {
        if (coldStartClampDone) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { posts.size }.first { it > 0 }
        if (listState.firstVisibleItemIndex >= posts.size) {
            listState.scrollToItem(0)
        }
        coldStartClampDone = true
    }

    // Pinned color-only scroll behavior — height transitions are owned by
    // [topBarOffsetPx] below so we don't fight two systems for the same dp.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // Twitter / Instagram floating-bar pattern: scroll delta directly drives
    // the destination-style bar's vertical offset, in sync with the user's
    // finger. No separate timed animation = no reflow jolt.
    //
    // Earlier iterations tried `AnimatedVisibility` wrapped around the topBar
    // slot — that runs a 150 ms shrink tween on the bar's height while the
    // user is mid-scroll. Scaffold re-measured the topBar slot every frame
    // of the tween, body's `padding(top = topPadding)` jumped along, the
    // FoldersBar / LazyColumn underneath shifted up at ~750 dp/s for those
    // 150 ms while the user's own scroll continued at ~200 dp/s. The
    // combined velocity discontinuity was the visible jank.
    //
    // The fix is to drive the bar's "exit" purely by scroll delta via a
    // [NestedScrollConnection]: every pixel the user pulls the content up
    // moves the bar one pixel further out of view, until the bar is fully
    // hidden. Scrolling back down at the top of the list passes leftover
    // delta through to reveal the bar — Twitter / Instagram canonical. The
    // bar's measured height shrinks via [Modifier.layout] in lockstep with
    // its visual offset so Scaffold's body padding tracks the same signal,
    // never a competing timeline.
    // Only the destination-style bars (home, bookmarks) participate in the
    // scroll-hide. Filter / search-inside-filter use [HortayTopBarSize.Compact]
    // and read as a tool stage with active input — those must stay pinned.
    // The behavior helper reads `enabled` live so toggling it doesn't
    // re-allocate the NestedScrollConnection.
    // The feed bar always participates in scroll-hide — there is no channel-filter
    // tool stage to pin it in place (that case is now owned by ChannelScreen).
    val floatingBar = rememberFloatingTopBarBehavior()
    val topBarFullHeightPx = floatingBar.fullHeightPx
    val topBarOffsetPx = floatingBar.offsetPx
    val topBarNestedScroll = floatingBar.nestedScroll
    // Reset the bar to fully visible whenever the user switches between
    // top-level destinations (home ↔ bookmarks) or in/out of channel filter
    // mode. Without this the bar stayed at its last hidden offset across
    // navigation, so a fresh destination would briefly orphan the user
    // looking at chrome they didn't expect to be missing.
    // Reset the bar to fully visible when switching between top-level destinations
    // (Home ↔ Saved). Without this the bar stays at its last hidden offset across
    // navigation.
    LaunchedEffect(showOnlyBookmarked) {
        topBarOffsetPx.floatValue = 0f
    }

    // One-shot "scroll to this messageId once it lands in the list". Two producers feed
    // this: in-app quote-card taps (see [PostInteractions.onQuotedSourceClick]) and
    // external deep links (the [scrollToMessage] parameter from MainScaffold). One
    // consumer — the LaunchedEffect below — resolves the target by scanning
    // displayedItems and clears the request on success. Cleared too on filter dismissal
    // (C2 fix) so a stale target from a previous channel doesn't yank the user later.
    var pendingScrollToMessage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // (chatId, messageId) of the post we just scrolled to via a deep link / quote tap.
    // Drives a brief surface-tint highlight on that PostCard so the user can locate it
    // post-scroll. Auto-clears after [HIGHLIGHT_DURATION_MS]; null when no highlight is
    // active.
    var highlightedPostKey by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(scrollToMessage) {
        if (scrollToMessage != null) {
            pendingScrollToMessage = scrollToMessage
            onScrollHandled()
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
    // (no pill needed if the user is already looking at the top of the feed). MUST be
    // keyed on `listState`: the active list flips between [globalListState] and
    // [filterListState] on `channelFilter` toggle (see selection above), and a bare
    // `remember { derivedStateOf {...} }` would capture the first listState forever —
    // so after entering a channel filter, `atTop` would still reflect the GLOBAL feed's
    // scroll position. The "новi пости" pill auto-accept gate and pill visibility both
    // read this flag, so the bug surfaced as the pill behaving for the wrong scope.
    // Mirrors the keying applied to `scrollGate` and `prefetchAnchor` further below.
    val atTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
        }
    }

    // Twitter-style "tap home twice": first tap scrolls to top, second one (already at top)
    // refreshes. The trigger is a monotonic timestamp from the parent, so a single bump
    // produces a single reaction.
    //
    // Gotcha: `LaunchedEffect(homeTapTrigger)` by itself fires on every TimelineScreen
    // REMOUNT — the fresh effect has no memory of the previous key value. If the user
    // had ever tapped home in the session (`homeTapTrigger != 0`), then swapping
    // to another tab (Channels / Saved / Profile) and back to Feed remounts this
    // Composable and re-fires the effect with the same stale trigger value, yanking
    // the user to the top of the feed. Same class of bug we patched for `scope_filter`
    // and the cold-start clamp — track the last-handled timestamp in `rememberSaveable`
    // so only an actual NEW bump (re-tap on Home from the FloatingNavBar / BrandRow)
    // produces a reaction.
    var lastHandledHomeTap by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(homeTapTrigger) {
        if (homeTapTrigger == 0L) return@LaunchedEffect
        if (homeTapTrigger == lastHandledHomeTap) return@LaunchedEffect
        lastHandledHomeTap = homeTapTrigger
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atTop) vm.refresh() else listState.animateScrollToItem(0)
    }

    // Switching folders jumps to the top of the feed — "show me the top of this
    // folder" is the expected behaviour. The gotcha: `LaunchedEffect(scope_filter)`
    // by itself fires on every TimelineScreen REMOUNT (drilling into a channel
    // then popping back unmounts/remounts this Composable; the new LaunchedEffect
    // has no memory of the old key's value, so it fires even when the scope
    // didn't actually change). That yanked the user to the top of the feed
    // whenever they returned from a channel, regardless of where they were
    // scrolled when they drilled in. Fix: track the previously-observed scope
    // as a `rememberSaveable` String key — only scroll when the new key DIFFERS
    // from the saved prior value AND a prior value exists (so initial mount
    // does NOT scroll).
    val scopeKey = remember(scope_filter) {
        when (val s = scope_filter) {
            FilterScope.All -> "all"
            FilterScope.Archive -> "archive"
            is FilterScope.Folder -> "folder:${s.id}"
        }
    }
    var lastScopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(scopeKey) {
        val previous = lastScopeKey
        lastScopeKey = scopeKey
        if (previous != null && previous != scopeKey) {
            listState.scrollToItem(0)
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

    val visiblePosts = remember(posts, scopePredicate, bookmarkedKeys, showOnlyBookmarked) {
        buildList {
            posts.forEach { p ->
                if (showOnlyBookmarked && p.bookmarkKey() !in bookmarkedKeys) return@forEach
                if (p.content is PostContent.Service) return@forEach
                if (p.content is PostContent.ExpiredMedia) return@forEach
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

    // Source of truth for "what the LazyColumn is currently rendering". The all-feed and
    // bookmarks surfaces have no in-screen search (global search in web mode is a separate
    // overlay; TDLib in-channel search now lives in ChannelScreen). feedItems is always
    // used here.
    val displayedItems: List<FeedItem> = feedItems

    // LaunchedEffect's block freezes its captures on the keys-last-changed composition
    // (Compose's [remember] under the hood holds the original lambda), so subsequent
    // changes to [displayedItems] aren't seen by the long-running effect bodies below.
    // Routing the read through [rememberUpdatedState] gives us a [State] handle whose
    // .value is refreshed on every recomposition without restarting any effect — the
    // same pattern this composable already uses for [postsState], [translationsState],
    // [bookmarkedState] further down. Critical for the read-mark / focus-chat effects
    // because the feed list ref churns with every UpdateMessageInteractionInfo (~30 Hz
    // on a busy news day): a stale capture would have us ack the wrong posts as
    // forceRead=true and (worse) OpenChat the wrong chat.
    val displayedItemsState = rememberUpdatedState(displayedItems)

    // Resolve the queued "scroll to messageId" once the target row appears. Keyed on
    // [pendingScrollToMessage] alone (NOT on displayedItems) so a busy feed doesn't
    // restart the effect dozens of times per second on every list mutation — instead we
    // use snapshotFlow inside to react to displayedItems changes lazily, and stop
    // collecting as soon as we land the scroll. The lookup matches both the post's
    // canonical id AND any of its album member ids — TimelinePost collapses an album
    // into a single row keyed on the oldest member, but a quote card may point at any
    // member.
    //
    // Old-message handling: when the target is below the head load that
    // [loadChannelHistory] fetched (deep-linked old post, quote-reply to an old anchor)
    // it won't appear in displayedItems no matter how long we wait. Per TDLib docs we
    // ask for a window CENTERED on the anchor — see [PostsRepository.loadHistoryAround]
    // — exactly once per pending target. The follow-up snapshot tick then lands the
    // scroll. Single-shot guard ([requestedAroundLoad]) keeps a busy feed from re-firing
    // the RPC on every list mutation.
    LaunchedEffect(pendingScrollToMessage) {
        val (chatId, messageId) = pendingScrollToMessage ?: return@LaunchedEffect
        var requestedAroundLoad = false
        // snapshotFlow must read State — a plain local `val displayedItems` is captured
        // by value and never re-evaluated, so subsequent posts-arrived recompositions
        // would never re-emit. Reading through [displayedItemsState] (a
        // rememberUpdatedState wrapper) gives us a proper State.value read that the
        // snapshot system tracks.
        androidx.compose.runtime.snapshotFlow { displayedItemsState.value }
            .collect { items ->
                val idx = items.indexOfFirst { item ->
                    item.posts().any { p ->
                        p.chatId == chatId && (p.id == messageId || messageId in p.albumMessageIds)
                    }
                }
                if (idx >= 0) {
                    // Jump instantly via [scrollToItem] rather than animateScrollToItem.
                    // For a deep-linked old post the target row can be 200+ items down;
                    // animating through every intermediate row took seconds and made the
                    // app feel like it was searching for the post. Telegram-Android also
                    // hard-jumps on a link click. The highlight tint (set BEFORE the jump
                    // so it's composed by the time the user sees the new viewport) is
                    // what tells the eye where to look on arrival.
                    highlightedPostKey = chatId to messageId
                    listState.scrollToItem(idx)
                    pendingScrollToMessage = null
                    return@collect
                }
                if (!requestedAroundLoad) {
                    requestedAroundLoad = true
                    // TDLib best practice for "open by link to an out-of-cache message":
                    // GetChatHistory(from = anchor, offset = -limit/2). PostsRepository
                    // wraps that and returns false when the chat is inaccessible /
                    // network failed / the channel returned an empty window. In any of
                    // those cases there's nothing more to wait for — clear the pending
                    // target so the snapshot collector exits, mirroring Telegram-
                    // Android's "Message is no longer available" UX instead of leaving
                    // the user staring at a frozen skeleton.
                    val landed = tdlibRepo?.loadHistoryAround(chatId, messageId) ?: false
                    if (!landed) {
                        pendingScrollToMessage = null
                        onScrollMissed()
                        return@collect
                    }
                }
            }
    }

    // Auto-clear: the highlight pulses for [HIGHLIGHT_DURATION_MS] then fades away.
    // Cleared one-shot per landing so the next scroll-to-message can re-trigger.
    LaunchedEffect(highlightedPostKey) {
        if (highlightedPostKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(HIGHLIGHT_DURATION_MS)
        highlightedPostKey = null
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
                    // Guest mode posts carry CDN avatar URLs; TDLib posts
                    // leave this null and use fileId/thumb.
                    avatarUrl = anchor.avatarUrl,
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
                .debounce(1200)
                .collect { indices ->
                    // Read latest list via [displayedItemsState] — captures
                    // [feedItems] directly here would freeze on the keys-change
                    // composition and miss subsequent feed mutations (~30 Hz on busy
                    // news days). Indices come from the actual layoutInfo so they
                    // already reference the latest rendered list — pairing them with
                    // a stale snapshot would prefetch threads for posts that have
                    // since shifted out of those positions.
                    val snapshot = displayedItemsState.value
                    // Cap how many threads we warm per viewport-stable window. Each
                    // [CommentsRepository.prefetchThread] fires
                    // [TdApi.GetMessageProperties] + [TdApi.GetMessageThread] +
                    // [TdApi.GetMessageThreadHistory] on the same TDLib RPC pipe that
                    // also carries the user's interactive calls (taps, ack-on-dwell,
                    // chat presence). With 5-10 visible posts having comments, the
                    // burst flooded the pipe in the *exact* moment the user might tap
                    // a different post's comments — surfacing as the "сomments
                    // sometimes load slowly" symptom. Per Levin (tdlib/td#3019), TDLib
                    // serialises RPC requests through a single per-client queue, so
                    // unbounded fan-out blocks the head of the queue for the duration
                    // of the burst. Top-3 visible posts is the **plurality** of
                    // dwell-targets a user is likely to tap into without scrolling
                    // again — covers the warm-up benefit without owning the pipe.
                    // Cold-start gate: skip the prefetch entirely while we're in
                    // the post-auth storm window. The same viewport-stable will
                    // re-emit naturally as scroll / dwell continues, and by then
                    // the gate has flipped to Active and the prefetch lands without
                    // contention. See [StartupCoordinator] KDoc.
                    if (startupPhase?.value == dev.lyo.hortay.data.StartupCoordinator.Phase.Booting) {
                        return@collect
                    }
                    // commentCount > 0 (not just != null): a channel-with-discussion
                    // post that has zero replies still carries a non-null
                    // commentCount (TDLib populates replyInfo with `replyCount = 0`),
                    // and prefetching it costs 2 wasted RPCs (GetMessageProperties +
                    // GetMessageThread) for a thread guaranteed to be empty. Most
                    // posts on commenting channels are this case — typical post
                    // collects a few replies on the first hour and zero forever
                    // after. The old filter sprayed prefetch across them all and
                    // was the dominant share of the first-launch RPC volume.
                    indices.flatMap { snapshot.getOrNull(it)?.posts().orEmpty() }
                        .asSequence()
                        .filter { (it.commentCount ?: 0) > 0 }
                        .take(COMMENTS_PREFETCH_LIMIT)
                        .forEach { post ->
                            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                            commentsRepo.prefetchThread(post.chatId, ids)
                        }
                }
        }
    }

    // Read-state acks: posts that stayed visible long enough get marked as seen via
    // viewMessages(forceRead=true). Bumps server-side view counters AND advances
    // lastReadInboxMessageId so the unread badge in the official Telegram client
    // clears as the user reads here — see PostsRepository.viewMessages doc for the
    // maintainer-aligned reasoning.
    //
    // Why collectLatest + delay instead of debounce(): a delay inside collectLatest is
    // cancelled on every viewport mutation, giving us a true "viewport stable for
    // READ_DWELL_MS" gate. debounce() also throttles, but it doesn't reset on shift
    // when the new emission lands inside the prior debounce window — collectLatest is
    // the cleaner primitive for "user must pause this long". The dwell guard matters
    // with forceRead=true because a fast 200-channel flick would otherwise zero out
    // unread badges for everything that briefly flickered past the screen.
    //
    // ackedRead deduplicates so we don't re-issue viewMessages for the same posts on
    // every viewport mutation. TDLib filters re-acks server-side anyway (issue #136),
    // but skipping the round-trip altogether is cheaper. Cap is implicit: feed size
    // is bounded by MAX_FEED_SIZE (~1000), so the set stays small.
    val ackedRead = remember(tdlibRepo) { HashSet<Pair<Long, Long>>() }
    if (tdlibRepo != null) {
        LaunchedEffect(listState, tdlibRepo) {
            androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collectLatest { indices ->
                    if (indices.isEmpty()) return@collectLatest
                    kotlinx.coroutines.delay(READ_DWELL_MS)
                    // Read the LATEST list via [displayedItemsState] — see the State
                    // wrapper's KDoc for why feedItems-by-closure would be stale here.
                    val snapshot = displayedItemsState.value
                    if (snapshot.isEmpty()) return@collectLatest
                    val visible = indices.flatMap { idx -> snapshot.getOrNull(idx)?.posts().orEmpty() }
                    val fresh = visible.filter { (it.chatId to it.id) !in ackedRead }
                    if (fresh.isEmpty()) return@collectLatest
                    // Populate ackedRead BEFORE dispatching the suspending ack. If the
                    // dispatch coroutine is cancelled mid-batch we must not leave half
                    // the chats acked locally and the others not — that produces
                    // inconsistent re-issue behaviour on the next dwell. The TDLib call
                    // itself is detached into [scope] so a viewport change a frame
                    // later doesn't kill the in-flight ack: collectLatest cancel only
                    // affects this body, not coroutines launched from the outer scope.
                    fresh.forEach { ackedRead.add(it.chatId to it.id) }
                    val grouped = fresh.groupBy { it.chatId }
                    scope.launch {
                        grouped.forEach { (chatId, group) ->
                            tdlibRepo.viewMessages(chatId, group.map { it.id })
                        }
                    }
                }
        }
    }

    // Focus-chat tracking: keep the chat of the topmost-visible post OpenChat'd while
    // the user is dwelling on it, then transition cleanly to the next chat as they
    // scroll. This is what makes reactions / views / comment-counts stream live for
    // the post the user is actually looking at — per tdlib/td#2312, those updates
    // arrive only for chats that are currently opened in TDLib. The maintainer's
    // canonical pattern is "usually one chat opened" (tdlib/td#2695), so we honour
    // that strictly: at most ONE merged-feed chat is open at any time, and it's the
    // chat of whatever post sits at the top of the viewport.
    //
    // Hysteresis: FOCUS_DWELL_MS keeps us from rapidly cycling open/close on a quick
    // scroll. collectLatest cancels the delay on every viewport top change — only a
    // chat that wins the topmost slot AND holds it for FOCUS_DWELL_MS gets opened.
    //
    // Cleanup: try/finally with NonCancellable on the close so a fast screen exit
    // still flushes CloseChat.
    if (tdlibRepo != null) {
        LaunchedEffect(listState, tdlibRepo) {
            var opened: Long? = null
            try {
                androidx.compose.runtime.snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                }
                    .distinctUntilChanged()
                    .collectLatest { topIdx ->
                        if (topIdx == null) return@collectLatest
                        kotlinx.coroutines.delay(FOCUS_DWELL_MS)
                        // Latest displayed list — same staleness reason as Effect 1.
                        val items = displayedItemsState.value
                        val topChat = items.getOrNull(topIdx)?.posts()?.firstOrNull()?.chatId
                            ?: return@collectLatest
                        if (topChat == opened) return@collectLatest
                        // Atomic close+open via NonCancellable: ChatPresence decrements
                        // the local refcount BEFORE issuing the network CloseChat, so a
                        // mid-flight cancellation here would otherwise leak an opened
                        // chat in TDLib (refcount 0 locally, but TDLib never received
                        // CloseChat). Pinning the swap means a subsequent collectLatest
                        // cancel waits for both calls to land before letting the next
                        // emission start its own swap. NonCancellable doesn't block
                        // forever — viewMessages and OpenChat/CloseChat are bounded
                        // RPCs and ChatPresence wraps the send in runCatching anyway.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            opened?.let { prev -> tdlibRepo.closeChat(prev) }
                            tdlibRepo.openChat(topChat)
                            opened = topChat
                        }
                    }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    opened?.let { tdlibRepo.closeChat(it) }
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
    val onChannelOpenState = rememberUpdatedState(onChannelOpen)
    val onOpenCommentsState = rememberUpdatedState(onOpenComments)

    // Explicit-tap read ack: any deliberate "open this post" action (open comments,
    // open media viewer, open in Telegram) marks the post as read immediately, instead
    // of waiting for the dwell threshold. A tap is a stronger signal than dwell — if
    // the user fast-tapped and bounced under READ_DWELL_MS, we still want the unread
    // badge in the official client to clear. ackedRead is the same set the dwell
    // effect populates, so a post that was tap-acked never re-fires from the dwell
    // pass and vice-versa. Captured via rememberUpdatedState so the closure inside
    // [interactions] always sees the current ackedRead reference (which is stable
    // across recomposition, but this keeps the wiring uniform with the rest of the
    // captured-state pattern in this composable).
    val markPostReadState = rememberUpdatedState({ post: TimelinePost ->
        val repo = tdlibRepo
        if (repo != null) {
            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
            val unacked = ids.filter { (post.chatId to it) !in ackedRead }
            if (unacked.isNotEmpty()) {
                unacked.forEach { ackedRead.add(post.chatId to it) }
                scope.launch { repo.viewMessages(post.chatId, unacked) }
            }
        }
    })

    // Key on every long-lived dependency the lambdas close over. Without these
    // keys, a logout/login that swaps `feed` (and with it the new vm instance,
    // a fresh PostsRepository, a re-mounted MediaViewerHost) would leave the
    // already-cached interactions object pointing at the previous account's
    // ViewModel + repos — every callback would silently route to torn-down
    // state. The same root cause as the per-account-state-survived-logout fix
    // on the data layer, just on the UI side.
    val interactions = remember(
        vm,
        viewer,
        translations,
        channelActions,
        tdlibRepo,
        feed,
        bookmarks,
    ) {
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
                markPostReadState.value(post)
                // Unplayable videos (currently guest-mode "Media is too big" posts
                // where t.me drops the <video src>) hand the tap straight to the
                // Telegram client. The viewer would otherwise open with an empty
                // remoteVideoUrl and ExoPlayer would spin trying to prepare nothing.
                val items = (post.content as? dev.lyo.hortay.data.PostContent.PhotoAlbum)?.items.orEmpty()
                if (items.getOrNull(idx)?.isUnplayableVideo == true) {
                    scope.launch { dev.lyo.hortay.ui.actions.PostActions.openInTelegram(context, tdlibRepo, post) }
                } else {
                    viewer.openFor(post.content, idx)
                }
            },
            onChannelClick = { post -> onChannelOpenState.value(post.chatId) },
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
                // Always open the channel when we know the chatId — ChannelScreen owns
                // the preview/skeleton while TDLib loads the history for non-subscribed
                // channels.
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId)
                    !sourceHandle.isNullOrBlank() -> {
                        // Username-only origins (TDLib didn't include the resolved id) —
                        // route through LocalUriHandler so HortayUriHandler resolves
                        // the handle via SearchPublicChat and lands the ChannelScreen path.
                        uriHandler.openUri("https://t.me/${sourceHandle.removePrefix("@")}")
                    }
                }
            },
            onQuotedSourceClick = { post ->
                // In-app "open the original" — open the channel (no Intent chooser bounce)
                // and queue a scroll-to-target so the caller (MainScaffold) can pass it to
                // the new ChannelScreen via pendingScrollTarget.
                post.reply?.let { r ->
                    onChannelOpenState.value(r.replyToChatId)
                    pendingScrollToMessage = r.replyToChatId to r.replyToMessageId
                }
            },
            onBookmarkClick = { post -> vm.toggleBookmark(post) },
            onShareClick = { post -> scope.launch { PostActions.share(context, tdlibRepo, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post ->
                markPostReadState.value(post)
                scope.launch { PostActions.openInTelegram(context, tdlibRepo, post) }
            },
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
            translateEnabled = translations != null,
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
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
            },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(topBarNestedScroll),
        topBar = {
            // Two-zone bar:
            //   1. Persistent status-bar strip (always visible, painted with
            //      the app's background) — keeps the system status bar text
            //      legible against a stable backdrop regardless of how far
            //      the bar content has slid. Sits OUTSIDE the layout-shrinking
            //      block so it never moves.
            //   2. Sliding bar content — the [TimelineTopBar] itself with
            //      `windowInsets = WindowInsets(0)`, so its internal status-
            //      bar padding doesn't double up with our zone-1 strip. The
            //      [Modifier.layout] wrapper shrinks measured height in
            //      lockstep with [topBarOffsetPx] so Scaffold's body padding
            //      tracks the same signal — no separate animation timeline.
            //
            // Earlier iterations left status-bar handling inside the
            // [HortayTopBar] (default windowInsets) and clipped at
            // the layout-shrinker's bounds. That clipped the bar's outline
            // correctly but the bar's INTERNAL coordinate system put title
            // content right after its 24 dp status-bar pad — when offset = -50
            // shifted the placeable up, title pixels ended up at screen y =
            // 0..24, leaking onto the status bar. Splitting status-bar into
            // its own non-moving zone fixes that root cause.
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                )
                Box(modifier = Modifier
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        // Capture the bar's natural full height the first
                        // time we see a non-zero measure; the
                        // [topBarNestedScroll] connection reads this to know
                        // how far the bar can travel.
                        if (topBarFullHeightPx.floatValue == 0f && placeable.height > 0) {
                            topBarFullHeightPx.floatValue = placeable.height.toFloat()
                        }
                        val offset = topBarOffsetPx.floatValue.toInt()
                        val height = (placeable.height + offset).coerceAtLeast(0)
                        layout(placeable.width, height) {
                            placeable.placeRelative(0, offset)
                        }
                    }
                ) {
                    TimelineTopBar(
                        showOnlyBookmarked = showOnlyBookmarked,
                        onBrandTap = onBrandTap,
                        onGlobalSearchClick = onSearchClick,
                        topBarBadge = topBarBadge,
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // M3 Expressive pull-to-refresh: the default indicator slot is the
            // 1.4 circular spinner; `PullToRefreshDefaults.LoadingIndicator()` is
            // the 1.5 expressive variant that morphs through the same polygon
            // cycle as the inline `LoadingIndicator` (Circle → SoftBurst →
            // Cookie9 → Pill → Sunny). Single visual idiom for "system loading"
            // across pull-to-refresh, auth submit, comments thread load, and
            // settings clear-cache.
            val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
                        .LoadingIndicator(
                            state = pullState,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                },
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
                    if (!showOnlyBookmarked && hasFolderUi) {
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

                    if (visiblePosts.isEmpty() && !refreshing) {
                        EmptyState(showOnlyBookmarked)
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
                        // the next [PREFETCH_AHEAD] posts' posters at
                        // [DownloadPriority.Prefetch] (8). Visible posts self-ensure at
                        // [DownloadPriority.VisibleMedia] (16) via [rememberMediaBinding] —
                        // the priority gap between the lanes is what makes TDLib's
                        // priority-aware scheduler serve visible first regardless of
                        // [Levin's LIFO same-priority rule](https://github.com/tdlib/td/issues/786).
                        // Gated on scroll-settled (prefetchAnchor=null while scrolling)
                        // so we don't fire ensure() while the user is mid-fling — that
                        // would saturate TDLib's per-DC pool with cards about to scroll
                        // past the cancel-debounce window anyway.
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
                                // All prefetch at [DownloadPriority.Prefetch]. Visible posts
                                // already self-ensure at [DownloadPriority.VisibleMedia] via
                                // [rememberMediaBinding], so promoting prefetched neighbours
                                // to the same lane just adds LIFO contention against what
                                // the user is actively staring at — the very thing the
                                // logcat audit (PREFETCH_AHEAD=4 era) caught as multi-second
                                // bytes=0 stalls on user-facing files. Per Levin
                                // (tdlib/td#2179): "the most recently seen file will be
                                // downloaded first" — at the same priority that means our
                                // newest prefetch jumps the queue ahead of the post on
                                // screen. The fix is priority *separation*, not bump:
                                // visible files own lane 16, prefetch owns lane 8, TDLib's
                                // priority-aware scheduler then serves visible first
                                // regardless of LIFO ordering inside each lane.
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

                        // Viewport-centre key: the item whose centre is closest to
                        // the visible viewport centre. Each item composes its own
                        // [LocalIsCenteredItem] = (this.key == centeredItemKey),
                        // which [rememberMediaBinding] reads to promote
                        // [DownloadPriority.VisibleMedia] callers to
                        // [DownloadPriority.VisibleCenter] for the dominant card.
                        // On a tight TDLib pool (mobile/roaming, ~4 active slots
                        // per [tdlib/td#786](https://github.com/tdlib/td/issues/786))
                        // this guarantees the user-staring card always grabs a
                        // slot first regardless of LIFO ordering inside lane 16.
                        // `derivedStateOf` skips recomposition when the centre
                        // hasn't actually changed across snapshot reads.
                        val centeredItemKey by remember(listState) {
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
                                    // Per-item State so a centre flip recomposes
                                    // only the two affected items (old centre →
                                    // false, new centre → true) instead of the
                                    // whole feed.
                                    val isCentered = remember { mutableStateOf(false) }
                                    isCentered.value = item.key == centeredItemKey
                                    val highlighted = highlightedPostKey?.let { (cid, mid) ->
                                        item.posts().any { p ->
                                            p.chatId == cid && (p.id == mid || mid in p.albumMessageIds)
                                        }
                                    } == true
                                    CompositionLocalProvider(
                                        LocalIsCenteredItem provides isCentered,
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

            // Floating "X нових постів" pill. Hidden in the Saved tab, while a
            // refresh is in flight (transient delta), and while the user is already
            // at the top of the feed (we auto-accept pending in that case).
            val pillVisible = !showOnlyBookmarked &&
                !refreshing && !atTop && scopedPendingChannels.isNotEmpty()
            // Folder chips occupy the top ~56dp inside the same Box; offset the pill
            // so it lands just below them instead of overlapping.
            val chipsVisible = !showOnlyBookmarked
            val pillTopPadding = if (chipsVisible) 64.dp else 8.dp
            val pillSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            val pillEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedVisibility(
                visible = pillVisible,
                enter = slideInVertically(pillSpatial) { -it } + fadeIn(pillEffects),
                exit = slideOutVertically(pillSpatial) { -it } + fadeOut(pillEffects),
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

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    showOnlyBookmarked: Boolean,
    onBrandTap: () -> Unit,
    onGlobalSearchClick: (() -> Unit)?,
    topBarBadge: (@Composable () -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    // Status-bar insets are owned by the persistent zone-1 strip in the
    // Scaffold's topBar slot — passing [WindowInsets] of 0 here prevents the
    // bar from doubling up on top padding (and keeps its content from
    // travelling into the system status-bar area when the layout shrinker
    // pushes the bar upward on scroll).
    val barInsets = WindowInsets(0)
    // Both destinations (Bookmarks and Home) are top-level: M3 Expressive
    // canon uses the Medium-size flexible bar — larger title on first paint
    // that collapses to compact 64 dp on scroll. Tool stages (search,
    // channel filter) now live in the dedicated [ChannelScreen].
    if (showOnlyBookmarked) {
        HortayTopBar(
            title = stringResource(R.string.timeline_saved_tab),
            size = HortayTopBarSize.Medium,
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
        )
    } else {
        HortayTopBar(
            title = {
                Box(modifier = Modifier.clickable(role = Role.Button, onClick = onBrandTap)) {
                    BrandRow()
                }
            },
            size = HortayTopBarSize.Medium,
            actions = {
                onGlobalSearchClick?.let { handler ->
                    IconButton(onClick = handler) {
                        Symbol(
                            name = "search",
                            contentDescription = stringResource(R.string.web_search_action),
                        )
                    }
                }
                // Trailing slot for mode-specific chips (e.g. guest-mode
                // badge). Rendered after the search action so it sits at the
                // edge of the bar, where users expect status indicators.
                topBarBadge?.invoke()
            },
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
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
        // Bookmark hero uses the Heart polygon (matches the bookmark-active morph token);
        // empty-feed hero uses Flower for a friendly "nothing here yet, but pleasantly".
        ExpressiveEmptyHero(
            symbol = if (showingSaved) "bookmark" else "forum",
            shape = if (showingSaved) dev.lyo.hortay.ui.theme.HortayExpressive.BookmarkSelected
                    else dev.lyo.hortay.ui.theme.HortayExpressive.EmptyStateMask,
        )
        Spacer(Modifier.height(20.dp))
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
 * Expressive empty-state hero badge: a 96 dp polygon-masked tile in `secondaryContainer`
 * with a 40 dp glyph centred inside. The polygon (Flower / Heart / Cookie7) is the
 * single largest expressive form on screen at this moment, which is the canonical use
 * Google designers reach for in the M3 Expressive guidelines: hero for personality,
 * dense surfaces stay calm. Pre-allocates the Compose Shape via `asComposeShape` so
 * recompositions don't allocate a fresh PolygonShape per frame.
 */
@Composable
internal fun ExpressiveEmptyHero(
    symbol: String,
    shape: androidx.graphics.shapes.RoundedPolygon,
) {
    val composeShape = shape.asComposeShape()
    // No clip — Flower / Heart / Cookie polygons have dips that would slice the
    // central glyph. Painting the polygon as the backdrop only keeps the icon
    // fully visible while the silhouette reads as the expressive shape.
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, composeShape),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 40.dp,
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
 * Viewport-stable dwell required before a post counts as "read". Triggers
 * `viewMessages(forceRead=true)`, which advances `lastReadInboxMessageId` and clears
 * the unread badge in the official Telegram client. 1 s matches Telegram-Android's own
 * scroll-IDLE read threshold and the IAB-style "considered viewed" minimum used by
 * Twitter / Instagram. Lower would zero out badges on incidental flicker; higher
 * would feel laggy ("I read this 2 s ago, why is it still bold in my other client?").
 */
private const val READ_DWELL_MS = 1000L

/**
 * Viewport-stable dwell before we promote the topmost post's chat to OpenChat in
 * TDLib. Per tdlib/td#2312 only opened chats receive realtime reaction / view-counter
 * / comment-count updates, so this is what makes interaction info come alive on the
 * post the user is actually looking at. Slightly longer than [READ_DWELL_MS] — this
 * one carries side effects (TDLib starts streaming updates and may fetch sponsored
 * messages), so we wait for the dwell to feel deliberate rather than incidental.
 */
private const val FOCUS_DWELL_MS = 1500L

/**
 * How long the surface-tint "you just landed here" highlight lingers on the post a
 * deep link / quote tap scrolled to. Long enough for the eye to find it after the
 * scroll animation settles (~300ms) but short enough to fade before the user starts
 * scrolling away. Matches the ~2 s glow Telegram-iOS paints on the linked-to bubble.
 */
private const val HIGHLIGHT_DURATION_MS = 2200L

/**
 * How many posts ahead of the first visible item to eagerly prefetch posters for.
 *
 * Tuned **down to 2** after live logcat showed pool saturation under the previous
 * value of 4. Per Levin (tdlib/td#786), TDLib serves same-priority `DownloadFile`
 * requests in **reverse order of issue** (LIFO): "files with the same priority are
 * downloaded in the reverse order of downloadFile requests... downloading recently
 * requested files first." Combined with TDLib's per-DC active-slot pool (~4
 * simultaneous), every additional ensure() at the same priority pushes earlier
 * (often *visible*) files toward the back of the queue. The watchdog reissue
 * partially compensates by re-promoting stuck files, but the underlying
 * contention is what costs end-user time. Value 2 matches Telegram-Android's
 * empirical neighbour-cell prefetch window.
 *
 * Lower starts to feel laggy on a forward flick; higher reintroduces the LIFO
 * eviction we just fixed. 2 is the sweet spot for a phone viewport rendering
 * 3-5 cards at a time.
 */
private const val PREFETCH_AHEAD = 2

/**
 * Cap on `prefetchThread` fan-out per viewport-stable burst. With ~5-10 visible
 * posts that have comments, fan-out without a cap fired
 * [TdApi.GetMessageProperties] + [TdApi.GetMessageThread] +
 * [TdApi.GetMessageThreadHistory] for each at once on TDLib's single RPC queue —
 * the user's interactive comments tap got stuck behind the burst until it
 * cleared (~hundreds of ms on a slow DC).
 *
 * Tightened 3 → 1 in the post-cold-start follow-up (2026-05-11): real-device
 * testing showed that a fast bottom→top scroll with several micro-pauses
 * stacked 3 × 3 = 9 RPCs per pause on top of the cold-start album-coalesce
 * tail, saturating the per-DC active-slot pool and surfacing FLOOD_WAIT on
 * subsequent OpenChat calls. With cap=1 we warm only the top-most visible
 * post per stable window; the user's tap on a neighbouring post pays a
 * single cache miss instead of fighting the queue.
 */
private const val COMMENTS_PREFETCH_LIMIT = 1

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
internal fun PostContent.posterFileIds(): List<Int> = buildList {
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
internal fun PostContent.playbackFileIds(): List<Int> = buildList {
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

/**
 * Process-level "cold-start clamp already ran" flag. See the [LaunchedEffect(Unit)]
 * usage near line ~230 for the full rationale. JVM-volatile so a concurrent flip from
 * another TimelineScreen instance (Feed + Saved tabs both mount this Composable) is
 * visible without locking. Reset only by process death — the correct scope, because
 * the clamp's purpose is "saved index might be stale from a previous session"; that
 * concern only exists at process boot, never on in-process drill/pop or tab swap.
 */
@Volatile
private var coldStartClampDone: Boolean = false

internal fun formatSubscribers(count: Int): String {
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

