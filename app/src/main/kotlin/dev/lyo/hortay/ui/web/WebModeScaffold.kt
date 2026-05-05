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
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Top-level scaffold for guest (anonymous) reading mode. Mirrors
 * [dev.lyo.hortay.ui.main.MainScaffold] structure exactly: same
 * [FloatingNavBar] at the bottom, same [AnimatedContent] tab transition,
 * same set of [NavTab] entries — so the user can't visually tell they're
 * in a different mode beyond what's actually different (no chats, no auth
 * notifications, no FCM badge).
 *
 * Tab mapping in guest mode:
 *   - Feed     → [WebTimelineScreen]
 *   - Channels → [WebChannelsScreen] (subscribed list)
 *   - Saved    → [WebSavedScreen] (bookmarked posts; empty until wired)
 *   - Profile  → [WebSettingsSheet] launched as a screen-style overlay,
 *               since guest mode has no profile to show.
 *
 * Add-channel CTA lives on the Feed top-app-bar — same affordance position
 * as TDLib mode's "compose" button would land if/when we implement posting.
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
                    NavTab.Feed -> WebTimelineScreen(
                        feedSource = graph.webFeedSource,
                        emojiResolver = graph.webCustomEmoji,
                        contentPadding = padding,
                        onAddChannel = { addSheetOpen = true },
                    )

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = {
                            // Future: per-channel filter on feed. For now jump back
                            // to feed tab — user can scroll to find that channel's posts.
                            selectedTab = NavTab.Feed
                        },
                    )

                    NavTab.Saved -> WebSavedScreen(
                        graph = graph,
                        contentPadding = padding,
                    )

                    NavTab.Profile -> WebSettingsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onSignIn = {
                            scope.launch { graph.guestMode.setGuest(false) }
                        },
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
