package dev.lyo.hortay.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.NavEntry
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.FloatingNavBar
import dev.lyo.hortay.ui.main.LinkAwareScaffold
import dev.lyo.hortay.ui.main.NavTab
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.report.GuestReportDelegator
import dev.lyo.hortay.ui.report.ReportInstructionDialog
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.timeline.TimelineScreen
import androidx.compose.runtime.CompositionLocalProvider
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Top-level container for guest (anonymous) reading mode. Uses the SAME
 * Composables as [dev.lyo.hortay.ui.main.MainScaffold]:
 *   - [FloatingNavBar] / [NavTab] — same bottom nav, same four tabs
 *   - [TimelineScreen] — same feed renderer, driven by [WebFeedSource] via the
 *     shared [dev.lyo.hortay.data.FeedSource] interface; TDLib-only services
 *     (commentsRepo, folders, translations, channelActions, tdlibRepo) are
 *     passed null so the screen hides those affordances cleanly.
 *   - [SettingsScreen] — same screen, same SectionLabel / SettingsRow primitives;
 *     guest-mode parameters (onSignIn, onClearWebCache) flip the rendered set
 *     of sections without forking the screen.
 *
 * Web-specific UI files remaining: this scaffold (mode router) and
 * [WebChannelsScreen] / [AddChannelSheet] (channel-list + smart-paste flow
 * tied to the web subscription store; nothing equivalent exists in TDLib mode).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebModeScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // [addSheetOpen] + [deepLinkPrefill] are `rememberSaveable` so a user
    // mid-input in [AddChannelSheet] (typing a handle, reviewing a pasted URL)
    // doesn't lose the sheet and their typed text on rotation — the sheet's
    // own field state is saveable, but the parent's open/prefill flags need
    // to survive the same configuration change for the sheet to stay mounted.
    // Boolean and String? both serialise via the default Saver.
    //
    // Process-kill semantics are unchanged: rotation now preserves both flags,
    // and on a cold-launch after process death the deep-link router's
    // UNLIMITED channel on the graph re-delivers unconsumed events through the
    // collector path, which sets the pair again.
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var deepLinkPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    // guest-mode report instruction: set to the post's senderHandle after delegation fires.
    var showReportInstruction by remember { mutableStateOf(false) }
    // Monotonic counter incremented on each "Home" re-tap — TimelineScreen
    // observes it and scrolls the feed to top (or refreshes when already at
    // top). Same mechanism as MainScaffold so the home-tap-to-scroll gesture
    // works identically in both modes.
    var homeTapTrigger by rememberSaveable { mutableStateOf(0L) }

    // Channel back-stack — guest-mode counterpart to MainScaffold's TDLib stack.
    // Single polymorphic nav-stack on [AppGraph.nav]. Guest mode only ever
    // pushes [NavEntry.WebChannel]; the auth-mode variants (Channel, Comments)
    // are owned by [dev.lyo.hortay.ui.main.MainScaffold] and never appear here
    // because the two scaffolds never compose simultaneously
    // ([dev.lyo.hortay.MainActivity] routes auth.Ready → MainScaffold,
    // isGuest → WebModeScaffold).
    //
    // Same back-stack mechanics as MainScaffold — see [NavStack] KDoc.
    val stack by graph.nav.stack.collectAsStateWithLifecycle()
    val topEntry = stack.lastOrNull()

    // The active tab is NOT touched on push — under the nav-overlay the
    // user's originating tab keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the overlay.
    fun pushWebChannel(name: String) {
        graph.nav.push(NavEntry.WebChannel(username = name.lowercase()))
    }

    fun popNav() {
        graph.nav.pop()
    }

    fun clearNav() {
        graph.nav.clear()
    }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val locale = remember { Locale.getDefault().language.lowercase() }
    val signInRequiredMsg = stringResource(R.string.web_deeplink_signin_required)
    // Snackbar host lifted into the scaffold so deep-link rejection messages
    // ("sign in to open private channels") land regardless of which tab the
    // user is currently looking at. Same pattern as MainScaffold's userMessages
    // bus — TDLib mode's UserMessageBus has no analogue in guest mode, so this
    // is the lightest surface that satisfies the few cases we need.
    val snackbarHostState = remember { SnackbarHostState() }

    // Deep-link dispatcher. Mirrors MainScaffold's collector but speaks the
    // guest-mode dialect: only [DeepLink.PublicChannel] is actionable here
    // (we open AddChannelSheet pre-filled with the handle so the user can
    // confirm before subscribing — never auto-subscribing, which would join
    // arbitrary channels under the user's nose). Private and per-message
    // links require TDLib auth; we surface a snackbar nudging sign-in
    // instead of silently dropping them, which would feel broken when the
    // user clearly tapped a Telegram link.
    val systemUriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) {
        graph.deepLinkRouter.events.collect { link ->
            // Per-link runCatching so a snackbar suspend cancellation or an unexpected
            // throw in one handler doesn't permanently silence the collector for the
            // rest of the process — matches the failure isolation MainScaffold uses.
            try {
                when (link) {
                    is DeepLink.PublicChannel -> {
                        deepLinkPrefill = link.handle
                        addSheetOpen = true
                    }
                    is DeepLink.PrivateChannel,
                    is DeepLink.Message,
                    is DeepLink.ChatInvite -> {
                        // Auth-only surfaces: nudge sign-in instead of silently dropping.
                        snackbarHostState.showSnackbar(signInRequiredMsg)
                    }
                    is DeepLink.External -> {
                        runCatching { systemUriHandler.openUri(link.originalUrl) }
                    }
                    is DeepLink.HashtagSearch -> {
                        // Mirrors MainScaffold: scoped snackbar when a channel scope
                        // was inferred (from `#tag@channel` text-entity suffix or
                        // PostBody's scoped LocalHashtagTap), generic otherwise.
                        val msg = if (link.channelHandle != null) {
                            context.resources.getString(
                                R.string.link_hashtag_search_in_channel,
                                link.tag,
                                "@${link.channelHandle}",
                            )
                        } else {
                            context.resources.getString(R.string.link_hashtag_search, link.tag)
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                android.util.Log.w("WebModeScaffold", "deep-link dispatch failed for $link", t)
            }
        }
    }

    // Predictive back for the top nav-entry. Mirrors MainScaffold's
    // navBackProgress — same M3E fastEffectsSpec, same epsilon-skip,
    // same EXIT_PROGRESS extension on commit.
    val backCommitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val backRewindSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val navBackProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    var navBackEdge by remember { mutableIntStateOf(androidx.activity.BackEventCompat.EDGE_LEFT) }
    androidx.activity.compose.PredictiveBackHandler(enabled = topEntry != null) { progress ->
        try {
            progress.collect { event ->
                navBackEdge = event.swipeEdge
                val next = event.progress
                if (kotlin.math.abs(next - navBackProgress.value) >= 0.005f) {
                    navBackProgress.snapTo(next)
                }
            }
            navBackProgress.animateTo(2f, backCommitSpec)
            popNav()
            navBackProgress.snapTo(0f)
        } catch (_: kotlinx.coroutines.CancellationException) {
            navBackProgress.animateTo(0f, backRewindSpec)
        }
    }
    BackHandler(enabled = topEntry == null && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    val readCursors by graph.webFeedSource.chatReadCursors.collectAsStateWithLifecycle()
    val feedOrder by graph.settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = dev.lyo.hortay.data.FeedOrder.Newest,
    )
    val snapScroll by graph.settingsStore.snapScroll.collectAsStateWithLifecycle(
        initialValue = false,
    )
    // Guest-mode dwell-ack wrapper. Groups the viewport batch by channel
    // (recovered from `senderHandle` since web posts have no real chatId) and
    // advances each channel's local cursor to the highest seq in the batch.
    // Result mirrors TDLib's "lastReadInboxMessageId moved up to message X":
    // the channel_read_cursor row gets MAX-clamped to the freshest seen post.
    //
    // `remember`-wrapped on graph.webRepository so the lambda instance is stable
    // across WebModeScaffold recompositions. TimelineScreen uses this as a key for
    // `interactions = remember(...)` / `ackedRead = remember(markAsRead)`; a fresh
    // closure per recompose would invalidate those blocks and trigger redundant
    // markChannelRead writes on every dwell-batch evaluation.
    val webMarkAsRead: suspend (List<TimelinePost>) -> Unit = remember(graph.webRepository) {
        { batch ->
            batch.groupBy { it.senderHandle?.removePrefix("@")?.lowercase() ?: "" }
                .forEach { (username, group) ->
                    if (username.isNotEmpty()) {
                        graph.webRepository.markChannelRead(username, group.maxOf { it.id })
                    }
                }
        }
    }
    LinkAwareScaffold(graph) {
    CompositionLocalProvider(LocalReadCursors provides readCursors) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        bottomBar = {
            // Hide nav-bar inside a drill (same rationale as MainScaffold).
            // Animated through M3E motion so the surrounding content padding
            // eases instead of snapping when the overlay pushes / pops.
            androidx.compose.animation.AnimatedVisibility(
                visible = topEntry == null,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
            ) {
                FloatingNavBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        val reselectingActiveFeed =
                            tab == NavTab.Feed && tab == selectedTab
                        if (reselectingActiveFeed) homeTapTrigger = System.nanoTime()
                        selectedTab = tab
                    },
                )
            }
        },
        floatingActionButton = {
            // Hide the FAB inside a drill (the overlay owns the surface).
            // Symmetric M3E fade so it doesn't pop in/out abruptly.
            androidx.compose.animation.AnimatedVisibility(
                visible = topEntry == null,
                enter = androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ) + androidx.compose.animation.scaleIn(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
                exit = androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ) + androidx.compose.animation.scaleOut(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
            ) {
            // Primary "add channel" action. Surfaced on tabs where adding a
            // channel is contextually meaningful — Feed (where the user reads)
            // and Channels (where the list of subscriptions lives). Settings
            // and Saved hide it: clicking it there would feel context-mismatched.
            // ExtendedFab (with text label) on the empty-channels case so the
            // first-time user can't miss it; collapses to icon-only once posts
            // exist and the affordance becomes secondary.
            if (selectedTab == NavTab.Feed || selectedTab == NavTab.Channels) {
                // Subscribed via Lifecycle so the FAB collapses the moment the user
                // adds their first channel. Earlier `channels.value.any { … }` was a
                // raw StateFlow read inside composition — Compose never re-subscribed,
                // so the extended FAB stayed expanded with the "Add channel" label even
                // after subscriptions existed. derivedStateOf scopes recomposition to
                // the boolean: only an actual any/none flip propagates further.
                val channels by graph.webFeedSource.channels.collectAsStateWithLifecycle()
                val hasChannels = channels.any { it.isSubscribed }
                // No manual padding here — Scaffold positions the FAB above the
                // bottomBar automatically. An earlier 88dp bottom padding stacked
                // on top of Scaffold's own offset and floated the button much too
                // high above the FloatingNavBar.
                ExtendedFloatingActionButton(
                    onClick = { addSheetOpen = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    expanded = !hasChannels,
                    icon = {
                        Symbol(
                            name = "add",
                            contentDescription = stringResource(R.string.web_add_channel),
                            size = 24.dp,
                        )
                    },
                    text = { Text(stringResource(R.string.web_add_channel)) },
                )
            }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Tab swap = pure crossfade (no spatial component) — destination
            // switch, not depth. fastEffectsSpec is M3E's correct channel for
            // non-spatial state changes. Captured here for the non-composable
            // transitionSpec lambda.
            val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

            // SaveableStateHolder for tab-level state preservation. Each tab gets
            // its own independent saveable scope via SaveableStateProvider(tab.name),
            // so rememberSaveable / rememberLazyListState / rememberScrollState
            // inside each tab survive AnimatedContent's mount/unmount lifecycle.
            // The user's scroll position on the guest Feed, Channels, Saved and
            // Profile tabs is preserved across tab switches without any extra state
            // in each screen.
            val tabStateHolder = rememberSaveableStateHolder()

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec)
                },
                label = "web-tab-switch",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                tabStateHolder.SaveableStateProvider(key = tab.name) {
                when (tab) {
                    NavTab.Feed -> {
                        // Overlay pattern (mirror MainScaffold): TimelineScreen is
                        // ALWAYS mounted in the Feed tab; WebChannelScreen renders
                        // as a nav-stack overlay outside this tab branch (the top-2
                        // entries of [graph.nav.stack] drawn below this Box block).
                        // Keeping the feed mounted preserves scroll position across
                        // channel drills without the SaveableStateProvider serialise/
                        // restore cycle that would otherwise mis-anchor the user
                        // when the underlying post list mutates while they're away.
                        tabStateHolder.SaveableStateProvider(key = "web-feed:__all__") {
                            TimelineScreen(
                                feed = graph.webFeedSource,
                                bookmarks = graph.bookmarkStore,
                                contentPadding = padding,
                                showOnlyBookmarked = false,
                                onChannelOpen = { /* no per-channel drill from feed bodies in guest mode */ },
                                homeTapTrigger = homeTapTrigger,
                                onBrandTap = { homeTapTrigger = System.nanoTime() },
                                onSearchClick = { searchOpen = true },
                                topBarBadge = { GuestModeBadge() },
                                onReportClick = { post ->
                                    val outcome = graph.guestReportDelegator.report(
                                        channelUsername = post.senderHandle?.removePrefix("@"),
                                        postId = if (post.id != 0L) post.id else null,
                                    )
                                    if (outcome == GuestReportDelegator.Outcome.OpenedTelegram ||
                                        outcome == GuestReportDelegator.Outcome.OpenedWeb) {
                                        showReportInstruction = true
                                    }
                                },
                                canReport = { true },
                                markAsRead = webMarkAsRead,
                                feedOrder = feedOrder,
                                snapScroll = snapScroll,
                            )
                        }
                    }

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = { username ->
                            pushWebChannel(username)
                        },
                        onAddChannel = { addSheetOpen = true },
                    )

                    NavTab.Saved -> TimelineScreen(
                        feed = graph.webFeedSource,
                        bookmarks = graph.bookmarkStore,
                        contentPadding = padding,
                        showOnlyBookmarked = true,
                        onChannelOpen = { /* no-op: guest mode */ },
                        onReportClick = { post ->
                            val outcome = graph.guestReportDelegator.report(
                                channelUsername = post.senderHandle?.removePrefix("@"),
                                postId = if (post.id != 0L) post.id else null,
                            )
                            if (outcome == GuestReportDelegator.Outcome.OpenedTelegram ||
                                outcome == GuestReportDelegator.Outcome.OpenedWeb) {
                                showReportInstruction = true
                            }
                        },
                        canReport = { true },
                        markAsRead = webMarkAsRead,
                        feedOrder = feedOrder,
                        snapScroll = snapScroll,
                    )

                    NavTab.Profile -> SettingsScreen(
                        settings = graph.settingsStore,
                        stats = null,
                        contentPadding = padding,
                        onLogout = null,
                        onSignIn = { scope.launch { graph.guestMode.setGuest(false) } },
                        // Combined wipe-and-refetch so the user sees fresh
                        // content immediately, not an empty feed waiting for
                        // the next tier-2 sweep. Subscriptions survive.
                        onClearWebCache = { graph.webFeedSource.clearCacheAndRefresh() },
                    )
                }
                }
            }

            // Top-2 nav-stack entries rendered as stacked layers above the
            // always-mounted feed. Single forEach so each entry stays in a
            // stable composition position — after pop, the entry that was at
            // index 0 stays at index 0 (now `isTop = true`), preserving its
            // remember-group identity. Mirror of MainScaffold's overlay logic.
            val visibleEntries = stack.takeLast(2)
            val navStateHolder = rememberSaveableStateHolder()
            visibleEntries.forEachIndexed { idx, entry ->
                val isTop = idx == visibleEntries.lastIndex
                if (entry !is NavEntry.WebChannel) return@forEachIndexed
                key(entry.entryId) {
                    navStateHolder.SaveableStateProvider(key = entry.entryId) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isTop) {
                                        Modifier.graphicsLayer {
                                            val p = navBackProgress.value.coerceIn(0f, 2f)
                                            val signed = when (navBackEdge) {
                                                androidx.activity.BackEventCompat.EDGE_RIGHT -> -p
                                                else -> p
                                            }
                                            translationX = signed * size.width * 0.25f
                                            val s = 1f - p.coerceAtMost(1f) * 0.05f
                                            scaleX = s; scaleY = s
                                            alpha = (1f - p.coerceAtMost(1f) * 0.9f)
                                                .coerceAtLeast(0f)
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            WebChannelScreen(
                                username = entry.username,
                                graph = graph,
                                contentPadding = padding,
                                onBack = ::popNav,
                                feedOrder = feedOrder,
                            )
                        }
                    }
                }
            }
        }
    }

    if (addSheetOpen) {
        AddChannelSheet(
            feedSource = graph.webFeedSource,
            repository = graph.webRepository,
            client = graph.webClient,
            locale = locale,
            // One-shot: clear the prefill on dismiss so a manual reopen lands
            // back on the clipboard auto-paste path instead of looping the user
            // through the same deep-link target every time they tap "Add channel".
            onDismiss = {
                addSheetOpen = false
                deepLinkPrefill = null
            },
            onSignIn = { scope.launch { graph.guestMode.setGuest(false) } },
            prefilledUsername = deepLinkPrefill,
        )
    }

    // Cross-channel local search overlay. Lives at the scaffold level (not as
    // a tab) so it can grab the full screen, including the area normally
    // occupied by the FloatingNavBar — search-as-an-overlay is the canonical
    // Material 3 pattern, and pinning it under nav would make the keyboard
    // collide with results.
    if (searchOpen) {
        WebSearchScreen(
            repository = graph.webRepository,
            bookmarks = graph.bookmarkStore,
            onDismiss = { searchOpen = false },
        )
    }

    // Instruction dialog: shown after guest-mode delegation opens Telegram or a
    // web tab. Tells the user how to complete the report in the external surface.
    if (showReportInstruction) {
        ReportInstructionDialog(onDismiss = { showReportInstruction = false })
    }
    }
    }
}
