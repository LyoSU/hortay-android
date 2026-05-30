package dev.lyo.hortay.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.nav.ArchiveKey
import dev.lyo.hortay.data.nav.ArchiveSettingsKey
import dev.lyo.hortay.data.nav.ChannelKey
import dev.lyo.hortay.data.nav.CommentsKey
import dev.lyo.hortay.data.nav.HomeKey
import dev.lyo.hortay.data.nav.WebChannelKey
import dev.lyo.hortay.data.web.WebPostAdapter
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
 * Navigation is the SAME Navigation 3 model MainScaffold uses (migration stage 5):
 * a single [NavDisplay] over the shared [AppGraph.backStack], with [HomeKey] rendering
 * the tab scaffold beneath and detail keys pushing on top. Guest mode only ever pushes
 * [WebChannelKey] (channel drill) and [CommentsKey] (post detail, disabled-comments hero);
 * the auth-mode keys are owned by MainScaffold. Because the back stack is shared and the
 * two scaffolds never compose simultaneously ([dev.lyo.hortay.MainActivity] routes between
 * them), the entryProvider here also carries self-healing no-op entries for the auth-mode
 * keys — a foreign key can briefly survive a guest→auth→guest mode flip on the shared stack,
 * and NavDisplay requires an entry for every key it renders.
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

    // Navigation 3 back stack on the graph — the SAME instance MainScaffold drives. Root is
    // always [HomeKey] (the tab scaffold renders beneath the NavDisplay); guest detail keys
    // push on top, so `size > 1` ⇔ a drill overlay is showing. See [AppGraph.backStack] and
    // the file KDoc for why the stack is shared across both scaffolds.
    val backStack = graph.backStack
    val hasOverlay = backStack.size > 1

    // The active tab is NOT touched on push — under the nav-overlay the
    // user's originating tab keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the overlay.
    fun pushWebChannel(name: String) {
        backStack.add(WebChannelKey(username = name.lowercase()))
    }

    // Pop a detail entry. Guarded so the [HomeKey] root is never removed (NavDisplay always
    // needs a root; an empty stack would crash it).
    fun popNav() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    val locale = remember { Locale.getDefault().language.lowercase() }
    val signInRequiredMsg = stringResource(R.string.web_deeplink_signin_required)
    val signInActionLabel = stringResource(R.string.action_sign_in)
    // Snackbar host lifted into the scaffold so deep-link rejection messages
    // ("sign in to open private channels") land regardless of which tab the
    // user is currently looking at. The bus-driven relay (shared with
    // MainScaffold) collects [graph.userMessages] — repositories post once and
    // the active scaffold renders it. Direct showSnackbar calls below (deep-link
    // info pings) still work against the same host state.
    val snackbarHostState = remember { SnackbarHostState() }
    dev.lyo.hortay.ui.main.UserMessageSnackbarRelay(graph = graph, hostState = snackbarHostState)

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
                        // Auth-only surfaces: nudge sign-in with a tappable action that
                        // exits guest mode (MainActivity routes false → AuthScreen).
                        graph.userMessages.post(
                            text = signInRequiredMsg,
                            severity = dev.lyo.hortay.data.UserMessageBus.Severity.Info,
                            action = dev.lyo.hortay.data.UserMessageBus.Action.SignIn(signInActionLabel),
                        )
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

    // Back at the tab root (no drill on top) but not on Feed → return to Feed.
    // When a detail overlay is up, NavDisplay consumes back first (its predictive
    // back animates the leaving entry and reveals the tab scaffold below).
    BackHandler(enabled = !hasOverlay && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // See MainScaffold.kt for the holder-vs-PersistentMap rationale — guest
    // mode applies the same diff-apply pattern over its own cursor flow.
    val cursorHolder =
        dev.lyo.hortay.ui.timeline.rememberCursorHolder(graph.webFeedSource.chatReadCursors)
    val feedOrder by graph.settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst,
    )
    val snapScroll by graph.settingsStore.snapScroll.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val inlineVideoAutoplay by graph.settingsStore.inlineVideoAutoplay.collectAsStateWithLifecycle(
        initialValue = true,
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

    // Reverse-lookup chatId → username. WebPostAdapter.stableChatId is a stable
    // hash of the lowercased username; reading the live channels StateFlow at
    // tap time picks up newly-subscribed channels without needing a recomposition.
    // O(N) per tap is fine — N caps at the user's subscription set (≤200 in
    // practice), and the lambda only runs on a deliberate channel-name tap.
    val resolveUsername: (Long) -> String? = { chatId ->
        graph.webFeedSource.channels.value.firstOrNull {
            WebPostAdapter.stableChatId(it.info.username) == chatId
        }?.info?.username
    }
    // Post-tap in guest mode opens the same post-detail surface TDLib mode
    // uses — [CommentsScreen] with the frozen anchor pinned at the top — but
    // with an empty-state hero in place of the thread body explaining why
    // replies aren't reachable here. Reuses the auth-mode [CommentsKey] entry:
    // same nav-stack mechanics (predictive back, saveable state), same screen,
    // just a [CommentsDisabledOverride] supplied below so the screen
    // short-circuits its repository wiring. Previous behaviour was a bare
    // snackbar with the same copy — kept the user from getting to the post
    // detail at all.
    val commentsDisabledTitle = stringResource(R.string.web_comments_unavailable_title)
    val commentsDisabledBody = stringResource(R.string.web_comments_unavailable)
    val commentsDisabledAction = stringResource(R.string.action_sign_in)
    val webCommentsOverride = remember(commentsDisabledTitle, commentsDisabledBody, commentsDisabledAction) {
        dev.lyo.hortay.ui.comments.CommentsDisabledOverride(
            symbol = "chat_bubble",
            title = commentsDisabledTitle,
            body = commentsDisabledBody,
            actionLabel = commentsDisabledAction,
            // Flipping guestMode → false triggers MainActivity to re-route to
            // AuthScreen. Same path as the deep-link snackbar SignIn action,
            // just surfaced inline in the comments-empty hero where the user
            // actually hit the wall.
            onAction = { scope.launch { graph.guestMode.setGuest(false) } },
        )
    }
    val onGuestPostClick: (TimelinePost) -> Unit = remember(graph) {
        { post -> backStack.add(CommentsKey(anchor = post)) }
    }
    // Feed → channel-name tap routes through the same WebChannelScreen overlay
    // that the Channels tab uses. resolveUsername returns null for channels not
    // in our subscriptions (e.g. a forwarded-from chip pointing at a stranger's
    // channel) — fall back to opening t.me/<u> in the system browser so the tap
    // is never silently dead.
    val onFeedChannelOpen: (Long, Long?) -> Unit = remember(snackbarHostState) {
        { chatId, _ ->
            val u = resolveUsername(chatId)
            if (u != null) pushWebChannel(u)
            else systemUriHandler.openUri("https://t.me/")
        }
    }

    // Bottom inset handed to the WebChannelScreen drill (not a self-contained Scaffold for the
    // bottom edge). The tab scaffold under HomeKey owns the FloatingNavBar/FAB insets itself;
    // a detail only needs to clear the system navigation bar. CommentsScreen is a full Scaffold
    // and ignores this.
    val detailContentPadding = WindowInsets.navigationBars.asPaddingValues()

    LinkAwareScaffold(graph) {
    CompositionLocalProvider(
        LocalReadCursors provides cursorHolder,
        dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay provides inlineVideoAutoplay,
        dev.lyo.hortay.ui.main.LocalUserMessageBus provides graph.userMessages,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { scaffoldPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
                // Navigation 3 owns every scene (same model as MainScaffold). HomeKey renders the
                // tab scaffold (feed + bottom nav bar + add-channel FAB); detail keys render
                // full-screen over it, so a predictive-back swipe parallaxes BOTH the leaving
                // detail and the entering tab content. Entry decorators give each entry its own
                // saveable state + ViewModelStore (cleared on pop).
                NavDisplay(
                    backStack = backStack,
                    onBack = { popNav() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    // Horizontal shared-axis identical to MainScaffold: all three specs BARE (no
                    // explicit animationSpec) — the canonical Nav3 config that keeps the predictive
                    // seek smooth and finger-tracked. See MainScaffold for why a spring spec here
                    // jerks the predictive drag.
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
                                            val reselectingActiveFeed =
                                                tab == NavTab.Feed && tab == selectedTab
                                            if (reselectingActiveFeed) homeTapTrigger = System.nanoTime()
                                            selectedTab = tab
                                        },
                                    )
                                },
                                floatingActionButton = {
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
                                        ExtendedFloatingActionButton(
                                            onClick = {
                                                // Discrete "open add-channel sheet" action — ContextClick
                                                // before the sheet opens.
                                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                                addSheetOpen = true
                                            },
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
                                },
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            ) { homePadding ->
                                // Tab swap = pure crossfade (no spatial component) — destination
                                // switch, not depth. fastEffectsSpec is M3E's correct channel for
                                // non-spatial state changes. Captured here for the non-composable
                                // transitionSpec lambda.
                                val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

                                // SaveableStateHolder for tab-level state preservation. Each tab gets
                                // its own independent saveable scope via SaveableStateProvider(tab.name),
                                // so rememberSaveable / rememberLazyListState / rememberScrollState
                                // inside each tab survive AnimatedContent's mount/unmount lifecycle.
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
                                                // TimelineScreen stays mounted in the Feed tab; the
                                                // WebChannelScreen drill renders as a NavDisplay scene
                                                // over this whole tab scaffold. Keeping the feed mounted
                                                // preserves scroll position across channel drills without
                                                // the SaveableStateProvider serialise/restore cycle that
                                                // would otherwise mis-anchor the user when the underlying
                                                // post list mutates while they're away.
                                                tabStateHolder.SaveableStateProvider(key = "web-feed:__all__") {
                                                    TimelineScreen(
                                                        feed = graph.webFeedSource,
                                                        bookmarks = graph.bookmarkStore,
                                                        contentPadding = homePadding,
                                                        showOnlyBookmarked = false,
                                                        onChannelOpen = onFeedChannelOpen,
                                                        onOpenComments = onGuestPostClick,
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
                                                        // Reserve room for the "Add channel" FAB that this
                                                        // scaffold parks at BottomEnd. Without this the
                                                        // floating "↓ N" unread pill (also BottomEnd, owned
                                                        // by TimelineScreen) lands directly under the FAB
                                                        // and is un-tappable. ExtendedFAB ~56.dp + 16.dp
                                                        // gap → 72.dp. Only the Feed tab needs this; the
                                                        // Saved-tab call below leaves the default 0.dp
                                                        // because the FAB is hidden there.
                                                        unreadPillExtraBottomPadding = 72.dp,
                                                    )
                                                }
                                            }

                                            NavTab.Channels -> WebChannelsScreen(
                                                graph = graph,
                                                contentPadding = homePadding,
                                                onChannelClick = { username ->
                                                    pushWebChannel(username)
                                                },
                                                onAddChannel = { addSheetOpen = true },
                                                onAddCurated = { username ->
                                                    deepLinkPrefill = username
                                                    addSheetOpen = true
                                                },
                                            )

                                            NavTab.Saved -> TimelineScreen(
                                                feed = graph.webFeedSource,
                                                bookmarks = graph.bookmarkStore,
                                                contentPadding = homePadding,
                                                showOnlyBookmarked = true,
                                                onChannelOpen = onFeedChannelOpen,
                                                onOpenComments = onGuestPostClick,
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
                                                contentPadding = homePadding,
                                                onLogout = null,
                                                onSignIn = { scope.launch { graph.guestMode.setGuest(false) } },
                                                // Combined wipe-and-refetch so the user sees fresh
                                                // content immediately, not an empty feed waiting for
                                                // the next tier-2 sweep. Subscriptions survive.
                                                onClearWebCache = { graph.webFeedSource.clearCacheAndRefresh() },
                                                ignoredChannels = graph.ignoredChannels,
                                                // Guest-mode resolver: walk the in-memory channels
                                                // list for a row whose username hashes to the given
                                                // chatId. Cheap — typical subscription set is < 200,
                                                // resolution happens once per hidden chatId on screen
                                                // entry, and the StateFlow is already a snapshot the
                                                // composable holds.
                                                webChannelByChatId = { chatId ->
                                                    graph.webFeedSource.channels.value
                                                        .firstOrNull {
                                                            dev.lyo.hortay.data.web.WebPostAdapter.stableChatId(
                                                                it.info.username,
                                                            ) == chatId
                                                        }
                                                        ?.let {
                                                            dev.lyo.hortay.ui.settings.WebChannelDescriptor(
                                                                title = it.info.title,
                                                                username = it.info.username,
                                                            )
                                                        }
                                                },
                                                userMessages = graph.userMessages,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        entry<WebChannelKey> { key ->
                            WebChannelScreen(
                                username = key.username,
                                graph = graph,
                                contentPadding = detailContentPadding,
                                onBack = { popNav() },
                                onPostClick = onGuestPostClick,
                                feedOrder = feedOrder,
                            )
                        }

                        entry<CommentsKey> { key ->
                            dev.lyo.hortay.ui.comments.CommentsScreen(
                                post = key.anchor,
                                // Guest mode has no TDLib session → no CommentsRepository, no
                                // PostsRepository.posts to live-sync the anchor against. The screen
                                // renders the frozen CommentsKey snapshot and shows the
                                // [webCommentsOverride] empty-state hero in place of the thread body.
                                repo = null,
                                feedRepo = null,
                                onDismiss = { popNav() },
                                disabledOverride = webCommentsOverride,
                            )
                        }

                        // Defensive self-healing entries for the auth-mode keys. They are owned by
                        // MainScaffold and never pushed here, but the back stack is shared (see
                        // [AppGraph.backStack] + file KDoc) so a foreign key can briefly survive an
                        // auth→guest mode flip on the first frame after MainActivity re-routes. NavDisplay
                        // requires an entry for every key on the stack; these render nothing and pop
                        // themselves, healing the stack to a valid guest state (the Nav3 equivalent of the
                        // old guest renderer's defensive `is`-skip).
                        entry<ChannelKey> { LaunchedEffect(Unit) { popNav() } }
                        entry<ArchiveKey> { LaunchedEffect(Unit) { popNav() } }
                        entry<ArchiveSettingsKey> { LaunchedEffect(Unit) { popNav() } }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
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
