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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.FloatingNavBar
import dev.lyo.hortay.ui.main.LinkAwareScaffold
import dev.lyo.hortay.ui.main.NavTab
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.timeline.TimelineScreen
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
    // [addSheetOpen] + [deepLinkPrefill] are deliberately NOT `rememberSaveable`.
    // The pair is set together by the deep-link collector when a guest-mode user
    // taps `t.me/<handle>`. If the user dismissed the sheet pre-process-death we
    // wouldn't want to re-open it on cold-launch; if the user was mid-decision
    // when the process was killed they almost certainly abandoned the action.
    // The deep-link router buffers the inbound event itself (UNLIMITED channel
    // on the graph), so a re-entry that genuinely needs the sheet will re-arrive
    // through the collector path naturally.
    var addSheetOpen by remember { mutableStateOf(false) }
    var deepLinkPrefill by remember { mutableStateOf<String?>(null) }
    // Monotonic counter incremented on each "Home" re-tap — TimelineScreen
    // observes it and scrolls the feed to top (or refreshes when already at
    // top). Same mechanism as MainScaffold so the home-tap-to-scroll gesture
    // works identically in both modes.
    var homeTapTrigger by rememberSaveable { mutableStateOf(0L) }

    // Channel back-stack — guest-mode counterpart to MainScaffold's TDLib stack.
    // Entries are channel usernames (String) because guest mode identifies channels
    // by their t.me/s/ handle, not a TDLib chatId. Same push / pop / pop-to-existing
    // semantics as the TDLib stack so the back gesture feels identical across modes.
    // We don't track an entry tab (web mode users always enter channels from
    // Channels tab → Feed-tab routing; restoring to Channels-tab on pop just means
    // setting selectedTab back to Channels when the stack empties).
    var channelStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var channelEntryTab by rememberSaveable { mutableStateOf<NavTab?>(null) }
    fun enterWebChannel(name: String, fromTab: NavTab) {
        if (channelStack.isEmpty()) channelEntryTab = fromTab
        val lower = name.lowercase()
        val existing = channelStack.indexOf(lower)
        channelStack = if (existing >= 0) {
            channelStack.subList(0, existing + 1).toList()
        } else {
            channelStack + lower
        }
        selectedTab = NavTab.Feed
    }
    fun popWebChannel() {
        if (channelStack.isEmpty()) return
        channelStack = channelStack.dropLast(1)
        if (channelStack.isEmpty()) {
            selectedTab = channelEntryTab ?: NavTab.Feed
            channelEntryTab = null
        }
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
                            context.getString(
                                R.string.link_hashtag_search_in_channel,
                                link.tag,
                                "@${link.channelHandle}",
                            )
                        } else {
                            context.getString(R.string.link_hashtag_search, link.tag)
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

    // Back-priority chain mirrors MainScaffold's TDLib chain — channel-stack pops
    // first, tab swaps back to Feed second, system close last. The leaf-most
    // enabled BackHandler in the composition tree wins, so the channel stack
    // takes precedence whenever the user is drilled into a channel.
    BackHandler(enabled = channelStack.isNotEmpty()) { popWebChannel() }
    BackHandler(enabled = channelStack.isEmpty() && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    LinkAwareScaffold(graph) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab ->
                    // Same three-case dispatch as MainScaffold's TDLib counterpart —
                    // Home tap behaves differently depending on whether the user is
                    // inside a channel drill, on the all-feed already, or coming
                    // from another tab:
                    //   (a) Home + inside a channel → exit the channel only; do NOT
                    //       bump homeTapTrigger so the all-feed restores its prior
                    //       scroll position.
                    //   (b) Home + already on all-feed → bump homeTapTrigger for the
                    //       canonical "scroll to top / refresh" gesture.
                    //   (c) Any non-Home tab → just switch.
                    val tappingHomeWhileInChannel =
                        tab == NavTab.Feed && channelStack.isNotEmpty()
                    val reselectingActiveFeed =
                        tab == NavTab.Feed && tab == selectedTab && channelStack.isEmpty()
                    when {
                        tappingHomeWhileInChannel -> {
                            channelStack = emptyList()
                            channelEntryTab = null
                        }
                        reselectingActiveFeed -> homeTapTrigger = System.nanoTime()
                    }
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
                        // Routing parallel to MainScaffold: empty channel stack →
                        // all-feed TimelineScreen; non-empty → WebChannelScreen
                        // for the top username. Each entry gets its own nested
                        // SaveableStateProvider so per-channel scroll position is
                        // preserved while navigating in and out of the stack.
                        val currentChannel = channelStack.lastOrNull()
                        val saveableKey = currentChannel ?: "__all__"
                        tabStateHolder.SaveableStateProvider(key = "web-feed:$saveableKey") {
                            if (currentChannel == null) {
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
                                )
                            } else {
                                WebChannelScreen(
                                    username = currentChannel,
                                    graph = graph,
                                    contentPadding = padding,
                                    onBack = { popWebChannel() },
                                )
                            }
                        }
                    }

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = { username ->
                            enterWebChannel(username, fromTab = NavTab.Channels)
                        },
                        onAddChannel = { addSheetOpen = true },
                    )

                    NavTab.Saved -> TimelineScreen(
                        feed = graph.webFeedSource,
                        bookmarks = graph.bookmarkStore,
                        contentPadding = padding,
                        showOnlyBookmarked = true,
                        onChannelOpen = { /* no-op: guest mode */ },
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
    }
}
