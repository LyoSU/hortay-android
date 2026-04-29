package dev.lyo.telread.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.telread.AppGraph
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.channels.ChannelsScreen
import dev.lyo.telread.ui.comments.CommentsScreen
import dev.lyo.telread.ui.settings.SettingsScreen
import dev.lyo.telread.ui.timeline.TimelineScreen
import kotlinx.coroutines.launch

/**
 * Top-level container that owns nav-tab state, the global channel filter and the comments
 * overlay, then dispatches the four primary surfaces.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var channelFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var commentsForPost by remember { mutableStateOf<TimelinePost?>(null) }
    // Monotonic counter: each re-tap on Home (or brand) bumps it once. The Feed observes the
    // value and decides scroll-to-top vs refresh based on its own scroll position.
    var homeTapTrigger by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val connection by graph.tdClient.connection.collectAsStateWithLifecycle()

    // Back priority: dismiss overlay → clear channel filter → return to Feed → system close.
    BackHandler(enabled = commentsForPost != null) { commentsForPost = null }
    BackHandler(enabled = commentsForPost == null && channelFilter != null) { channelFilter = null }
    BackHandler(enabled = commentsForPost == null && channelFilter == null && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab ->
                    val reselectingFeed = tab == selectedTab && tab == NavTab.Feed
                    if (reselectingFeed) {
                        channelFilter = null
                        homeTapTrigger = System.nanoTime()
                    }
                    selectedTab = tab
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "tab-switch",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                NavTab.Feed -> TimelineScreen(
                    repo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = false,
                    channelFilter = channelFilter,
                    onChannelFilterChange = { channelFilter = it },
                    onOpenComments = { commentsForPost = it },
                    homeTapTrigger = homeTapTrigger,
                    onBrandTap = { homeTapTrigger = System.nanoTime() },
                )
                NavTab.Channels -> ChannelsScreen(
                    repo = graph.postsRepository,
                    contentPadding = padding,
                    onChannelClick = { chatId ->
                        channelFilter = chatId
                        selectedTab = NavTab.Feed
                    },
                )
                NavTab.Saved -> TimelineScreen(
                    repo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = true,
                    channelFilter = null,
                    onChannelFilterChange = {
                        // Tapping a channel from Saved jumps the user back to the live feed
                        // pre-filtered to that channel — same UX as ChannelsScreen.
                        if (it != null) {
                            channelFilter = it
                            selectedTab = NavTab.Feed
                        }
                    },
                    onOpenComments = { commentsForPost = it },
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                )
                NavTab.Profile -> SettingsScreen(
                    settings = graph.settingsStore,
                    stats = graph.statsRepository,
                    contentPadding = padding,
                    onLogout = { scope.launch { graph.tdClient.logOut() } },
                )
            }
        }

        ConnectionBanner(
            status = connection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = padding.calculateTopPadding()),
        )
        }
    }

    commentsForPost?.let { post ->
        CommentsScreen(
            post = post,
            repo = graph.commentsRepository,
            onDismiss = { commentsForPost = null },
            onChannelClick = { p ->
                channelFilter = p.chatId
                selectedTab = NavTab.Feed
                commentsForPost = null
            },
        )
    }
}
