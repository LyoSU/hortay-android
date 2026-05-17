@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
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
import kotlin.coroutines.cancellation.CancellationException
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.NavEntry
import dev.lyo.hortay.data.PublicHandleResult
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.report.ReportTarget
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.users.LocalUserProfileOpener
import dev.lyo.hortay.ui.users.UserProfileOpener
import kotlinx.coroutines.launch

/**
 * Predictive-back progress contract shared with [dev.lyo.hortay.ui.comments.CommentsScreen]'s
 * graphicsLayer:
 *  - 0f .. 1f = gesture peek (translate ~10%, scale to 0.9, alpha to 0.7)
 *  - 1f .. EXIT_PROGRESS = commit exit (translate to full width, scale to 0.85, alpha to 0)
 * Going past 1f on commit keeps the overlay visually "leaving" instead of freezing at peek.
 */
private const val EXIT_PROGRESS = 2f

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
    val stack by graph.nav.stack.collectAsStateWithLifecycle()
    val topEntry = stack.lastOrNull()

    // Nav helpers route through [AppGraph.nav]. The active tab is NOT
    // touched on push — under the nav-overlay the user's originating tab
    // (Channels, Saved, …) keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the top
    // overlay layer; tab restoration is automatic because we never moved
    // away from it.
    val pushChannel: (Long, Long?) -> Unit = { chatId, scrollTo ->
        graph.nav.push(NavEntry.Channel(chatId = chatId, scrollToMessageId = scrollTo))
    }
    val pushComments: (TimelinePost) -> Unit = { post ->
        graph.nav.push(NavEntry.Comments(anchor = post))
    }
    val popNav: () -> Unit = { graph.nav.pop() }

    // Monotonic counter: each re-tap on Home (or brand) bumps it once. The Feed observes the
    // value and decides scroll-to-top vs refresh based on its own scroll position.
    var homeTapTrigger by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val connection by graph.tdClient.connection.collectAsStateWithLifecycle()
    val floodWaitUntilMs by graph.tdClient.floodWaitUntilMs.collectAsStateWithLifecycle()

    // Single SnackbarHost owned by the scaffold so transient errors land on whichever
    // tab the user is currently looking at. Subscribing to the bus only while composed
    // means messages buffered during foreground transitions get delivered as soon as
    // we resume; a flooded bus drops oldest (see [UserMessageBus]) so we never queue
    // a stale apology that no longer reflects the current state.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        graph.userMessages.messages.collect { msg ->
            snackbarHostState.showSnackbar(message = msg.text)
        }
    }

    val res = LocalContext.current.resources

    /**
     * Gated channel-open for in-app gestures (forward-source chip, cross-channel
     * quote-tap, post-channel-name tap when it differs from the host). Mirrors the
     * type-gate the deep-link dispatcher runs against [dev.lyo.hortay.data.DeepLink].
     * Non-channel targets (supergroup-chat, basic group, 1:1 user / bot) used to
     * slip through these gestures, push a [NavEntry.Channel] onto the nav stack,
     * and land the user on a ChannelScreen whose loadChannelHistory short-circuits
     * on `!chat.isChannel()` → empty hero. Hortay's product scope is broadcast
     * channels only, so the right answer for non-channel sources is a kind-keyed
     * snackbar — same as the deep-link path.
     */
    val safelyOpenChannel: (Long, Long?) -> Unit = { chatId, scrollTo ->
        scope.launch {
            when (val resolved = graph.postsRepository.resolveChatKind(chatId)) {
                is PublicHandleResult.Channel -> pushChannel(resolved.chatId, scrollTo)
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

    /**
     * Same kind-gate as [safelyOpenChannel], but for "go to original" affordances
     * on overlay surfaces (pinned-anchor reply card / channel chip / author-chat
     * header in CommentsScreen): on success, replaces the current top entry
     * in-place via [dev.lyo.hortay.data.NavStack.replaceTop] instead of stacking
     * a new entry on top of it.
     *
     * Why replace, not stack:
     *   - Product idiom — the reply card / channel chip in a post-detail view is
     *     a NAVIGATION ("go to the original"), not a stacked drill.
     *   - Layout — [MainScaffold] only mounts `stack.takeLast(2)`, so stacking a
     *     third layer would unmount the originating channel underneath. Backing
     *     out of the destination would then re-mount it (fresh ViewModelStore,
     *     fresh OpenChat refcount swing, scroll-position re-derivation).
     *
     * Atomicity: [resolveChatKind] is cheap (chatCache hit) for the common case;
     * [replaceTop] is a SINGLE [_stack] write — Compose subscribers see exactly
     * one recomposition. The Channel entry at index 0 keeps its identity across
     * the transition (same `entryId`).
     */
    val safelyReplaceTopWithChannel: (Long, Long?) -> Unit = { chatId, scrollTo ->
        scope.launch {
            when (val resolved = graph.postsRepository.resolveChatKind(chatId)) {
                is PublicHandleResult.Channel ->
                    graph.nav.replaceTop(
                        NavEntry.Channel(
                            chatId = resolved.chatId,
                            scrollToMessageId = scrollTo,
                        ),
                    )
                is PublicHandleResult.Unsupported -> {
                    graph.nav.pop()
                    graph.userMessages.post(
                        res.getString(unsupportedHandleMessageId(resolved.kind)),
                        UserMessageBus.Severity.Info,
                    )
                }
                is PublicHandleResult.NotFound -> {
                    graph.nav.pop()
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
    )

    // Pending report: (chatId, messageId, token). Hoisted off local state onto
    // [AppGraph.reportDialogs] so a rotation mid-flow doesn't drop the sheet on
    // the floor — `ReportFlowViewModel` keeps partial answers across TDLib
    // roundtrips, and a re-created composition starting with null target would
    // erase the user's progress visibly.
    val openReport: (Long, Long?) -> Unit = { chatId, messageId ->
        graph.reportDialogs.open(ReportTarget(chatId, messageId, System.nanoTime()))
    }

    // User-profile sheet pendant. Local state — unlike the report flow, no TDLib write
    // is staged in here, so a rotation just re-fetches the profile (cheap, three cached
    // local reads in the steady state). [UserProfileOpener] is a `fun interface` so
    // re-providing the local on every recomposition still preserves equality identity
    // for skippable propagation under the provider.
    var pendingUserId by remember { mutableStateOf<Long?>(null) }
    val userProfileOpener = remember {
        UserProfileOpener { userId -> pendingUserId = userId }
    }

    // Single predictive-back handler for the top nav-entry. Translates,
    // scales, fades the visible top layer under the user's finger; edge
    // (LEFT vs RIGHT) is forwarded because users can bind back to either
    // edge — a hard-coded translateX direction would invert the motion on
    // right-handed setups. Motion springs captured here in @Composable scope
    // (`progress.collect` is a plain coroutine; `MaterialTheme` reads require
    // composable context) — same M3E `fastEffectsSpec` the tab AnimatedContent
    // rides, so the predictive-back commit / rewind shares physics with the
    // rest of the chrome instead of riding a one-off duration-tween.
    val backCommitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val backRewindSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val navBackProgress = remember { Animatable(0f) }
    var navBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = topEntry != null) { progress ->
        try {
            progress.collect { event ->
                navBackEdge = event.swipeEdge
                // System emits at pointer-move rate (60–120 Hz). Epsilon-skip
                // sub-pixel deltas so we don't pay an Animatable snapshot write
                // (and the resulting graphicsLayer re-evaluation in the top
                // entry's content) for invisible motion. 0.005f ≈ half a pixel
                // on a 1080px-wide screen at translation 0.1 — below perceptual
                // threshold.
                val next = event.progress
                if (kotlin.math.abs(next - navBackProgress.value) >= 0.005f) {
                    navBackProgress.snapTo(next)
                }
            }
            // Commit: extend past peek (1f) to full exit (2f) so the top
            // layer continues translating off-screen and fades to zero alpha
            // before we drop it from composition. Without this leg the
            // overlay would freeze at peek (~70% visible) for the duration
            // of the commit animation and then snap away — janky on flagship.
            navBackProgress.animateTo(EXIT_PROGRESS, backCommitSpec)
            popNav()
            navBackProgress.snapTo(0f)
        } catch (_: CancellationException) {
            navBackProgress.animateTo(0f, backRewindSpec)
        }
    }
    // Stack empty + not on Feed: return to Feed tab. Plain BackHandler — no
    // overlay to animate at this point.
    BackHandler(enabled = topEntry == null && selectedTab != NavTab.Feed) {
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
    val navStateHolder = rememberSaveableStateHolder()

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
                    val navBarVisible = topEntry == null
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
                        coveredByOverlay = stack.isNotEmpty(),
                        scope = scope,
                        onHomeTapTriggerBump = { homeTapTrigger = System.nanoTime() },
                        onSafelyOpenChannel = safelyOpenChannel,
                        onPushChannel = pushChannel,
                        onPushComments = pushComments,
                        onPostReportClick = onPostReportClick,
                        canReportPost = canReportPost,
                        tdlibMarkAsRead = tdlibMarkAsRead,
                    )

                    NavOverlayRenderer(
                        visibleEntries = stack.takeLast(2),
                        navStateHolder = navStateHolder,
                        navBackProgress = navBackProgress.value,
                        navBackEdge = navBackEdge,
                        graph = graph,
                        padding = padding,
                        feedOrder = feedOrder,
                        scope = scope,
                        onPopNav = popNav,
                        onPushChannel = pushChannel,
                        onPushComments = pushComments,
                        onSafelyOpenChannel = safelyOpenChannel,
                        onSafelyReplaceTopWithChannel = safelyReplaceTopWithChannel,
                        onOpenReport = openReport,
                        onPostReportClick = onPostReportClick,
                        canReportPost = canReportPost,
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
