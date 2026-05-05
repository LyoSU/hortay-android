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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.lyo.hortay.AppGraph
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
    val scope = rememberCoroutineScope()
    val locale = remember { Locale.getDefault().language.lowercase() }

    BackHandler(enabled = selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab -> selectedTab = tab },
            )
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
                    )

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = { selectedTab = NavTab.Feed },
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
                        onClearWebCache = { graph.webRepository.clearAllCache() },
                    )
                }
            }
        }
    }

    if (addSheetOpen) {
        AddChannelSheet(
            feedSource = graph.webFeedSource,
            client = graph.webClient,
            locale = locale,
            onDismiss = { addSheetOpen = false },
        )
    }
}
