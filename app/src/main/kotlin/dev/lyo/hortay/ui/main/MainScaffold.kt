@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package dev.lyo.hortay.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.nav.ArchiveKey
import dev.lyo.hortay.data.nav.ArchiveSettingsKey
import dev.lyo.hortay.data.nav.ChannelKey
import dev.lyo.hortay.data.nav.CommentsKey
import dev.lyo.hortay.data.nav.HomeKey
import dev.lyo.hortay.data.nav.WebChannelKey
import dev.lyo.hortay.data.posts.PublicHandleResult
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.report.ReportTarget
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.users.LocalUserProfileOpener
import dev.lyo.hortay.ui.users.UserProfileOpener
import kotlinx.coroutines.launch

/**
 * How long a channel-open tap is allowed to wait for
 * [PostsRepository.loadChannelHistory] before pushing [ChannelKey]
 * anyway. See the KDoc on `pushChannel` for the rationale on awaiting the
 * prefetch instead of pushing in parallel.
 *
 * 400 ms sits inside the "feels responsive" perceptual band (under 500 ms is
 * not consciously read as lag), but well above the typical local-cache /
 * Wi-Fi RPC time for `GetChatHistory` (~100-250 ms in steady state). The
 * common case is the tap looks instant; only slow paths cross the timeout,
 * and the destination's [ChannelUiState.Resolving] gate paints a skeleton
 * once mounted so the user gets visible feedback rather than a hanging tap.
 */
private const val CHANNEL_PUSH_PREFETCH_TIMEOUT_MS = 400L

