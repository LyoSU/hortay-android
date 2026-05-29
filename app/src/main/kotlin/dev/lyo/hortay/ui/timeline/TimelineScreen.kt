@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.isUnplayableVideo
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.isUnreadAt
import dev.lyo.hortay.data.isUnreadIn
import dev.lyo.hortay.data.orderedFor
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.ui.archive.PostRevisionSheet
import kotlinx.collections.immutable.persistentListOf
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.main.rememberFloatingTopBarBehavior
import androidx.compose.ui.draw.clipToBounds
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.FlowPreview
import androidx.compose.foundation.gestures.ScrollableDefaults
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Called when the user taps a post's channel header, a forward-source chip,
     * or an inline reply / quote card. The second parameter is the optional
     * messageId to land on inside the destination channel — used by the quote-tap
     * path so the new [ChannelScreen] highlights the replied-to message instead
     * of opening cold. Header / forward-source taps pass null.
     */
    onChannelOpen: (chatId: Long, scrollToMessageId: Long?) -> Unit = { _, _ -> },
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
     * Report callback forwarded into [PostInteractions.onReportClick]. Scaffold wires this
     * to open [dev.lyo.hortay.ui.report.ReportFlowSheet] (auth) or
     * [dev.lyo.hortay.ui.report.GuestReportDelegator] (guest). Default no-op keeps callers
     * that don't wire reporting from crashing.
     */
    onReportClick: (TimelinePost) -> Unit = {},
    /**
     * Predicate forwarded into [PostInteractions.canReport]. Auth mode returns
     * [TimelinePost.canReportChat]; guest mode returns true for all posts. Default false
     * hides the Report affordance in the sheet when the callback is not wired.
     */
    canReport: (TimelinePost) -> Boolean = { false },
    /**
     * Mode-agnostic read-state ack. Fires once per posts batch the viewport
     * dwell collector promotes from "in view" to "read". TDLib mode bridges to
     * [dev.lyo.hortay.data.posts.PostsRepository.viewMessages] (advances
     * `lastReadInboxMessageId` server-side and bumps view counters); guest
     * mode bridges to [dev.lyo.hortay.data.web.WebRepository.markChannelRead]
     * (advances the local `channel_read_cursor` row). Null disables the ack —
     * the auth screen and other surfaces that mount TimelineScreen without a
     * connected backend pass null so the dwell collector stays inert.
     */
    markAsRead: (suspend (List<TimelinePost>) -> Unit)? = null,
    /**
     * User-chosen feed ordering (defaults to
     * [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst] — the Telegram-channel
     * chat-app idiom: oldest read posts on top, unread queue below, newest at
     * the bottom). Scaffolds collect from
     * [dev.lyo.hortay.data.SettingsStore.feedOrder] and pass in; the sort runs in a
     * keyed `remember` over [visiblePosts] + [LocalReadCursors] so switching the
     * setting at runtime reshuffles the feed without any extra wiring. Per UX
     * guard: a runtime switch also auto-scrolls the LazyColumn back to the home
     * target so the new order has a clear starting point.
     */
    feedOrder: dev.lyo.hortay.data.FeedOrder = dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst,
    /**
     * Snap-fling toggle from [dev.lyo.hortay.data.SettingsStore.snapScroll].
     * When true, the LazyColumn fling settles on the nearest item boundary
     * — each post "clicks into place" at the viewport start. Drag scrolling
     * stays free; only the inertia animation snaps. Applies regardless of
     * [feedOrder] (presentation mode independent of ordering).
     */
    snapScroll: Boolean = false,
    /**
     * True while a channel/comments nav overlay covers this feed. The feed stays
     * mounted so Back is instant and scroll state is retained, but viewport-driven
     * background effects must pause: under an overlay the user is not actually
     * looking at these rows, so auto-accepting new posts or dwell-acking read
     * state would mutate the feed behind their back.
     */
    coveredByOverlay: Boolean = false,
    /**
     * Process-wide cold-start gate, TDLib mode only. While in
     * [StartupCoordinator.Phase.Booting] the comments-thread prefetch
     * collector silently skips its work to keep the TDLib RPC pipe clear for
     * TDLib's own initial sync. Guest mode passes null and the collector runs
     * unguarded — it doesn't make TDLib RPC anyway. See [StartupCoordinator] KDoc.
     */
    startupPhase: kotlinx.coroutines.flow.StateFlow<dev.lyo.hortay.data.StartupCoordinator.Phase>? = null,
    /**
     * Extra space to reserve below the floating "↓ N" unread chip so it stacks
     * above another bottom-anchored affordance that lives in the host Scaffold
     * (e.g. guest mode's "Add channel" FAB). Scaffold's [contentPadding] does
     * not include the FAB inset by design — content scrolls under it — so the
     * pill, anchored to the same BottomEnd corner, would otherwise sit right
     * under the FAB and become un-tappable. Default 0.dp matches TDLib mode
     * where no FAB is present; the guest-mode Feed tab passes a value sized to
     * the FAB height + a 16.dp gap.
     */
    unreadPillExtraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * Optional archive repository for showing [PostRevisionSheet] when the user
     * taps [dev.lyo.hortay.ui.archive.components.EditedChip] on an edited post.
     * Null in guest mode and any surface that doesn't wire archive support — the
     * chip is still rendered, but tapping it is a no-op.
     */
    archiveRepository: ArchiveRepository? = null,
    /**
     * Companion to [archiveRepository] for rendering archived media in the
     * revision sheet. When null, the sheet falls back to the minithumb-only
     * path; archived bytes are unreachable.
     */
    archiveMediaStore: dev.lyo.hortay.data.archive.ArchivedMediaStore? = null,
) {
    // Holders read by long-lived LaunchedEffects (home-tap, scope-switch) so
    // their captures stay live across recomposition without restarting the
    // effect bodies. `.intValue = …` / `.value = …` assignments happen inside
    // a single [LaunchedEffect] below the [latchedUiState] / [feedItems]
    // computation, NOT during composition — keeps the writes outside the
    // composition critical path. Default `0` / empty are the right
    // pre-landing fallbacks.
    val homeScrollIndexState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val feedItemsForEffectsState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<FeedItem>>(emptyList())
    }

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
    // Resolved per-folder rules (kept hot by ChatFoldersRepository). Used to hide tabs
    // that the user can't act on:
    //   • folders whose membership rule matches none of the subscribed channels — the
    //     tab would just open onto an empty feed;
    //   • folders whose rule is indistinguishable from the default "All" scope — the
    //     tab would duplicate the already-present "Усі" chip.
    // Filtering is gated on rules being resolved: while [fullFolders] is empty (cold
    // start, between UpdateChatFolders and the parallel GetChatFolder fan-out landing),
    // we render the raw list as before so the bar doesn't flicker / lose tabs.
    val fullFoldersMap: Map<Int, org.drinkless.tdlib.TdApi.ChatFolder> = folders?.fullFolders
        ?.collectAsStateWithLifecycle()?.value
        ?: emptyMap()
    // Per-folder resolved chat-id sets (TDLib applies the rule, we just consume the answer).
    // Used by the tab-visibility check below to hide folders that contain none of the
    // currently-subscribed channels — same UX as before, just sourced from TDLib instead of
    // an in-process rule reimplementation.
    val folderChatIdsMap: Map<Int, Set<Long>> = folders?.folderChatIds
        ?.collectAsStateWithLifecycle()?.value
        ?: emptyMap()
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
    // Revision sheet: set to the post whose EditedChip was tapped; null when closed.
    var revisionsForPost by remember { mutableStateOf<TimelinePost?>(null) }
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
    //
    // Cold-start positioning is owned by the `LazyListState(initialIndex, 0)`
    // constructor arg: the seed is derived SYNCHRONOUSLY from [latchedUiState]
    // at the [rememberSaveable] call site, so when the latcher flips to
    // [TimelineUiState.Ready(initialIndex = N)] the very next composition pass
    // re-keys the saver bundle and instantiates a fresh [LazyListState] at
    // index N. No `LaunchedEffect → MutableIntState → re-key` chain → no
    // one-frame paint at index 0 before the seed updates. Mirrors the pattern
    // [ChannelScreen] uses (see ChannelScreen.kt around line 294).
    //
    // [feedOrder] is part of [routeKey] so flipping Newest ↔ OldestUnreadFirst
    // invalidates the saver bundle directly: the latched [Ready.initialIndex]
    // would otherwise be preserved across the order flip (reducer's Ready→Ready
    // branch keeps the previous index), and a `LaunchedEffect(feedOrder)`
    // mutating a seed holder could no-op when old and new boundaries collide
    // (both `0`, for example), stranding the user on the old sort's anchor.
    //
    // [LazyListState.Saver] still persists `firstVisibleItemIndex` across tab
    // swaps within a process (the SaveableStateProvider in MainScaffold /
    // WebModeScaffold scopes saved state per route), so drilling into a
    // channel and popping back retains the user's scroll. Process death is
    // handled implicitly: the seed re-evaluates from 0 on restore, then jumps
    // to its real value on the next Ready transition, and the column mounts
    // pre-positioned. We don't try to bridge scroll across process death —
    // see the "Cold launch always lands on Home top-of-feed" guarantee.
    // ────────────────────────────────────────────────────────────────────────
    // Latcher chain — every input to [buildTimelineUiState] + [rememberLatchedTimelineUiState]
    // is computed here, BEFORE [listState], so the [readySeed] read into the
    // [rememberSaveable] key is the LATCHED Ready.initialIndex (not a stale 0 seeded
    // by a LaunchedEffect that runs after composition). ChannelScreen uses the same
    // shape — see ChannelScreen.kt:294.
    //
    // Order matters:
    //   scope_filter + folder vars → scopePredicate → filteredPosts → visiblePosts →
    //   readCursors → cursorsHaveLanded → boundaryCursors → feedItems → candidateUiState →
    //   latchedUiState → routeKey (includes feedOrder) → listState seeded from latched.
    // ────────────────────────────────────────────────────────────────────────

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
    // Stable string identity for the active scope. Used as part of [routeKey]
    // so every piece of scene-level state (latcher, pinnedScrollSeedKeys,
    // LazyListState.Saver bundle) re-keys on scope swap — that makes the
    // swap behave like a mini cold-start: composition produces a fresh
    // initialIndex for the new scope, LazyListState mounts at it on the
    // SAME frame, no LaunchedEffect-scrolled second-frame flash. We key
    // on the folder `id` only (not the title) so a TDLib `UpdateChatFolders`
    // emit with a translated name doesn't invalidate the scene by mistake.
    val scopeKey = remember(scope_filter) {
        when (val s = scope_filter) {
            FilterScope.All -> "all"
            FilterScope.Archive -> "archive"
            is FilterScope.Folder -> "folder:${s.id}"
        }
    }
    // Membership for the active folder, resolved by TDLib via GetChats(ChatListFolder).
    // We deliberately do NOT re-evaluate the include/exclude/excludeArchived/etc. rules in
    // process — TDLib already applies them and reimplementing them silently drifted any
    // time Telegram added a new exclude_* flag. `null` means the membership hasn't landed
    // yet (cold start, or non-Folder scope); the scope predicate treats that as "show
    // nothing folder-scoped" so the user doesn't see a flash of unfiltered posts under a
    // folder tab.
    val folderChatIdsForScope: Set<Long>? = (scope_filter as? FilterScope.Folder)
        ?.let { folderChatIdsMap[it.id] }
    LaunchedEffect(scope_filter) {
        val folderScope = scope_filter as? FilterScope.Folder ?: return@LaunchedEffect
        // Suspend-prime the cache so a freshly-selected folder tab gets its membership
        // populated even if the eager warm-up in ChatFoldersRepository hasn't completed.
        runCatching { folders?.folderChatIds(folderScope.id) }
    }

    // Scope predicate shared by the visible feed and the "X нових постів" pill — so the
    // pill can't surface pending posts the user can't actually see (e.g. archived chats
    // while the user is in "Усі", or out-of-folder channels while a folder is active).
    val scopePredicate = remember(scope_filter, archivedChatIds, folderChatIdsForScope) {
        { p: TimelinePost ->
            val isArchived = p.chatId in archivedChatIds
            when (scope_filter) {
                FilterScope.All -> !isArchived
                FilterScope.Archive -> isArchived
                is FilterScope.Folder -> folderChatIdsForScope?.contains(p.chatId) == true
            }
        }
    }

    val filteredPosts = remember(posts, scopePredicate, bookmarkedKeys, showOnlyBookmarked) {
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

    // Live cursor holder from LocalReadCursors — drives the UnreadStrip fade
    // per visible card (PostCard reads `holder[post.chatId]`), the
    // [UnreadCounterPill] counter, and the cold-start scroll target. The
    // sort itself is independent of cursors (strict ascending by date for
    // OldestUnreadFirst), so cursor-arrival never re-orders the list.
    //
    // The [dev.lyo.hortay.ui.timeline.UnreadBoundaryRow] divider does NOT
    // read this live holder — see [boundaryCursorsState] below for why.
    val cursorHolder = LocalReadCursors.current

    // Recency floor for OldestUnreadFirst landing — see
    // [BOUNDARY_RECENCY_WINDOW_MS]. Captured once per mount so the cutoff is
    // stable across recompositions; on re-entry (tab swap / cold start) it
    // re-samples, which is correct because the floor IS "recent relative to
    // when the user opened the feed". One source of truth: every boundary
    // pick — cold-start latcher, home-tap, scope switch, pill fallback — goes
    // through the same value via [homeScrollIndex] / [buildTimelineUiState].
    val recencyCutoffMs = remember { System.currentTimeMillis() - BOUNDARY_RECENCY_WINDOW_MS }

    val visiblePosts = remember(filteredPosts, feedOrder) {
        filteredPosts.orderedFor(feedOrder)
    }

    // Cold-start "cursors usable for the boundary picker" signal. Source of
    // truth is [TimelineViewModel.coldStartCursorsSettled] — an Activity-scoped
    // latched flag that flips `false → true` exactly once per session and stays
    // true across [dev.lyo.hortay.ui.main.TabContentSwitcher] AnimatedContent
    // unmount / remount. Holding the flag in the VM avoids the previous
    // screen-local `remember(feed, feedOrder) { mutableStateOf(false) }` that
    // reset on every Feed-tab remount and re-armed the grace timer, painting a
    // `SkeletonFeed` for ~800 ms on every Feed → Channels → Feed swap.
    //
    // Gated on `refresh idle + COLD_START_CURSOR_GRACE_MS` only — NOT on
    // "first cursor put landed", which was a race: the flag would flip true
    // the moment ANY chat's `UpdateChatReadInbox` arrived, but the boundary
    // picker then ran against a partially-seeded cursor map. Chats whose
    // `UpdateNewChat` hadn't been processed yet had `cursor == null` and
    // looked "read" per [isUnreadIn] — so an early-flipping cursor could pin
    // the LazyColumn at the wrong [items] row, and the latcher would freeze
    // that wrong landing for the rest of the session.
    //
    // `refreshLocked` drains [TdApi.LoadChats] for `ChatListMain` before it
    // harvests `Chat.lastMessage`, and `UpdateNewChat` (which seeds the
    // cursor for every chat with `lastReadInboxMessageId > 0`) flushes before
    // the harvest's `awaitAll`. So by the time `refreshing` falls true →
    // false, the cursor map is as complete as TDLib will get it for this
    // cold-start window. The grace delay covers in-flight
    // `UpdateChatReadInbox` deltas the daemon sends just after `LoadChats`
    // resolves. Refs: tdlib/td#1369 (state via updates, not getChats
    // response), #1034 (`unreadCount` ↔ `lastReadInboxMessageId` invariant).
    val cursorsHaveLanded by vm.coldStartCursorsSettled.collectAsStateWithLifecycle()
    LaunchedEffect(feed, feedOrder, cursorsHaveLanded) {
        if (cursorsHaveLanded) return@LaunchedEffect
        if (feedOrder != dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst) {
            vm.markColdStartCursorsSettled()
            return@LaunchedEffect
        }
        androidx.compose.runtime.snapshotFlow { refreshing }.first { !it }
        kotlinx.coroutines.delay(COLD_START_CURSOR_GRACE_MS)
        vm.markColdStartCursorsSettled()
    }

    // Frozen cursor snapshot for the [UnreadBoundaryRow] divider. Latches on
    // discrete boundary events — never on the per-card dwell-acks that flow
    // through [readCursors] continuously. Triggers:
    //   1. Cold-start landing: snapshot the moment `cursorsHaveLanded`
    //      first flips true (= TDLib's UpdateChatReadInbox burst arrived,
    //      or the grace timeout fired).
    //   2. Pull-to-refresh completion: `refreshing` falls true → false. The
    //      user explicitly asked for a fresh view of the world, so it's
    //      legitimate to move the boundary.
    //   3. Feed identity change (logout / mode switch / scope-level VM
    //      rebuild): `remember(feed, feedOrder)` resets the snapshot.
    //
    // Why not the live map: the boundary divider is a ~28dp LazyColumn item
    // keyed by FeedItem. When it migrates from above item A to above
    // item B (because A just got dwell-acked), every item below A jumps up
    // by the divider's height and every item above shifts the other way —
    // a visible "skip" under the user's scroll. Telegram-Android, Slack,
    // and Discord all latch the New-messages rule on chat-open and refuse
    // to move it mid-session for the same reason: the divider is meant to
    // be an ANCHOR ("here is where you came in"), not a live read-edge
    // ("here is where you are now"). The per-card unread strip in
    // [PostCard.kt] keeps the live signal — and it can afford to, because
    // `Modifier.drawBehind` shifts zero pixels of layout.
    //
    // The per-card live strip + frozen divider + live `↓ N` pill answer
    // three different questions ("is this read?", "where did I come in?",
    // "how many left?") with three different cadences. That separation is
    // load-bearing — collapsing them costs either clarity (drop the
    // divider) or stability (let it migrate).
    // Seeded with the live snapshot when cursors are already settled (= the
    // VM-resident cold-start flag is true), so a tab-swap remount lands the
    // divider in the right place on the first frame instead of flashing the
    // empty-cursor fallback until the [LaunchedEffect] below catches up.
    val boundaryCursorsState = remember(feed, feedOrder) {
        androidx.compose.runtime.mutableStateOf(
            if (cursorsHaveLanded) cursorHolder.snapshot()
            else dev.lyo.hortay.data.EmptyReadCursors,
        )
    }
    // Epoch counter that rotates in lock-step with [boundaryCursorsState]. The
    // [FeedItem.Boundary] key embeds this epoch (`boundary_$epoch`) so the divider
    // row keeps a stable LazyColumn identity across per-card dwell-acks (which
    // don't rotate the snapshot and don't advance the epoch) but rotates on the
    // discrete session-anchor events that DO re-latch the snapshot — cold-start
    // landing, PTR completion, and feed/order identity change (the `remember`
    // key list below).
    val cursorEpochState = remember(feed, feedOrder) {
        androidx.compose.runtime.mutableLongStateOf(0L)
    }
    LaunchedEffect(feed, feedOrder, cursorsHaveLanded, refreshing) {
        if (feedOrder != dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst) return@LaunchedEffect
        if (!cursorsHaveLanded) return@LaunchedEffect
        // Re-latch on (a) the first cold-start landing — `cursorsHaveLanded`
        // just flipped, capture the current map — and (b) refresh idle
        // transitions. The `!refreshing` gate covers both: it's true on
        // the cold-start landing turn (refresh has already completed by
        // then in nearly every path) AND it's true the instant a
        // pull-to-refresh round-trip ends.
        if (!refreshing) {
            // .snapshot() reads under Snapshot.withoutReadObservation, so
            // this LaunchedEffect does NOT subscribe to subsequent cursor
            // advances — the frozen reference only refreshes when the
            // effect re-keys (cold-start landing, refresh idle, feed swap).
            boundaryCursorsState.value = cursorHolder.snapshot()
            cursorEpochState.longValue = cursorEpochState.longValue + 1L
        }
    }
    val boundaryCursors = boundaryCursorsState.value
    val cursorEpoch = cursorEpochState.longValue

    // One TimelinePost → one [FeedItem] row. Single source of row identity through
    // [FeedItem.key], stable across every ingest path (`loadChannelHistory`,
    // `UpdateNewMessage`, `loadOlder`, album-coalesce). LazyColumn's keyed-scroll
    // preservation carries the user's anchor through any reorder. The previous
    // `groupReplies` reshape that promoted Single → Thread on the fly was deleted —
    // see [FeedItem] for the full rationale.
    val feedItems: List<FeedItem> = remember(
        visiblePosts, boundaryCursors, feedOrder, cursorEpoch, recencyCutoffMs,
    ) {
        val postItems = visiblePosts.map(FeedItem::Post)
        if (feedOrder == dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst) {
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
    }
    // Keep the forward-declared holder current so the home-tap LaunchedEffect
    // can read the latest list without capturing a stale reference. Wrapped
    // in [SideEffect] so the snapshot mutation happens after composition
    // completes — writing State during composition is an antipattern that
    // can confuse the snapshot system and trip "State was modified during
    // composition" diagnostics under aggressive recomposition timing.
    androidx.compose.runtime.SideEffect {
        feedItemsForEffectsState.value = feedItems
    }

    // Row-space scroll target for user-initiated jumps (home tap, scope switch,
    // ↑ N / ↓ N pills). MUST be computed against [feedItems] — the LazyColumn
    // renders FeedItem rows, and although the merged feed maps 1:1 to posts
    // today, keeping the computation row-space makes the call sites resilient
    // to future grouping (e.g. day-separators) and matches the latcher's own
    // row-space [Ready.initialIndex] (see [buildTimelineUiState] using
    // items.map { it.posts().first() }).
    // Per-key snapshot reads inside the derivedStateOf body: the closure
    // touches `cursorHolder[anchor.chatId]` for each [FeedItem] (this is
    // home-tap scroll target, computed against the whole feed — NOT just
    // visible rows — because "home" can jump from any scroll position to
    // the read→unread boundary). Compose subscribes to exactly the
    // per-chatId keys the closure reads (which is the union of chat ids
    // across the feed), and `derivedStateOf` dedupes its output —
    // downstream (homeScrollIndexState mirror) flips only when the
    // boundary index actually moves. So an `UpdateChatReadInbox` for a
    // chat not in the feed is a free no-op; for an in-feed chat the
    // scan re-runs but most cursor advances don't move the integer
    // boundary, so the mirror does not re-emit.
    // Single source of truth for "where should I scroll now" boundary picks:
    // cold-start latcher, NavBar home re-tap, scope/folder switch, and the
    // UnreadCounterPill fallback all read this value. Goes through
    // [continueReadingIndex] with the same [recencyCutoffMs] the latcher uses
    // so a folder switch can't auto-scroll the user onto a weeks-old dormant
    // unread that the cold-start picker would have rejected.
    val homeScrollIndex by remember(feedItems, feedOrder, cursorsHaveLanded, recencyCutoffMs) {
        derivedStateOf {
            when (feedOrder) {
                dev.lyo.hortay.data.FeedOrder.Newest -> 0
                dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst -> {
                    if (feedItems.isEmpty()) 0
                    else if (!cursorsHaveLanded) 0
                    else {
                        // Row-space scan that skips [FeedItem.Boundary] divider rows
                        // (the divider never qualifies as a landing target). Mirrors
                        // the descending-order semantics of [continueReadingIndex] —
                        // unread block on the low indices, read block on the high
                        // indices, boundary = oldest unread = last unread row that
                        // also satisfies the recency floor.
                        val live = cursorHolder.snapshot()
                        val boundary = feedItems.indexOfLast { row ->
                            val post = (row as? FeedItem.Post)?.post ?: return@indexOfLast false
                            post.isUnreadIn(live) &&
                                (recencyCutoffMs <= 0L || post.date >= recencyCutoffMs)
                        }
                        // Fall back to newest (index 0) when no unread boundary
                        // qualifies — same fallback main's continueReadingIndex path used.
                        if (boundary >= 0) boundary else 0
                    }
                }
            }
        }
    }
    // Mirror into the forward-declared holder so the cold-start pin /
    // home-tap / scope-switch effects further up the composable see the
    // current target without recomposing on every cursor advance.
    LaunchedEffect(homeScrollIndex) {
        homeScrollIndexState.intValue = homeScrollIndex
    }

    // Single source of truth for what TimelineScreen renders — derived from the
    // already-filtered, already-ordered, already-grouped [feedItems], the live
    // [ReadCursors] map, and the [feedOrder] / [refreshing] flags. The latching
    // wrapper enforces the one-shot contract on [TimelineUiState.Ready.initialIndex]:
    // captured on the first Ready transition and held stable across recompositions
    // — live cursor advances and post arrivals can't yank the user's scroll. PTR
    // completion only re-latches [frozenCursors] (not [initialIndex]) so the unread
    // boundary divider can update while the user keeps their scroll. [routeKey]
    // includes [feedOrder] so an order flip invalidates the saver bundle directly
    // (the latched initialIndex would otherwise be preserved across the flip and a
    // seed-bump LaunchedEffect could no-op when old/new boundaries collide).
    val persistentFeedItems = remember(feedItems) { feedItems.toPersistentList() }
    // Scene-level identity. Includes [scopeKey] so a scope swap (Archive ↔
    // All ↔ Folder) re-keys the latcher, the VM pinned-anchor map, AND the
    // LazyListState saver bundle in one shot. The LazyColumn then mounts
    // at the new scope's `Ready.initialIndex` on the SAME frame the
    // composition produced it — no LaunchedEffect-scrolled second-frame
    // flash, no `layoutInfo`-vs-`feedItems` race. Scroll within a scope is
    // still preserved (saver bundle keyed on the same triple stays
    // intact), and switching back to a previously-visited scope restores
    // the captured anchor via [TimelineViewModel.pinnedScrollSeedKey].
    val routeKey = Triple(if (showOnlyBookmarked) "saved" else "home", feedOrder, scopeKey)
    // Reuses [boundaryCursorsState] as the frozenCursors source: both
    // need a snapshot of the cursor map captured at first cold-start
    // landing + PTR completion, which is exactly what
    // [boundaryCursorsState]'s LaunchedEffect already latches. Avoids
    // allocating a fresh PersistentMap on every TimelineScreen
    // recomposition via [cursorHolder.snapshot()] — for OldestUnreadFirst
    // the latched value is what the latcher would have captured anyway;
    // for Newest mode the state stays at [EmptyReadCursors] which the
    // builder maps to initialIndex=0 (the correct Newest landing).
    val candidateUiState: TimelineUiState = buildTimelineUiState(
        items = persistentFeedItems,
        cursorsLanded = cursorsHaveLanded,
        frozenCursors = boundaryCursors,
        feedOrder = feedOrder,
        refreshing = refreshing,
        minUnreadDate = recencyCutoffMs,
    )
    val latchedUiState = rememberLatchedTimelineUiState(
        candidate = candidateUiState,
        refreshing = refreshing,
        routeKey = routeKey,
    )

    // [rememberSaveable] key for the feed's [LazyListState] is keyed on the
    // BOOLEAN "latched candidate has produced an initial index" — NOT on the
    // index value itself.
    //
    // Why the boolean:
    //   • Cold start: it flips `false→true` in lockstep with [latchedUiState]
    //     reaching its first [TimelineUiState.Ready] (because
    //     [candidateInitialIndex] is derived from latched in the same
    //     composition). The init lambda runs synchronously on that re-key
    //     and captures the current frame's [readySeed], so the LazyColumn
    //     mounts at the unread boundary on the very same frame it first
    //     becomes visible.
    //   • Steady state, ingest, drill / drill-back, PTR, dwell-acks:
    //     [reduceTimelineUiState] preserves `initialIndex` across every
    //     Ready→Ready transition (one-shot latcher contract — see its
    //     KDoc), so the boolean stays `true` and the key stays stable. The
    //     saver bundle is owned by the user's scroll; LazyColumn carries
    //     the visual anchor through any feedItems mutation via
    //     keyed-scroll preservation on [FeedItem.key].
    //   • Tab swap (TabContentSwitcher unmounts the inactive tab and the
    //     parent SaveableStateProvider persists bundles per `tab.name`):
    //     on remount the latcher cold-starts again (Loading → Ready), the
    //     boolean transitions `false → true` once more, and on the `true`
    //     frame the bundle saved before unmount is restored under the
    //     same key — user's pre-unmount scroll position lands back where
    //     it was.
    //
    // Why NOT key on the index value (the original design): every time the
    // resolved row of the pinned anchor drifts (e.g. while the user is
    // parked under a channel drill, [PostsRepository.loadChannelHistory]
    // backfills ~80 older posts of an open subscribed channel into `_posts`,
    // shifting the anchor from row ~5 to ~85 in OldestUnreadFirst's
    // asc-by-date sort), the key changes, the saver bundle is invalidated,
    // and the listState reinitialises at the new anchor row. On overlay pop
    // the user lands at the unread boundary instead of where they were
    // reading.
    //
    // Why NOT key on `cursorsHaveLanded`: that flag flips `false→true` one
    // composition BEFORE [latchedUiState] becomes Ready (the latcher
    // updates `effective.value` through a [LaunchedEffect] that commits
    // after composition). On the flip frame [candidateInitialIndex] is
    // still null, so re-init would seed the listState at row 0 — the
    // oldest post in OldestUnreadFirst's asc-by-date sort, which surfaces
    // as "cold start lands on 2017 posts".
    //
    // The init lambda captures [readySeed] DIRECTLY (no
    // [rememberUpdatedState] hop) because rememberSaveable invokes init
    // synchronously inside the same composition that recomputed
    // [readySeed]. rememberUpdatedState would defer the update to a
    // SideEffect that runs AFTER composition — reading its `.value` from
    // an init lambda invoked during composition returns the previous
    // frame's value.
    val candidateInitialIndex = (latchedUiState as? TimelineUiState.Ready)?.initialIndex
    val pinnedKey: String? = vm.pinnedScrollSeedKey(routeKey)
    LaunchedEffect(routeKey, candidateInitialIndex, feedItems) {
        if (pinnedKey != null) return@LaunchedEffect
        val anchorKey = candidateInitialIndex?.let { feedItems.getOrNull(it)?.key }
            ?: return@LaunchedEffect
        vm.capturePinnedScrollSeedKey(routeKey, anchorKey)
    }
    // Resolve the pinned key back to the current row index. If the post
    // was deleted / filtered out between sessions of this composable, fall
    // back to the freshest candidate.
    val pinnedIndex: Int? = remember(pinnedKey, feedItems) {
        if (pinnedKey == null) null
        else feedItems.indexOfFirst { it.key == pinnedKey }.takeIf { it >= 0 }
    }
    val readySeed = pinnedIndex ?: candidateInitialIndex ?: 0
    val listState = rememberSaveable(
        routeKey, candidateInitialIndex != null,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver,
    ) {
        androidx.compose.foundation.lazy.LazyListState(readySeed, 0)
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
    // The feed bar hide-on-scrolls in BOTH orders via pure gesture deltas — the
    // hide/show direction is gesture-based (finger-up = reading = hide), which is
    // the same in Newest and OldestUnreadFirst, so the behavior needs no
    // layout-direction awareness.
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
    // feedItems and clears the request on success. Cleared too on filter dismissal
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
    val viewer = LocalMediaViewer.current

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
        // Mode-aware "Home" target via [homeScrollIndexState]:
        //   - Newest: index 0 (newest content — "show me what's fresh").
        //   - OldestUnreadFirst: first-unread boundary — "take me to where I
        //     should be reading". Top of the list is the oldest read post,
        //     not what the user expects from a Home tap.
        // Re-tap when already at the target = refresh (Twitter / TG idiom).
        val target = homeScrollIndexState.intValue
        val atTarget = listState.firstVisibleItemIndex == target &&
            listState.firstVisibleItemScrollOffset == 0
        if (atTarget) {
            vm.refresh()
        } else {
            // Brief surface-tint pulse on the destination card — canonical
            // chat-UI pattern (Telegram/Slack/Discord: "you just landed here").
            // Reuses the same [highlightedPostKey] pipeline that deep-link and
            // quote-tap landings drive, so there is a single rendering path for
            // the highlight. The set happens after the scroll call so the card
            // is already in the visible viewport by the time Compose reads the key.
            // Land on the LIVE "continue reading" target ([homeScrollIndexState],
            // recomputed over the live cursor map), top-aligned in OldestUnreadFirst
            // so reading starts at the oldest-unread post's top. NOT the frozen
            // [FeedItem.Boundary] divider anchor, which doesn't advance as you read and
            // would land you back on an already-read post (same bug as the unread pill).
            val items = feedItemsForEffectsState.value
            if (target > 0) {
                listState.scrollToBoundary(rowIndex = target, animated = true)
            } else {
                // Newest mode / caught-up (target == 0): natural landing on newest.
                listState.smartScrollTo(target)
            }
            items.getOrNull(target)?.posts()?.firstOrNull()?.let { post ->
                highlightedPostKey = post.chatId to post.id
            }
        }
    }

    // Scope-swap landing is handled by composition, not by a LaunchedEffect:
    // [scopeKey] is part of [routeKey], so a swap re-keys the latcher AND the
    // LazyListState saver bundle in one shot. `Ready.initialIndex` for the
    // new scope is computed during composition (via [buildTimelineUiState]
    // with the same recency floor the cold-start latcher uses), and
    // LazyColumn mounts at that index on the very first paint — no
    // intermediate frame at the previous scope's `firstVisibleItemIndex`.
    //
    // The earlier LaunchedEffect-driven `smartScrollTo` flashed visibly on
    // Archive → All because Compose dispatches LaunchedEffect bodies on the
    // frame AFTER the one that drew the new collection with the stale
    // listState index. Re-keying lifts the landing into the same frame as
    // the collection change, where it belongs.


    // LaunchedEffect's block freezes its captures on the keys-last-changed composition
    // (Compose's [remember] under the hood holds the original lambda), so subsequent
    // changes to [feedItems] aren't seen by the long-running effect bodies below.
    // Routing the read through [rememberUpdatedState] gives us a [State] handle whose
    // .value is refreshed on every recomposition without restarting any effect — the
    // same pattern this composable already uses for [postsState], [translationsState],
    // [bookmarkedState] further down. Critical for the read-mark / focus-chat effects
    // because the feed list ref churns with every UpdateMessageInteractionInfo (~30 Hz
    // on a busy news day): a stale capture would have us ack the wrong posts as
    // forceRead=true and (worse) OpenChat the wrong chat.
    val feedItemsState = rememberUpdatedState(feedItems)

    // Resolve the queued "scroll to messageId" once the target row appears.
    // Extracted to [rememberPendingScrollToMessage] — shared with [ChannelScreen].
    // Highlight is set BEFORE the jump so it's composed by the time the new viewport
    // paints; [scrollToItem] is an instant jump (Telegram-Android also hard-jumps on a
    // link click — animating through 200+ intermediate rows for a deep-linked old post
    // made the app feel like it was searching for the post). On miss the helper invokes
    // [onScrollMissed] via the [onMissed] callback so the scaffold surfaces "Посилання
    // не знайдено" instead of leaving the user staring at a frozen skeleton.
    // Gate `pendingTarget` (NOT displayedItems) on Ready. The resolver's
    // LaunchedEffect keys on pendingTarget — a non-null target fires it
    // immediately, even with an empty items list, which makes loadHistoryAround
    // run and (on its `false` return, or after the 1500ms grace) trigger
    // onMissed before the column was ever mounted. Clearing the pending target
    // until Ready keeps the effect dormant: it re-fires only when both (a) the
    // user's deep-link is still pending AND (b) the column is actually mounted,
    // so the resolved index lands on a real row.
    val readyPendingTarget = pendingScrollToMessage
        ?.takeIf { latchedUiState is TimelineUiState.Ready && !coveredByOverlay }
    rememberPendingScrollToMessage(
        displayedItems = feedItems,
        pendingTarget = readyPendingTarget,
        loadHistoryAround = { cid, mid -> tdlibRepo?.loadHistoryAround(cid, mid) ?: false },
        onLanded = { cid, mid, idx ->
            highlightedPostKey = cid to mid
            // scrollToTopAligned, not scrollToItem: in reverseLayout (OldestUnreadFirst
            // mode), scrollToItem(idx, 0) glues the target's BOTTOM to the viewport
            // bottom — for a tall post the header (top) ends up clipped above the
            // viewport. scrollToTopAligned reads the row's measured size and uses the
            // matching offset so the header is always visible.
            listState.scrollToTopAligned(idx)
            pendingScrollToMessage = null
        },
        onMissed = {
            pendingScrollToMessage = null
            onScrollMissed()
        },
    )

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

    // Warm the discussion-thread cache for posts that linger in the viewport.
    // Extracted to [rememberCommentsPrefetch] — shared with [ChannelScreen]. Cold
    // GetMessageThread is ~1.5–2 s per channel; after a single fetch TDLib answers
    // from local cache (~200 ms). Cap of [COMMENTS_PREFETCH_LIMIT] keeps the TDLib
    // RPC pipe clear (per Levin tdlib/td#3019 the pipe is a single per-client
    // queue), and the [StartupCoordinator] gate keeps us silent during the post-auth
    // storm. commentCount > 0 (not just != null) skips the typical "0 replies
    // forever" case that would otherwise burn 2 wasted RPCs per post on the
    // dominant share of first-launch volume.
    if (commentsRepo != null && !coveredByOverlay) {
        rememberCommentsPrefetch(
            listState = listState,
            displayedItems = feedItems,
            startupPhase = startupPhase,
            prefetchThread = commentsRepo::prefetchThread,
            maxConcurrent = COMMENTS_PREFETCH_LIMIT,
        )
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
    // but skipping the round-trip altogether is cheaper. Set size scales with what the
    // user actually reads in one session — bounded by TDLib's own history retention,
    // not by an in-process cap.
    // Read-ack dwell extracted to [rememberReadAckDwell] — shared with
    // [ChannelScreen]. Populates ackedRead BEFORE dispatching the suspending ack so
    // a mid-batch cancellation can't leave half the chats acked locally; the
    // mode-specific call is detached into [scope] so a viewport change a frame later
    // doesn't kill the in-flight ack. The returned set is the same one
    // [markPostReadState] below extends with explicit-tap acks.
    val ackedRead = rememberReadAckDwell(
        listState = listState,
        displayedItems = feedItems,
        ackKey = markAsRead,
        markAsRead = if (coveredByOverlay) null else markAsRead,
        scope = scope,
        dwellMs = READ_DWELL_MS,
    )

    // Focus-chat tracking: keep the chat of the post the user is actually LOOKING AT
    // OpenChat'd, and transition cleanly to the next chat as they scroll. This is what
    // makes reactions / views / comment-counts stream live for the post under their
    // gaze — per tdlib/td#2312, those updates arrive only for chats currently opened
    // in TDLib. The maintainer's canonical pattern is "usually one chat opened"
    // (tdlib/td#2695), so we honour that strictly: at most ONE merged-feed chat is
    // open at any time.
    //
    // Picking "the post under the gaze" — we use the item whose VISIBLE area in the
    // viewport is greatest, not `firstVisibleItem`. In a feed of full-width tall cards
    // the topmost item is usually partially scrolled out while the user reads the next
    // card centred on screen; `firstVisibleItem` would OpenChat the wrong chat (one
    // whose post is barely visible), leaving the actually-read chat closed and its
    // interaction stream silent. Max-visible-area survives albums spanning more height
    // than one card, near-equal splits at scroll boundaries (tie-break by index is
    // stable, so we don't oscillate), and the cold-start "no items yet" state (null,
    // skip). The previous KDoc claimed "topmost" was correct; observed in practice it
    // wasn't.
    //
    // Hysteresis: FOCUS_DWELL_MS keeps us from rapidly cycling open/close on a quick
    // scroll. collectLatest cancels the delay on every focus change — only a chat
    // that holds the dominant-visible slot for FOCUS_DWELL_MS straight gets opened.
    //
    // Cleanup: try/finally with NonCancellable on the close so a fast screen exit
    // still flushes CloseChat.
    if (tdlibRepo != null && !coveredByOverlay) {
        LaunchedEffect(listState, tdlibRepo) {
            var opened: Long? = null
            try {
                androidx.compose.runtime.snapshotFlow {
                    val layout = listState.layoutInfo
                    val viewportStart = layout.viewportStartOffset
                    val viewportEnd = layout.viewportEndOffset
                    layout.visibleItemsInfo
                        .maxByOrNull { item ->
                            val itemEnd = item.offset + item.size
                            val clippedStart = maxOf(item.offset, viewportStart)
                            val clippedEnd = minOf(itemEnd, viewportEnd)
                            (clippedEnd - clippedStart).coerceAtLeast(0)
                        }
                        ?.index
                }
                    .distinctUntilChanged()
                    .collectLatest { focusIdx ->
                        if (focusIdx == null) return@collectLatest
                        kotlinx.coroutines.delay(FOCUS_DWELL_MS)
                        // Latest displayed list — same staleness reason as the read-ack effect.
                        val items = feedItemsState.value
                        val focusedPost = items.getOrNull(focusIdx)?.posts()?.firstOrNull()
                            ?: return@collectLatest
                        val focusChat = focusedPost.chatId
                        if (focusChat == opened) return@collectLatest
                        // Atomic close+open+re-ack via NonCancellable. Three reasons it
                        // has to be a single non-cancellable block:
                        //
                        // (1) ChatPresence decrements the local refcount BEFORE issuing
                        //     the network CloseChat, so a mid-flight cancellation here
                        //     would otherwise leak an opened chat in TDLib (refcount 0
                        //     locally, but TDLib never received CloseChat). Pinning the
                        //     swap means a subsequent collectLatest cancel waits for
                        //     both calls to land before letting the next emission start
                        //     its own swap.
                        // (2) viewMessages MUST run AFTER openChat so
                        //     [ChatPresence.isOpen] reports true and
                        //     [PostsRepository.viewMessages] picks force_read=false
                        //     (canonical "actively reading", which gates the
                        //     interaction-info stream). Without this explicit re-ack
                        //     the merged-feed focused post gets its only viewMessages
                        //     from the read-ack dwell at READ_DWELL_MS (500 ms) —
                        //     BEFORE this effect's FOCUS_DWELL_MS (600 ms) OpenChat —
                        //     with force_read=true, which deprioritises the stream we
                        //     want. ChannelScreen doesn't have this gap because OpenChat
                        //     fires from ChannelViewModel.init before any viewMessages.
                        // (3) NonCancellable doesn't block forever — viewMessages and
                        //     OpenChat/CloseChat are bounded RPCs and ChatPresence
                        //     wraps the send in runCatching anyway.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            opened?.let { prev -> tdlibRepo.closeChat(prev) }
                            tdlibRepo.openChat(focusChat)
                            opened = focusChat
                            val ids = focusedPost.albumMessageIds
                                .ifEmpty { listOf(focusedPost.id) }
                            tdlibRepo.viewMessages(focusChat, ids)
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
    val onChannelOpenState = rememberUpdatedState { chatId: Long, scrollTo: Long? ->
        onChannelOpen(chatId, scrollTo)
    }
    val onOpenCommentsState = rememberUpdatedState { post: TimelinePost ->
        onOpenComments(post)
    }

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
        val mark = markAsRead
        if (mark != null) {
            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
            val unacked = ids.filter { (post.chatId to it) !in ackedRead }
            if (unacked.isNotEmpty()) {
                unacked.forEach { ackedRead.add(post.chatId to it) }
                // Synthesise a one-element batch carrying the resolved album ids
                // so the mode-specific wrapper sees the same shape it gets from
                // the dwell collector. TDLib mode flattens to viewMessages(chatId,
                // [albumIds]); guest mode picks the max seq and advances the
                // channel cursor. Either way, an explicit tap clears the unread
                // strip on every member of the album, not just the anchor.
                val ackPosts = unacked.map { id ->
                    if (id == post.id) post else post.copy(id = id, albumMessageIds = emptyList())
                }
                scope.launch { mark(ackPosts) }
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
        onReportClick,
        canReport,
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
            // Tapping a feed post's channel name/avatar drills into that channel
            // landed ON the tapped post — anchored with a highlight pulse — instead
            // of at the read/unread boundary. Reuses the same loadHistoryAround +
            // highlight contract as the forward-chip and reply-quote taps; `post.id`
            // is the TDLib message id ChannelViewModel matches against scrollToMessageId.
            onChannelClick = { post -> onChannelOpenState.value(post.chatId, post.id) },
            onAuthorChatClick = { id -> onChannelOpenState.value(id, null) },
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
                // Only Channel origins carry a permalink message id — group/chat
                // forwards have no per-message anchor on the TDLib side.
                val sourceMessageId =
                    (origin as? dev.lyo.hortay.data.ForwardOrigin.Channel)?.sourceMessageId
                // Always open the channel when we know the chatId — ChannelScreen owns
                // the preview/skeleton while TDLib loads the history for non-subscribed
                // channels. With sourceMessageId set, the destination lands at the
                // original post instead of the channel's newest entry.
                when {
                    sourceId != null -> onChannelOpenState.value(sourceId, sourceMessageId)
                    !sourceHandle.isNullOrBlank() -> {
                        // Username-only origins (TDLib didn't include the resolved id) —
                        // route through LocalUriHandler so HortayUriHandler resolves
                        // the handle via SearchPublicChat and lands the ChannelScreen path.
                        // Append the message id when available so the resolver can drill
                        // straight to the original post.
                        val handle = sourceHandle.removePrefix("@")
                        val url = if (sourceMessageId != null) "https://t.me/$handle/$sourceMessageId"
                            else "https://t.me/$handle"
                        uriHandler.openUri(url)
                    }
                }
            },
            onQuotedSourceClick = { post ->
                // Open the original in a ChannelScreen, with the replied-to messageId
                // baked into the nav-entry so the channel lands at that message and
                // pulses the highlight inside the new overlay. Critically, the feed's
                // own [pendingScrollToMessage] is NOT touched here — otherwise the
                // resolver would also scroll the feed underneath the overlay and flash
                // a highlight there, which the user reads as "the feed jumped under me"
                // (see bug: tap reply → channel opens AND feed highlights the post).
                // `replyToChatId` is already normalised at the mapping boundary —
                // see [MessageMapper.mapReply] for the rationale.
                post.reply?.let { r ->
                    onChannelOpenState.value(r.replyToChatId, r.replyToMessageId)
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
                val repo = tdlibRepo ?: return@PostInteractions
                // Optimistic UI: flip the chip and adjust the count BEFORE the RPC so
                // the tap feels instant — the canonical pattern (Telegram-Android,
                // Twitter, Reddit, Slack). The eventual `UpdateMessageInteractionInfo`
                // overwrites the local guess with server truth via
                // [PostsRepository.flushPendingInteractionInfo]; on RPC failure we
                // revert by applying the inverse toggle.
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                val nowChosen = !item.isChosen
                repo.applyOptimisticReaction(post.chatId, target, item.kind, nowChosen)
                scope.launch {
                    val ok = ca.toggleReaction(
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
                channelActions?.availableReactions(post.chatId, target) ?: emptyList()
            },
            onPostClick = { post ->
                markPostReadState.value(post)
                onOpenCommentsState.value(post)
            },
            // Optimistic poll vote: flip the local poll state (chosen rows light up, shimmer
            // ride the result bars) BEFORE the RPC, then dispatch SetPollAnswer. The eventual
            // `UpdateMessageContent` from TDLib carries the authoritative percentages and
            // overwrites our guess via [PostsRepository.handleContentChanged]; on RPC failure
            // [PostsRepository.clearPollPending] with revert=true undoes the local flip.
            onPollVote = { post, indices ->
                val ca = channelActions ?: return@PostInteractions
                val repo = tdlibRepo ?: return@PostInteractions
                val target = post.albumMessageIds.ifEmpty { listOf(post.id) }.first()
                repo.applyOptimisticPollAnswer(post.chatId, target, indices)
                scope.launch {
                    val ok = ca.setPollAnswer(post.chatId, target, indices)
                    repo.clearPollPending(post.chatId, target, revert = !ok)
                }
            },
            pollVotingEnabled = channelActions != null && tdlibRepo != null,
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedState.value },
            onReportClick = onReportClick,
            canReport = canReport,
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
                    // Tabs we actually show in the bar. A folder is hidden when:
                    //   1. Its full rule is resolved AND every subscribed channel falls
                    //      outside it (the tab would land on an empty feed), OR
                    //   2. Its full rule is resolved AND is indistinguishable from the
                    //      default "All" scope (`includeChannels=true`, no pins / explicit
                    //      includes / excludes / archive-mute-read narrowing) — the chip
                    //      would just duplicate "Усі".
                    // While rules haven't been resolved yet (cold start / between
                    // UpdateChatFolders and the GetChatFolder fan-out landing), we render
                    // the raw list so the bar doesn't briefly drop folders.
                    val visibleChatIds = remember(posts) {
                        posts.asSequence().map { it.chatId }.toSet()
                    }
                    val tabs = remember(foldersList, fullFoldersMap, folderChatIdsMap, visibleChatIds, folders) {
                        val repo = folders
                        foldersList.mapNotNull { info ->
                            val full = fullFoldersMap[info.id]
                            val ids = folderChatIdsMap[info.id]
                            val keep = when {
                                full == null || repo == null -> true
                                repo.isEquivalentToAll(full) -> false
                                // Membership not yet resolved — keep the tab so it doesn't
                                // briefly drop out between UpdateChatFolders and the eager
                                // folderChatIds warm-up landing.
                                ids == null -> true
                                visibleChatIds.isEmpty() -> true
                                else -> visibleChatIds.any { it in ids }
                            }
                            if (keep) FolderTab(info.id, info.name?.text?.text.orEmpty()) else null
                        }
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

                    // Render gate. The latched [TimelineUiState] is the single source
                    // of truth for what gets mounted:
                    //   • Loading → [SkeletonFeed]: refresh in flight on an empty
                    //     feed, OR reverse-feed items present but cursors not yet
                    //     landed. The LazyColumn is intentionally NOT mounted so
                    //     no transient "wrong sort" / "wrong row at index 0" frame
                    //     can leak.
                    //   • Empty → [EmptyState]: refresh finished and there is
                    //     genuinely nothing to show — Saved tab is empty, queue is
                    //     caught up in OldestUnreadFirst, or no channels followed yet.
                    //   • Ready → [TimelineFeedColumn]: items non-empty, [listState]
                    //     was constructed with `LazyListState(initialIndex, 0)` so
                    //     first paint lands at the correct row in one frame. The
                    //     scroll-gate / prefetch / centered-key / boundary-key
                    //     scaffolding sits inside this branch so observers don't
                    //     attach to a column that isn't mounted.
                    when (val state = latchedUiState) {
                        TimelineUiState.Loading -> {
                            SkeletonFeed(modifier = Modifier.fillMaxSize())
                        }
                        TimelineUiState.Empty -> {
                            val emptyKind = when {
                                showOnlyBookmarked -> EmptyKind.Saved
                                feedOrder == dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst &&
                                    filteredPosts.isNotEmpty() -> EmptyKind.CaughtUp
                                else -> EmptyKind.Default
                            }
                            EmptyState(emptyKind)
                        }
                        is TimelineUiState.Ready -> {
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
                        LaunchedEffect(prefetchAnchor, feedItems) {
                            val firstVisible = prefetchAnchor ?: return@LaunchedEffect
                            if (firstVisible >= feedItems.size) return@LaunchedEffect
                            val end = (firstVisible + PREFETCH_AHEAD).coerceAtMost(feedItems.lastIndex)
                            for (idx in (firstVisible + 1)..end) {
                                val item = feedItems.getOrNull(idx) ?: continue
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
                                    // Deleted-post tombstone: the message is gone server-side
                                    // and its media can't be re-fetched (tdlib/td#3493), so
                                    // prefetching its files only spins doomed downloads through
                                    // the reducer ahead of live posts. The card renders
                                    // observe-only via [LocalMediaPassive]; skip it here too.
                                    if (post.isDeleted) continue
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
                        // Kept as a [State] (not unwrapped via `by`) so the read
                        // of the centre-key value happens INSIDE each item's
                        // per-item `derivedStateOf { state.value == myKey }`
                        // rather than at the items() lambda level. Reading
                        // `.value` directly here would mark this whole branch
                        // as a centre-key reader, invalidating the entire
                        // CompositionLocalProvider subtree on every fling.
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

                        // Snap-fling: when [snapScroll] is enabled, route flings through
                        // [rememberFeedSnapFlingBehavior]. The stock
                        // `rememberSnapFlingBehavior(snapPosition = Start)` is the Reels
                        // idiom — great for fixed-height paginators, hostile to a feed
                        // with mixed-height posts (long posts can't be read mid-way;
                        // gentle flings snap back instead of advancing). Our wrapper
                        // does the discrete-swipe / paginator model: one post per fling
                        // in the gesture direction, with tall-post and low-velocity
                        // bypass that falls through to a free decay-fling. Reference:
                        // Instagram's main feed (not Reels), Twitter and Telegram
                        // channels all skip snap entirely on mixed-height feeds for
                        // exactly these reasons.
                        val flingBehavior = if (snapScroll) {
                            rememberFeedSnapFlingBehavior(lazyListState = listState)
                        } else {
                            ScrollableDefaults.flingBehavior()
                        }

                        // The read→unread divider is now a real [FeedItem.Boundary]
                        // row inserted by [withBoundary] at the feedItems build site
                        // (see above). The LazyColumn renders it directly via
                        // [TimelineFeedColumn]; no derived key lookup is needed here.
                        //
                        // Cold-entry top-align reveal (reverseLayout only). LazyListState
                        // mounts seeded at `state.initialIndex` with scrollOffset = 0,
                        // which under reverseLayout glues the target item's BOTTOM to
                        // the viewport bottom — the divider + unread queue would be
                        // stranded off-screen below. The reveal waits one layout pass
                        // for the target row to measure, then one instant measured-delta
                        // ([topAlignDelta]) reposition lands it at the viewport
                        // top with no wrong-frame flash; the SkeletonFeed cover stays
                        // painted on top until the reveal flips true.
                        //
                        // Skipped in Newest mode (forward layout's scrollOffset = 0
                        // already top-aligns) and on drill-out/drill-in restore
                        // (routeKey unchanged → revealed flag preserved across re-mount).
                        val boundaryRevealed = rememberBoundaryReveal(
                            listState = listState,
                            boundaryIndex = state.initialIndex,
                            enabled = feedOrder.reverseLayout,
                            routeKey = routeKey,
                        )

                        CompositionLocalProvider(LocalScrollGate provides scrollGate) {
                            // The reverse-feed cold-start gate is now upstream:
                            // [buildTimelineUiState] returns Loading in
                            // OldestUnreadFirst until [cursorsLanded] flips true,
                            // so we never reach this branch with cursors absent.
                            // First paint of [TimelineFeedColumn] lands at the
                            // [Ready.initialIndex] row in one frame — no
                            // animate-through, no transient wrong-order frame.
                            Box(modifier = Modifier.fillMaxSize()) {
                                TimelineFeedColumn(
                                    state = listState,
                                    flingBehavior = flingBehavior,
                                    reverseLayout = feedOrder.reverseLayout,
                                    bottomPadding = contentPadding.calculateBottomPadding(),
                                    feedItems = state.items,
                                    centeredItemKeyState = centeredItemKeyState,
                                    highlightedPostKey = highlightedPostKey,
                                    interactions = interactions,
                                    onTapRevisions = { post ->
                                        if (archiveRepository != null) revisionsForPost = post
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (!boundaryRevealed) {
                                    SkeletonFeed(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                        }
                    }
                }
            }

            // Floating "X нових постів" pill — the single commit path for
            // pendingNew arrivals (see ARCHITECTURE.md → Load-bearing).
            //
            // Visible whenever [scopedPendingNew] is non-empty, at EVERY
            // scroll position. Hidden only in:
            //   - Saved tab (no concept of "new" in bookmarks)
            //   - Refresh in flight (PTR runs vm.acceptPending() itself; the
            //     pill would race against it and double-commit).
            //
            // Why no edge-based auto-hide / auto-accept (two iterations
            // tried and reverted):
            //   1. Bare `if (atTop || atBottom) acceptIds()` at the freshness
            //      edge: LazyColumn's keyed-scroll preservation pins the
            //      user's anchor item to its current y-coord, so the
            //      silently-accepted posts render OFF-screen (above the
            //      viewport in Newest, below it in OldestUnreadFirst). The
            //      user sees no visible change AND no pill — feels broken.
            //   2. Follow-up `scrollToItem`-after-`acceptIds`: the latcher
            //      in [rememberLatchedTimelineUiState] commits new items
            //      via a `LaunchedEffect`, so `layoutInfo.totalItemsCount`
            //      lags `feedItems` by a frame. Even with [awaitItemsCommitted]
            //      as the wait helper, the LazyColumn's own overscroll
            //      could still latch the user into the "stuck at the wall"
            //      state before the follow-up scroll resolved. User report:
            //      "scroll to bottom in reverse mode, pill disappears,
            //      never see the new posts even with the auto-scroll".
            //
            // Always-visible pill collapses both: the pill IS the affordance,
            // [awaitItemsCommitted] (which the onClick uses) is the proven
            // sequencer — no edge detection, no race.
            //
            // Mode-dependent placement / direction (single pill, two anchors):
            //   - Newest: TopCenter, ↑ glyph — fresh content lives above.
            //   - OldestUnreadFirst: BottomCenter, ↓ glyph — fresh content
            //     sits at the END of the unread queue (asc by date).
            val pillVisible = !showOnlyBookmarked &&
                !refreshing && scopedPendingChannels.isNotEmpty()
            val pillAtTop = feedOrder != dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst
            // Folder chips occupy the top ~56dp inside the same Box; offset the pill
            // so it lands just below them instead of overlapping.
            val chipsVisible = !showOnlyBookmarked
            val pillTopPadding = if (chipsVisible) 64.dp else 8.dp
            val pillBottomPadding = contentPadding.calculateBottomPadding() + 16.dp
            val pillSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            val pillEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedVisibility(
                visible = pillVisible,
                // Slide from the same edge the pill is anchored to — top-pill
                // slides DOWN from above (-it), bottom-pill slides UP from
                // below (+it). Mirrors the M3 enter-from-anchor vocabulary.
                enter = (if (pillAtTop) {
                    slideInVertically(pillSpatial) { -it }
                } else {
                    slideInVertically(pillSpatial) { it }
                }) + fadeIn(pillEffects),
                exit = (if (pillAtTop) {
                    slideOutVertically(pillSpatial) { -it }
                } else {
                    slideOutVertically(pillSpatial) { it }
                }) + fadeOut(pillEffects),
                modifier = Modifier
                    .align(if (pillAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(
                        top = if (pillAtTop) pillTopPadding else 0.dp,
                        bottom = if (pillAtTop) 0.dp else pillBottomPadding,
                    ),
            ) {
                NewPostsPill(
                    channels = scopedPendingChannels,
                    pendingCount = scopedPendingNew.size,
                    arrowGlyph = if (pillAtTop) "arrow_upward" else "arrow_downward",
                    onClick = {
                        // Ack only scope-visible pending; archive / other-folder
                        // pending stays unread for those tabs. Scroll target lands
                        // the user at the OLDEST of the just-accepted posts so the
                        // new batch reads forward in time — canonical chat-app
                        // "New messages" jump. Data is descending (newest = index 0),
                        // so the oldest arrival is the HIGHEST-index acked row =
                        // indexOfLast. In Newest the home target IS index 0 = newest.
                        val ackedKeys = scopedPendingNew.map { it.chatId to it.id }
                        val preTotal = listState.layoutInfo.totalItemsCount
                        vm.acceptIds(ackedKeys)
                        scope.launch {
                            listState.awaitItemsCommitted(preTotal)
                            val target = when (feedOrder) {
                                dev.lyo.hortay.data.FeedOrder.Newest -> homeScrollIndexState.intValue
                                dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst -> {
                                    val ackedSet = ackedKeys.toHashSet()
                                    val items = feedItemsState.value
                                    val oldestNew = items.indexOfLast { fi ->
                                        fi.posts().any { (it.chatId to it.id) in ackedSet }
                                    }
                                    if (oldestNew >= 0) oldestNew
                                    // Fallback: arrivals cluster at index 0 (newest)
                                    // under the descending data model.
                                    else 0
                                }
                            }
                            listState.smartScrollTo(target)
                            // Pulse on the landing post — matches the divider-anchored jump path.
                            val destinationPost = feedItemsForEffectsState.value
                                .getOrNull(target)?.posts()?.firstOrNull()
                            if (destinationPost != null) {
                                highlightedPostKey = destinationPost.chatId to destinationPost.id
                            }
                        }
                    },
                )
            }

            // Ambient unread-remaining FAB, OldestUnreadFirst-only.
            // Circle silhouette + BottomEnd anchor keep it Gestalt-disjoint
            // from the centred stadium-shaped NewPostsPill, so when both
            // fire at once they read as distinct affordances even though
            // both carry an arrow_downward glyph. See UnreadCounterPill KDoc
            // for the silhouette-based hierarchy rationale.
            //
            // Count uses LIVE readCursors (via `cursorsState`), not the
            // frozen sort snapshot — so the number ticks down as
            // viewport-dwell acks land, giving the user real-time
            // "how much is left to read" feedback even while posts hold
            // their position in the snapshot-sorted queue.
            //
            // Hidden in Saved tab (no unread concept on bookmarks) and
            // when nothing is left to read.
            if (feedOrder == dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst && !showOnlyBookmarked) {
                val unreadRemaining by remember(visiblePosts, feedOrder) {
                    derivedStateOf {
                        visiblePosts.count { it.isUnreadAt(cursorHolder[it.chatId]) }
                    }
                }
                AnimatedVisibility(
                    visible = unreadRemaining > 0,
                    enter = slideInVertically(pillSpatial) { it } + fadeIn(pillEffects),
                    exit = slideOutVertically(pillSpatial) { it } + fadeOut(pillEffects),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            bottom = pillBottomPadding + unreadPillExtraBottomPadding,
                        ),
                ) {
                    UnreadCounterPill(
                        count = unreadRemaining,
                        onClick = {
                            // Jump to the LIVE oldest-unread post — top-aligned so
                            // reading starts at its top, with a brief highlight.
                            // [homeScrollIndexState] tracks `continueReadingIndex` over
                            // the LIVE cursor map (recompute on every dwell-ack), so the
                            // target advances as you read: tap → read → tap → the NEXT
                            // unread, never the same post twice.
                            //
                            // NOT the frozen [FeedItem.Boundary] divider's anchor: that
                            // divider is a session marker captured at the last refresh
                            // ([boundaryCursors]) and does NOT move as you read, so
                            // jumping to it sent you back onto a post you had already
                            // read — the "unread pill lands on an already-read post" bug.
                            //
                            // Read [feedItemsState] (live) so a refresh / arrival between
                            // this composition and the click doesn't index a stale list.
                            val items = feedItemsState.value
                            val target = homeScrollIndexState.intValue
                            val landedPost = items.getOrNull(target)?.posts()?.firstOrNull()
                            // A deliberate "next unread" jump is itself an explicit read
                            // signal — at least as strong as tapping a post open. Ack the
                            // landed post NOW (synchronously on tap, before the scroll) via
                            // the same explicit-tap path (album-aware + shares the dwell dedup
                            // set) instead of leaning on the viewport dwell to re-detect it.
                            //
                            // Two reasons it must be here, not after the scroll completes:
                            //  1. After a PROGRAMMATIC scroll the dwell's re-arm is racy. It
                            //     fires only on a fresh settled `distinctUntilChanged` viewport
                            //     tuple held idle for READ_DWELL_MS, but once we land the
                            //     focus-tracker OpenChats this chat and the live interaction-info
                            //     stream churns card heights, so a neighbour keeps crossing the
                            //     60% line and collectLatest resets the 500 ms window before it
                            //     completes; a zero-delta top-align produces no settle transition
                            //     at all. The cursor then never advanced, the counter never ticked
                            //     down, and the pill re-landed on the same post — the bug this ack
                            //     closes.
                            //  2. Acking inside the launch AFTER `scrollToBoundary` would be
                            //     skipped if the user grabs the list mid-animation: the gesture
                            //     cancels `animateScrollBy`, the CancellationException unwinds the
                            //     coroutine, and the ack never runs — re-opening the same bug in
                            //     exactly the "I scrolled a bit" path. Doing it on tap is immune.
                            // Gated on target > 0 so the caught-up landing on an already-read
                            // newest post stays a no-op.
                            if (target > 0 && landedPost != null) {
                                markPostReadState.value(landedPost)
                            }
                            scope.launch {
                                if (target > 0) {
                                    listState.scrollToBoundary(rowIndex = target, animated = true)
                                } else {
                                    // Caught up (target == 0): land on newest by its
                                    // natural reverseLayout position, no top-align.
                                    listState.smartScrollTo(target)
                                }
                                landedPost?.let { highlightedPostKey = it.chatId to it.id }
                            }
                        },
                    )
                }
            }

        }
    }

    // Revision sheet: shown when the user taps EditedChip on a post.
    val currentRevisionPost = revisionsForPost
    if (currentRevisionPost != null && archiveRepository != null) {
        val revisions by archiveRepository
            .observeRevisions(
                ChatRef.tdlib(currentRevisionPost.chatId),
                currentRevisionPost.id.toString(),
            )
            .collectAsState(initial = persistentListOf())
        PostRevisionSheet(
            revisions = revisions,
            onDismiss = { revisionsForPost = null },
            onOpenInTelegram = {
                scope.launch {
                    PostActions.openInTelegram(context, tdlibRepo, currentRevisionPost)
                }
            },
            mediaStore = archiveMediaStore,
        )
    }

}

/** How long after the last keystroke we issue the search round-trip. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * How long the OldestUnreadFirst cold-start gate waits AFTER refresh has
 * settled for TDLib's first `UpdateChatReadInbox` burst to arrive before
 * giving up and rendering the feed anyway. Without this fallback, a brand-new
 * account (no chats ever opened → no cursors ever sent by TDLib) would hang
 * on the empty placeholder forever — the cursor map only seeds when
 * `lastReadInboxMessageId > 0` (see [PostsRepository] ~L274).
 *
 * 800 ms is the empirical 95th-percentile for cursor arrival on a real
 * cold start. If they're going to come at all they're already here by then;
 * if not, the user is almost certainly on a fresh account and we should
 * land them on the bottom of the asc-by-date feed (= newest) rather than
 * stall.
 */
private const val COLD_START_CURSOR_GRACE_MS = 800L

/**
 * Recency floor for cold-start landing in [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst].
 *
 * The aggregated feed across 100+ subscriptions can have a single dormant channel
 * whose only unread post is weeks old; in asc-by-date sort that post sits at the
 * top, and the naive `indexOfFirst { isUnread }` boundary picker strands the user
 * there ("I open the feed, it shows an old post"). With this floor, only unread
 * posts dated within the window pull the landing — older unread is still rendered
 * above the boundary so the user can scroll up to it, it just doesn't anchor the
 * cold-start view. If nothing unread qualifies, the picker falls through to the
 * caught-up landing (newest, at the bottom).
 *
 * 7 days covers "back from a long weekend" comfortably while filtering out
 * months-old dormant unread that almost certainly isn't where the user wants to
 * resume. The boundary divider above this floor is unaffected — it tracks the
 * read-edge for display, not the landing target.
 */
private const val BOUNDARY_RECENCY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

/** Avatars in the "X нових постів" pill — same cap as the original VM-side limit. */
private const val MAX_PILL_BADGES = 3

/**
 * Viewport-stable dwell required before a post counts as "read". Triggers
 * `viewMessages(forceRead=true)`, which advances `lastReadInboxMessageId` and clears
 * the unread badge in the official Telegram client. 500 ms starts AFTER scrolling stops
 * (the `isScrollInProgress == false` gate already filters fling-throughs), so in practice
 * this is "user settled on a post, held for half a second" — short enough to feel
 * responsive, long enough to avoid acking a post the user merely paused on while reaching
 * for back. Higher (≥1 s) reads as laggy: "I clearly read this, why is it still bold?".
 */
private const val READ_DWELL_MS = 500L

/**
 * Viewport-stable dwell before we promote the dominant-visible post's chat to OpenChat
 * in TDLib. Per tdlib/td#2312 only opened chats receive realtime reaction / view-counter
 * / comment-count updates, so this is what makes interaction info come alive on the
 * post the user is actually looking at. Slightly above [READ_DWELL_MS] so the
 * focus signal lags read-ack — a post that's still in transition past the centre when
 * the user resumes scrolling never gets OpenChat'd, while a deliberate stop fires
 * within human-perceptible latency. The pre-2026-05 value of 1500 ms was tuned for an
 * `firstVisibleItem`-based tracker that opened the wrong chat anyway; with the
 * max-visible-area picker the focus signal is accurate enough that we can shorten
 * the dwell without thrashing OpenChat on flings (collectLatest cancels in-flight
 * delays on every focus change, so a fling never accumulates open/close churn).
 */
private const val FOCUS_DWELL_MS = 600L

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

