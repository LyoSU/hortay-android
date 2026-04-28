package dev.lyo.telread.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import dev.lyo.telread.AppGraph
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.channels.ChannelsScreen
import dev.lyo.telread.ui.comments.CommentsScreen
import dev.lyo.telread.ui.settings.SettingsScreen
import dev.lyo.telread.ui.timeline.TimelineScreen
import kotlinx.coroutines.launch

/**
 * Top-level container that owns the floating bottom navigation and dispatches between the
 * four primary surfaces: feed, channels, saved, profile. State lives here so individual
 * screens stay focused on their own concerns.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var commentsForPost by remember { mutableStateOf<TimelinePost?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "tab-switch",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                NavTab.Feed -> TimelineScreen(
                    repo = graph.postsRepository,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = false,
                    onOpenComments = { commentsForPost = it },
                )
                NavTab.Channels -> ChannelsScreen(
                    repo = graph.postsRepository,
                    contentPadding = padding,
                    onChannelClick = { selectedTab = NavTab.Feed },
                )
                NavTab.Saved -> TimelineScreen(
                    repo = graph.postsRepository,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = true,
                    onOpenComments = { commentsForPost = it },
                )
                NavTab.Profile -> SettingsScreen(
                    settings = graph.settingsStore,
                    contentPadding = padding,
                    onLogout = { scope.launch { graph.tdClient.logOut() } },
                )
            }
        }
    }

    commentsForPost?.let { post ->
        CommentsScreen(
            post = post,
            repo = graph.commentsRepository,
            onDismiss = { commentsForPost = null },
        )
    }
}
