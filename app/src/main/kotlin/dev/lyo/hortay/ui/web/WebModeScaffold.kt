package dev.lyo.hortay.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.FloatingNavBar
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
@Composable
fun WebModeScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Username carried over from a deep-link arrival into the AddChannelSheet.
    // Null in the manual-open case (clipboard auto-paste runs); set to a bare
    // handle by the deep-link collector below to skip the paste step entirely.
    var deepLinkPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    // Monotonic counter incremented on each "Home" re-tap — TimelineScreen
    // observes it and scrolls the feed to top (or refreshes when already at
    // top). Same mechanism as MainScaffold so the home-tap-to-scroll gesture
    // works identically in both modes.
    var homeTapTrigger by rememberSaveable { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(Unit) {
        graph.deepLinkRouter.events.collect { link ->
            when (link) {
                is DeepLink.PublicChannel -> {
                    deepLinkPrefill = link.handle
                    addSheetOpen = true
                }
                is DeepLink.PrivateChannel,
                is DeepLink.Message -> {
                    snackbarHostState.showSnackbar(signInRequiredMsg)
                }
            }
        }
    }

    BackHandler(enabled = selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab ->
                    if (tab == selectedTab && tab == NavTab.Feed) {
                        // Re-tap on the active Home tab → bump the counter so
                        // TimelineScreen scrolls to top (or refreshes when
                        // already there). Same gesture contract as TDLib mode.
                        homeTapTrigger = System.nanoTime()
                    } else {
                        selectedTab = tab
                    }
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
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "web-tab-switch",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                when (tab) {
                    NavTab.Feed -> TimelineScreen(
                        feed = graph.webFeedSource,
                        bookmarks = graph.bookmarkStore,
                        contentPadding = padding,
                        showOnlyBookmarked = false,
                        channelFilter = null,
                        onChannelFilterChange = { /* no per-channel filter in guest mode */ },
                        homeTapTrigger = homeTapTrigger,
                        onBrandTap = { homeTapTrigger = System.nanoTime() },
                        onSearchClick = { searchOpen = true },
                    )

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = { selectedTab = NavTab.Feed },
                        onAddChannel = { addSheetOpen = true },
                    )

                    NavTab.Saved -> TimelineScreen(
                        feed = graph.webFeedSource,
                        bookmarks = graph.bookmarkStore,
                        contentPadding = padding,
                        showOnlyBookmarked = true,
                        channelFilter = null,
                        onChannelFilterChange = { /* no-op: guest mode */ },
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
