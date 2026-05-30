@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * [PostsRepository.loadChannelHistory] before pushing [NavEntry.Channel]
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
 *  - [NavOverlayRenderer]  — top-2 entries of the polymorphic nav stack as overlay layers.
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
    // Unified polymorphic nav-stack on [AppGraph.nav]. Each push is a new layer
    // (no dedup on repeated chatIds) — permits unlimited nesting in the
    // Telegram-Android pattern: channel → comments → channel → comments → …
    //
    // Top entry receives back gestures + predictive back. Each entry has its
    // own stable [NavEntry.entryId] (UUID), used as the key for the per-entry
    // [SaveableStateProvider] and `viewModel(key)` so each push is an isolated
    // screen instance with its own scroll position and ViewModel — pushing the
    // same channel twice produces two independent screens.
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
    // before mounting [NavEntry.Channel]. On warm re-entry the cooldown
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
    // Hero-open from a feed/channel post tap or its "Показати більше": open the full post
    // positioned at [anchorY] — the post's absolute on-screen Y captured in the feed — so it
    // lands exactly where it sat (can be negative for a long post scrolled past its top).
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
     * Smart back-stack shortcut: when the destination matches the [NavEntry.Channel]
     * directly below the current top and no scroll target is requested, this acts
     * as a pop instead of a push. The user is asking to return to a channel that
     * is already one swipe-back away — stacking a duplicate would force a
     * double-back to exit AND remount the original (the `stack.takeLast(2)` window
     * in [NavOverlayRenderer] would evict it). Pop preserves both the existing
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

    // Detail-stack push/pop motion for NavDisplay: a horizontal shared-axis slide + fade on the
    // app's expressive motion scheme, so nav transitions (and the predictive-back peek, which
    // rides the same pop spec) feel like the rest of the chrome instead of NavDisplay's default.
    // Captured here in composable scope because the spec lambdas run outside composition. The
    // entering "below" layer slides only a fifth of the width (parallax) while the leaving layer
    // travels the full width — the standard Android detail-pane back motion.
    val navSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val navEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // Predictive back for the detail stack is owned by NavDisplay now (it animates the leaving
    // entry and reveals the one below during the gesture — what the hand-rolled top-2 renderer
    // used to do). Only the "at root, not on Feed → return to Feed" case stays a plain
    // BackHandler; when a detail overlay is up, NavDisplay consumes back first.
    BackHandler(enabled = !hasOverlay && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // SaveableStateHolders must live in MainScaffold's @Composable body, NOT inside
    // the Scaffold content lambda. The Scaffold body owns the tab AnimatedContent;
    // the nav-overlay sits OUTSIDE that lambda (above the tab chrome and
    // FloatingNavBar). Declaring the holder inside the Scaffold lambda would put it
    // out of scope for the overlay's SaveableStateProvider call. One declaration at
    // this level lets both call-sites capture the same reference.
    //
    // [navStateHolder] keys per-NavEntry by its stable UUID `entryId`. Each push
    // — channel or comments — gets its own SaveableStateProvider scope, so
    // pushing the same channel twice (legitimate in unlimited-nesting flows)
    // produces two independent screens with their own scroll positions.
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
                bottomBar = {
                    // Hide the nav-bar while a nav-overlay is visible (Telegram /
                    // Twitter / Instagram all do this for drilled-in screens — the
                    // overlay owns the bottom edge so the last row of content isn't
                    // occluded).
                    //
                    // Reserved-slot animation, NOT height-collapse: we keep the bar's
                    // measured height stable across the show/hide transition and
                    // visually slide-and-fade its content via `graphicsLayer`. The
                    // earlier [AnimatedVisibility] form used `expandVertically /
                    // shrinkVertically`, which animates the Scaffold's bottomBar slot
                    // height — that propagates through [PaddingValues] into
                    // TimelineScreen's `contentPadding.bottom` and re-lays out the
                    // LazyColumn every frame of the animation. Stable
                    // `firstVisibleItemIndex` keeps the top edge anchored, but the
                    // bottom-padding delta shifts which rows fit in the viewport — read
                    // by the user as a small scroll jitter on overlay return. The
                    // overlay covers the full screen anyway, so the reserved space
                    // sitting behind it is invisible during the navigation window.
                    val navBarVisible = !hasOverlay
                    val navBarAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (navBarVisible) 1f else 0f,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "navbar-alpha",
                    )
                    val navBarSlide by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (navBarVisible) 0f else 1f,
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        label = "navbar-slide",
                    )
                    Box(
                        modifier = Modifier.graphicsLayer {
                            alpha = navBarAlpha
                            // `navBarSlide = 1f` translates the content fully off the
                            // bottom edge of its own slot — the measured slot height
                            // stays at the bar's natural value either way.
                            translationY = navBarSlide * size.height
                        },
                    ) {
                        FloatingNavBar(
                            selected = selectedTab,
                            onSelect = { tab ->
                                // Three distinct cases when the user taps the Home pill while
                                // selectedTab is already Feed. Telegram-Android / Twitter / X all
                                // settle on the same rule, surfaced explicitly here:
                                //  (a) User on Feed AND re-tapping the active Home tab.
                                //      Canonical "tap home twice" gesture: bump homeTapTrigger
                                //      so TimelineScreen scrolls to top (or refreshes if already
                                //      there).
                                //  (b) User on a different tab. Just switch tabs.
                                // (Home-tap-while-drilled is unreachable here because the
                                // nav-bar is hidden in that state — see the early return
                                // above.)
                                val reselectingActiveFeed =
                                    tab == NavTab.Feed && tab == selectedTab
                                if (reselectingActiveFeed) homeTapTrigger = System.nanoTime()
                                selectedTab = tab
                            },
                        )
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    TabContentSwitcher(
                        selectedTab = selectedTab,
                        tabStateHolder = tabStateHolder,
                        graph = graph,
                        padding = padding,
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

                    // Navigation 3 detail stack, rendered ABOVE the always-mounted tab content.
                    // The [HomeKey] root renders nothing (transparent) so the feed beneath shows
                    // through at the root and during a predictive-back peek; detail keys render
                    // their full-screen screens over it. Entry decorators give each entry its own
                    // saveable-state scope and its own ViewModelStore (cleared on pop) — the
                    // canonical replacement for the old per-entryId SaveableStateProvider +
                    // custom ViewModelStoreOwner, and the structural fix for the per-chatId VM leak.
                    NavDisplay(
                        backStack = backStack,
                        onBack = { popNav() },
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        transitionSpec = {
                            (slideInHorizontally(navSpatial) { it } + fadeIn(navEffects)) togetherWith
                                (slideOutHorizontally(navSpatial) { -it / 5 } + fadeOut(navEffects))
                        },
                        popTransitionSpec = {
                            (slideInHorizontally(navSpatial) { -it / 5 } + fadeIn(navEffects)) togetherWith
                                (slideOutHorizontally(navSpatial) { it } + fadeOut(navEffects))
                        },
                        predictivePopTransitionSpec = {
                            (slideInHorizontally(navSpatial) { -it / 5 } + fadeIn(navEffects)) togetherWith
                                (slideOutHorizontally(navSpatial) { it } + fadeOut(navEffects))
                        },
                        entryProvider = entryProvider {
                            entry<HomeKey> { Box(modifier = Modifier.fillMaxSize()) {} }
                            entry<ChannelKey> { key ->
                                RenderNavKey(
                                    key, graph, padding, feedOrder, scope, popNav, pushChannel,
                                    pushComments, pushCommentsHero, safelyOpenChannel, openReport,
                                    onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<CommentsKey> { key ->
                                RenderNavKey(
                                    key, graph, padding, feedOrder, scope, popNav, pushChannel,
                                    pushComments, pushCommentsHero, safelyOpenChannel, openReport,
                                    onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<ArchiveKey> { key ->
                                RenderNavKey(
                                    key, graph, padding, feedOrder, scope, popNav, pushChannel,
                                    pushComments, pushCommentsHero, safelyOpenChannel, openReport,
                                    onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                            entry<ArchiveSettingsKey> { key ->
                                RenderNavKey(
                                    key, graph, padding, feedOrder, scope, popNav, pushChannel,
                                    pushComments, pushCommentsHero, safelyOpenChannel, openReport,
                                    onPostReportClick, canReportPost, onLinkNotFound,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

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