/**
 * Top-level container that owns nav-tab state, the global channel filter and the comments
 * overlay, then dispatches the four primary surfaces.
 *
 * Sub-composables split out (all in this package):
 *  - [DeepLinkDispatcher]  — collects [AppGraph.deepLinkRouter] events and routes to nav pushes.
 *  - [TabContentSwitcher]  — Feed / Channels / Saved / Profile AnimatedContent crossfade.
 *  - [RenderNavKey]        — maps one Navigation 3 detail key to its screen (per `entry<T>`).
 *  - [MainScaffoldDialogs] — invite preview, report flow sheet, user profile sheet.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    // Navigation state — plain `remember`, deliberately NOT `rememberSaveable`. Tab
    // selection and the channel back-stack reset to defaults on every fresh Activity
    // create (cold launch, swipe-from-recents, memory-pressure restart), so opening
    // the app always lands on the Feed top. The previous saveable form caused a
    // recurring UX complaint: closing on the Saved tab (or several channels deep)
    // reopened the app exactly there, even after an overnight gap. Twitter / Telegram
    // / Instagram all reset their top-level navigation on cold launch — restoring
    // multi-hour-old navigation reads as the app teleporting the user somewhere
    // stale.
    //
    // Trade-off: rotation also resets navigation. Hortay is portrait-default with
    // no landscape-specific layout, so the practical cost is near-zero. Scroll
    // positions inside individual screens stay `rememberSaveable` (via the parent
    // `SaveableStateProvider` chain) so configuration changes and memory-pressure
    // recoveries within a session preserve in-screen state — only the top-level
    // route resets.
    //
    // Detail navigation runs on the Navigation 3 back stack [AppGraph.backStack]
    // (a SnapshotStateList of [AppNavKey]); each push appends a key — permits
    // unlimited nesting in the Telegram-Android pattern: channel → comments →
    // channel → comments → …
    //
    // NavDisplay owns back gestures + predictive back, and its entry decorators
    // give every entry its own saveable-state bag and ViewModelStore (cleared on
    // pop) — replacing the old per-entryId SaveableStateProvider + viewModel(key)
    // wiring and the per-chatId ViewModelStore leak it caused.
    var selectedTab by remember { mutableStateOf(NavTab.Feed) }
    // Navigation 3 back stack on the graph. Root is always [HomeKey] (the tab scaffold renders
    // beneath the NavDisplay), detail keys push on top, so `size > 1` ⇔ an overlay is showing.
    val backStack = graph.backStack
    val hasOverlay = backStack.size > 1

    val scope = rememberCoroutineScope()

    // Nav helpers route through [AppGraph.nav]. The active tab is NOT
    // touched on push — under the nav-overlay the user's originating tab
    // (Channels, Saved, …) keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the top
    // overlay layer; tab restoration is automatic because we never moved
    // away from it.
    //
    // Await-prefetch contract for channel-opens. The push waits for the
    // deep history load to settle (up to [CHANNEL_PUSH_PREFETCH_TIMEOUT_MS])
    // before mounting [ChannelKey]. On warm re-entry the cooldown
    // short-circuit inside [PostsRepository.loadChannelHistory] returns
    // immediately, so the tap → push transition is still effectively
    // instant. On cold first entry — the case where
    // [PostsRepository.refreshLocked]'s cold-start harvest has populated
    // exactly one post per channel from `Chat.lastMessage` — the wait
    // ensures the [_posts] slice is full BEFORE the destination mounts.
    // Otherwise the user would see the LazyColumn lay out with one post,
    // then 79 older posts merge in above mid-frame; in OldestUnreadFirst
    // (asc-by-date, newer at the bottom) the visible row reads as
    // "stretching" while older history pops in over the top — the
    // user-reported "стрімає, посто двигається" symptom.
    //
    // The timeout is a safety: slow networks / FLOOD_WAIT can stall
    // loadChannelHistory past the perceptible-lag threshold, and a
    // tap that hangs forever is worse than a brief skeleton. After
    // [CHANNEL_PUSH_PREFETCH_TIMEOUT_MS] we push regardless, and the
    // destination's existing [ChannelUiState.Resolving] gate paints
    // the skeleton until the load finally lands.
    //
    // This deliberately walks back the "push is instant; prefetch is
    // fire-and-forget" rule from the earlier tap-navigation contract
    // (see [data/TapNavigation.kt]). The earlier rationale assumed the
    // destination-side anti-flicker grace alone was enough — it is for
    // Newest sort where the single cold-harvest post happens to sit at
    // the LazyColumn's lastIndex and 79 history posts merge in below
    // the viewport, invisible. In OldestUnreadFirst the geometry
    // inverts: the cold-harvest post sits at the bottom (newest) of
    // an asc-sort, and history insertions land above — squarely in
    // the user's field of view.
    val pushChannel: (Long, Long?) -> Unit = { chatId, scrollTo ->
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(CHANNEL_PUSH_PREFETCH_TIMEOUT_MS) {
                graph.postsRepository.loadChannelHistory(chatId)
            }
            graph.backStack.add(ChannelKey(chatId = chatId, scrollToMessageId = scrollTo))
        }
        Unit
    }
    // Same parallel-prefetch contract as [pushChannel], applied to the
    // comments overlay. [primeCommentsForOpen] kicks off [prefetchThread]
    // so the anchor resolve and one batch of history land in TDLib's
    // local DB. The screen-side grace decides whether to paint the
    // loading overlay.
    val pushComments: (TimelinePost) -> Unit = { post ->
        graph.commentsRepository.primeCommentsForOpen(post)
        graph.backStack.add(CommentsKey(anchor = post))
    }
    // Hero-open from a feed/channel post tap or its "Показати більше": open the full post at the
    // reading position the user left — [anchorY] is the post's absolute on-screen Y in the feed, so
    // a long post the user scrolled into opens showing the SAME content, not jumped to the top. The
    // post-card → open-post container-transform morph ([postMorph]) layers on top: with the anchor
    // landing where the card sat, the morph reads as the card expanding in place into the full post.
    val pushCommentsHero: (TimelinePost, Int) -> Unit = { post, anchorY ->
        graph.commentsRepository.primeCommentsForOpen(post)
        graph.backStack.add(CommentsKey(anchor = post, heroAnchorY = anchorY))
    }
    // Pop a detail entry. Guarded so the [HomeKey] root is never removed (NavDisplay always
    // needs a root; an empty stack would crash it).
    val popNav: () -> Unit = {
        if (graph.backStack.size > 1) graph.backStack.removeAt(graph.backStack.lastIndex)
    }

    // Monotonic counter: each re-tap on Home (or brand) bumps it once. The Feed observes the
    // value and decides scroll-to-top vs refresh based on its own scroll position.
    var homeTapTrigger by remember { mutableLongStateOf(0L) }
    val connection by graph.tdClient.connection.collectAsStateWithLifecycle()
    val floodWaitUntilMs by graph.tdClient.floodWaitUntilMs.collectAsStateWithLifecycle()

    // Single SnackbarHost owned by the scaffold so transient errors land on whichever
    // tab the user is currently looking at. Subscribing to the bus only while composed
    // means messages buffered during foreground transitions get delivered as soon as
    // we resume; a flooded bus drops oldest (see [UserMessageBus]) so we never queue
    // a stale apology that no longer reflects the current state.
    val snackbarHostState = remember { SnackbarHostState() }
    UserMessageSnackbarRelay(graph = graph, hostState = snackbarHostState)

    val res = LocalContext.current.resources

    // User-profile sheet pendant. Local state — unlike the report flow, no TDLib write
    // is staged in here, so a rotation just re-fetches the profile (cheap, three cached
    // local reads in the steady state). [UserProfileOpener] is a `fun interface` so
    // re-providing the local on every recomposition still preserves equality identity
    // for skippable propagation under the provider.
    //
    // Declared above the channel-open gates and the DeepLinkDispatcher so both can
    // route `PublicHandleResult.User` straight to the in-app sheet — same surface as
    // an in-text `TextEntityTypeMentionName` tap, no Telegram-client bounce.
    var pendingUserId by remember { mutableStateOf<Long?>(null) }
    // Soft-gated by design — the sheet renders with `null` profile and the
    // seed name / avatar from the trigger (PostCard sender row, in-text
    // mention, forward chip), so it's never blank on first frame. The
    // sheet's own `LaunchedEffect(userId)` then runs `GetUser` +
    // `GetUserFullInfo`; bio / personal-channel rows fade in as fields
    // populate. No push-side prefetch — the sheet enters its animation
    // and fetches in parallel, like every other tap target in the app.
    val userProfileOpener = remember {
        UserProfileOpener { userId -> pendingUserId = userId }
    }

    /**
     * Gated channel-open for in-app gestures (forward-source chip, cross-channel
     * quote-tap, post-channel-name tap when it differs from the host, channel /
     * author-chip / reply-quote affordances inside a Comments overlay). Mirrors
     * the type-gate the deep-link dispatcher runs against
     * [dev.lyo.hortay.data.DeepLink]. Non-channel targets (basic group,
     * supergroup-chat) surface a kind-keyed snackbar; 1:1 user / bot targets
     * open the in-app user-profile sheet, matching how `@username` mentions
     * resolve. Hortay's product scope is broadcast channels only, so the right
     * answer for groups is the snackbar — same as the deep-link path.
     *
     * Smart back-stack shortcut: when the destination matches the [ChannelKey]
     * directly below the current top and no scroll target is requested, this acts
     * as a pop instead of a push. The user is asking to return to a channel that
     * is already one swipe-back away — stacking a duplicate would force a
     * double-back to exit AND remount the original. Pop preserves both the existing
     * entry's scroll / ViewModel and natural back semantics. Two surfaces hit
     * this uniformly: tap-channel-chip / tap-author-header inside a Comments
     * overlay anchored at a post of its own channel, and tap-forward-source
     * inside Channel-B for a post originally from Channel-A when A sits directly
     * below in the stack.
     *
     * The shortcut is gated on `scrollTo == null`: a reply-quote tap with an
     * explicit `replyToMessageId` needs a fresh entry to honour the target —
     * the already-mounted channel below holds its own scroll state and won't
     * react to a different anchor. The redundant duplicate is the lesser evil
     * there (back-swipe sequence is still correct).
     *
     * User-case overlay collapse: when the resolved kind is a 1:1 user / bot
     * AND we're currently inside a Comments overlay, pop it before surfacing
     * the user-profile sheet — the "go to original" promise can't resolve to a
     * channel screen, so the overlay has nothing left to show. For feed /
     * channel surfaces the top isn't Comments and the overlay stays put.
     */
    val safelyOpenChannel: (Long, Long?) -> Unit = { chatId, scrollTo ->
        scope.launch {
            when (val resolved = graph.postsRepository.resolveChatKind(chatId)) {
                is PublicHandleResult.Channel -> {
                    val below = graph.backStack.getOrNull(graph.backStack.lastIndex - 1)
                    val matchesBelow = scrollTo == null &&
                        below is ChannelKey &&
                        below.chatId == resolved.chatId
                    if (matchesBelow) popNav()
                    else pushChannel(resolved.chatId, scrollTo)
                }
                is PublicHandleResult.User -> {
                    if (graph.backStack.lastOrNull() is CommentsKey) popNav()
                    userProfileOpener.open(resolved.userId)
                }
                is PublicHandleResult.Unsupported -> {
                    graph.userMessages.post(
                        res.getString(unsupportedHandleMessageId(resolved.kind)),
                        UserMessageBus.Severity.Info,
                    )
                }
                is PublicHandleResult.NotFound -> {
                    graph.userMessages.post(res.getString(R.string.link_not_found))
                }
            }
        }
        Unit
    }

    DeepLinkDispatcher(
        router = graph.deepLinkRouter,
        userMessages = graph.userMessages,
        linkDialogs = graph.linkDialogs,
        resolvePublicHandle = graph.postsRepository::resolvePublicHandle,
        resolveChatKind = graph.postsRepository::resolveChatKind,
        previewChatInvite = graph.channelActions::previewChatInvite,
        onPushChannel = pushChannel,
        onOpenUser = { userId -> userProfileOpener.open(userId) },
    )

    // Pending report: (chatId, messageId, token). Hoisted off local state onto
    // [AppGraph.reportDialogs] so a rotation mid-flow doesn't drop the sheet on
    // the floor — `ReportFlowViewModel` keeps partial answers across TDLib
    // roundtrips, and a re-created composition starting with null target would
    // erase the user's progress visibly.
    val openReport: (Long, Long?) -> Unit = { chatId, messageId ->
        graph.reportDialogs.open(ReportTarget(chatId, messageId, System.nanoTime()))
    }
    val onLinkNotFound: () -> Unit = {
        graph.userMessages.post(res.getString(R.string.link_not_found), UserMessageBus.Severity.Info)
    }
    // Bottom inset handed to full-screen detail screens that aren't self-contained Scaffolds for
    // the bottom edge (ChannelScreen reads contentPadding.bottom for its list clearance). The
    // tab scaffold under HomeKey owns the FloatingNavBar inset itself; details only need to clear
    // the system navigation bar. CommentsScreen / ArchiveScreen are full Scaffolds and ignore it.
    val detailContentPadding = WindowInsets.navigationBars.asPaddingValues()

    // Predictive back for the detail stack is owned by NavDisplay now (it animates the leaving
    // entry and reveals the one below during the gesture — what the hand-rolled top-2 renderer
    // used to do). Only the "at root, not on Feed → return to Feed" case stays a plain
    // BackHandler; when a detail overlay is up, NavDisplay consumes back first.
    BackHandler(enabled = !hasOverlay && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // Saveable-state holder for the tab AnimatedContent inside the HomeKey scene. Each tab
    // (Feed / Channels / Saved / Profile) gets its own SaveableStateProvider scope keyed by
    // tab name, so rememberSaveable / list-state / scroll-state inside each tab survives the
    // AnimatedContent mount/unmount cycle. Detail-screen state (channel, comments) is NOT held
    // here — NavDisplay's entry decorators own per-entry saveable state + ViewModelStore.
    val tabStateHolder = rememberSaveableStateHolder()

    // Live cursor holder collected once, mutated in place via diff-apply so
    // per-key Compose snapshot subscribers (PostCard, ↓N counter, boundary
    // derivedStateOf) are invalidated only when their own chat's cursor
    // changes. The previous `collectAsStateWithLifecycle()` over a
    // PersistentMap-typed flow swapped a fresh map identity into the
    // `staticCompositionLocalOf<ReadCursors>` on every put — which
    // invalidated the entire CompositionLocalProvider subtree (including
    // the feed LazyColumn) for every dwell-ack and external read sync,
    // producing the per-frame jank the user reported during scroll.
    val cursorHolder =
        dev.lyo.hortay.ui.timeline.rememberCursorHolder(graph.postsRepository.chatReadCursors)
    val feedOrder by graph.settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst,
    )
    val snapScroll by graph.settingsStore.snapScroll.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val inlineVideoAutoplay by graph.settingsStore.inlineVideoAutoplay.collectAsStateWithLifecycle(
        initialValue = true,
    )

    // Mode-agnostic read-state ack handed to TimelineScreen / ChannelScreen. TDLib
    // mode groups the dwell-batch by chatId and bridges to viewMessages(forceRead=true)
    // — the canonical TDLib path that advances `lastReadInboxMessageId` server-side
    // and surfaces the read through to the official Telegram client.
    //
    // `remember`-wrapped on the stable AppGraph identity so the lambda referenced
    // by `markAsRead` keeps the same instance across MainScaffold recompositions.
    // Without this, every recomposition allocates a fresh closure, breaking
    // skippability of TimelineScreen's `interactions = remember(...)` and
    // `ackedRead = remember(markAsRead)` blocks — which would trigger redundant
    // `viewMessages` RPCs on every dwell-batch evaluation.
    val tdlibMarkAsRead: suspend (List<TimelinePost>) -> Unit = remember(graph) {
        { batch ->
            batch.groupBy { it.chatId }.forEach { (chatId, group) ->
                // Expand each post to every album-member id so TDLib advances
                // lastReadInboxMessageId past the LAST member, not just the
                // anchor (anchor = lowest id, so an album-aware comparison in
                // isUnreadIn would otherwise re-light the card as unread until
                // the cursor crossed every member). Solo posts contribute
                // their own id via the ifEmpty fallback.
                val ids = group.flatMap { post ->
                    post.albumMessageIds.ifEmpty { listOf(post.id) }
                }.distinct()
                graph.postsRepository.viewMessages(chatId, ids)
            }
        }
    }
    // Same stability concern as [tdlibMarkAsRead]: TimelineScreen / ChannelScreen
    // hold `onReportClick` and `canReport` as parameters that feed into
    // `interactions = remember(...)`. Fresh lambdas per recomposition would invalidate
    // that remember block and propagate unstable callbacks down to PostCard.
    val onPostReportClick = remember(graph) {
        { post: TimelinePost ->
            graph.reportDialogs.open(
                ReportTarget(
                    post.chatId,
                    if (post.id != 0L) post.id else null,
                    System.nanoTime(),
                ),
            )
        }
    }
    val canReportPost = remember { { post: TimelinePost -> post.canReportChat } }

    // Wrap the entire content tree with the in-app UriHandler. Every descendant call
    // — LinkAnnotation.Url taps in post bodies, WebPreviewCard opens, AddChannelSheet
    // affordances, settings author rows — goes through this handler, which checks each
    // URL against the Telegram link resolver before falling back to the OS. One
    // interceptor wired here is cheaper than wrapping every Text call-site individually
    // and guarantees no path leaks straight to ACTION_VIEW.
    LinkAwareScaffold(graph) {
        CompositionLocalProvider(
            LocalReadCursors provides cursorHolder,
            dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay provides inlineVideoAutoplay,
            LocalUserProfileOpener provides userProfileOpener,
            LocalUserMessageBus provides graph.userMessages,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = {
                    SnackbarHost(snackbarHostState) { data ->
                        Snackbar(snackbarData = data)
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { scaffoldPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
                    // Navigation 3 owns every scene. HomeKey renders the tab scaffold (feed +
                    // bottom nav bar); detail keys render full-screen over it. A predictive-back
                    // swipe therefore parallaxes BOTH the leaving detail and the entering tab
                    // content (the canonical system motion) — no hand-rolled overlay stacking,
                    // nav-bar hide animation, or transparent-root tricks. Entry decorators give
                    // each entry its own saveable state + ViewModelStore (cleared on pop),
                    // replacing the old per-entryId holder and fixing the per-chatId VM leak.
                    // SharedTransitionLayout wraps the NavDisplay so the feed/channel card and the
                    // open-post pinned anchor can morph into each other (Apple/Telegram container
                    // transform). The scope is published via [LocalPostMorphScope] so [postMorph]
                    // call sites (feed cards, comments anchor) can tag their shared bounds.
                    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                      val morphScope = this
                      CompositionLocalProvider(LocalPostMorphScope provides morphScope) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { popNav() },
                        sharedTransitionScope = morphScope,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        // Horizontal shared-axis: detail slides in/out from the side, the scene
                        // below parallaxes a third of the width — so a predictive-back swipe moves
                        // BOTH layers (not the default shrink-to-centre).
                        //
                        // All three specs are left BARE — no explicit animationSpec. This is the
                        // canonical Navigation 3 configuration (see the official `animate-destinations`
                        // sample) and it keeps `predictivePopTransitionSpec` seekable, so the gesture
                        // tracks the finger smoothly from the first pixel. An earlier attempt to ride
                        // the commit specs on `motionScheme.fastSpatialSpec` made the predictive seek
                        // jerk at the start of the drag — a fast spring, when scrubbed by raw finger
                        // progress, has a non-linear response near 0; the default bare-slide spring
                        // scrubs cleanly. Do NOT add a spec back here to "settle faster": predictive
                        // back is gated on the entering scene reaching RESUMED, and trading smooth
                        // scrubbing for a slightly-shorter arm-window is the wrong call (see commit
                        // history for the reverted fastSpatialSpec experiment).
                        transitionSpec = {
                            slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<HomeKey> {
                                Scaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    bottomBar = {
                                        FloatingNavBar(
                                            selected = selectedTab,
                                            onSelect = { tab ->
                                                // Re-tapping the active Feed pill bumps
                                                // homeTapTrigger (scroll-to-top, or refresh if
                                                // already there); otherwise switch tab.
                                                val reselectingActiveFeed =
                                                    tab == NavTab.Feed && tab == selectedTab
                                                if (reselectingActiveFeed) homeTapTrigger = System.nanoTime()
                                                selectedTab = tab
                                            },
                                        )
                                    },
                                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                ) { homePadding ->
                                    TabContentSwitcher(
                                        selectedTab = selectedTab,
                                        tabStateHolder = tabStateHolder,
                                        graph = graph,
                                        padding = homePadding,
                                        feedOrder = feedOrder,
                                        snapScroll = snapScroll,
                                        homeTapTrigger = homeTapTrigger,
                                        coveredByOverlay = hasOverlay,
                                        scope = scope,
                                        onHomeTapTriggerBump = { homeTapTrigger = System.nanoTime() },
                                        onSafelyOpenChannel = safelyOpenChannel,
                                        onPushChannel = pushChannel,
                                        onPushComments = pushComments,
                                        onShowFullPost = pushCommentsHero,
                                        onPostReportClick = onPostReportClick,
                                        canReportPost = canReportPost,
                                        tdlibMarkAsRead = tdlibMarkAsRead,
                                    )
                                }
                            }
                            entry<ChannelKey> { key ->
                                RenderNavKey(
                                    key, graph, detailContentPadding, feedOrder, scope, popNav,
                                    pushChannel, pushComments, pushCommentsHero, safelyOpenChannel,
                                    openReport, onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<CommentsKey> { key ->
                                RenderNavKey(
                                    key, graph, detailContentPadding, feedOrder, scope, popNav,
                                    pushChannel, pushComments, pushCommentsHero, safelyOpenChannel,
                                    openReport, onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<ArchiveKey> { key ->
                                RenderNavKey(
                                    key, graph, detailContentPadding, feedOrder, scope, popNav,
                                    pushChannel, pushComments, pushCommentsHero, safelyOpenChannel,
                                    openReport, onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<ArchiveSettingsKey> { key ->
                                RenderNavKey(
                                    key, graph, detailContentPadding, feedOrder, scope, popNav,
                                    pushChannel, pushComments, pushCommentsHero, safelyOpenChannel,
                                    openReport, onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            // Defensive: [WebChannelKey] is guest-mode only and never pushed by this
                            // scaffold. But the back stack is shared across both scaffolds (see
                            // [AppGraph.backStack]); a guest→auth sign-in performed while a guest drill
                            // was on the stack can leave a foreign key on top for the first frame after
                            // MainActivity re-routes here. NavDisplay requires an entry for every key on
                            // the stack — without this it would crash on that frame. The entry renders
                            // nothing and pops itself, self-healing the stack back to a valid auth state
                            // (the Nav3 equivalent of the old guest renderer's defensive `is`-skip).
                            entry<WebChannelKey> { LaunchedEffect(Unit) { popNav() } }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                      }
                    }

                    ConnectionBanner(
                        status = connection,
                        floodWaitUntilMs = floodWaitUntilMs,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding(),
                    )
                }
            }

            MainScaffoldDialogs(
                graph = graph,
                scope = scope,
                pendingUserId = pendingUserId,
                onUserSheetDismiss = { pendingUserId = null },
                onPushChannel = pushChannel,
            )
        }
    }
}
