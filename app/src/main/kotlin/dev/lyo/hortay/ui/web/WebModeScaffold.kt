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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.feed.SimpleFeed
import dev.lyo.hortay.ui.main.FloatingNavBar
import dev.lyo.hortay.ui.main.NavTab
import dev.lyo.hortay.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Top-level container for guest (anonymous) reading mode. Mirrors
 * [dev.lyo.hortay.ui.main.MainScaffold] structure exactly: same [FloatingNavBar]
 * at the bottom, same four [NavTab] entries, same [AnimatedContent] tab
 * transition. Differs only in which Composable each tab dispatches to:
 *
 *   - Feed     → [SimpleFeed] reading [WebFeedSource.posts]
 *   - Channels → [WebChannelsScreen] (subscribed list; channel data shape
 *               differs from TDLib's chat list, kept separate)
 *   - Saved    → [SimpleFeed] reading [WebRepository.observeBookmarked]
 *   - Profile  → [SettingsScreen] in guest mode (sign-in CTA + clear-cache)
 *
 * Web-specific files removed during the unification: WebTimelineScreen,
 * WebSavedScreen, WebSettingsScreen, WebPostCard, WebFeedEntry — replaced by
 * shared composables and types.
 */
@Composable
fun WebModeScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val locale = remember { Locale.getDefault().language.lowercase() }
    val posts by graph.webFeedSource.posts.collectAsStateWithLifecycle()
    val refreshState by graph.webFeedSource.refreshState.collectAsStateWithLifecycle()
    val isRefreshing = refreshState is dev.lyo.hortay.data.web.WebFeedSource.RefreshState.Refreshing
    val bookmarked by graph.webRepository.observeBookmarked()
        .collectAsStateWithLifecycle(initialValue = kotlinx.collections.immutable.persistentListOf())

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
                    NavTab.Feed -> SimpleFeed(
                        title = stringResource(R.string.web_feed_title),
                        posts = posts,
                        isRefreshing = isRefreshing,
                        isInitialEmpty = false,
                        emptyText = stringResource(R.string.web_empty_posts),
                        loadingText = stringResource(R.string.web_loading),
                        onRefresh = { scope.launch { graph.webFeedSource.refresh(force = true) } },
                        contentPadding = padding,
                        actionIconSymbol = "add",
                        actionIconContentDescription = stringResource(R.string.web_add_channel),
                        onActionClick = { addSheetOpen = true },
                    )

                    NavTab.Channels -> WebChannelsScreen(
                        graph = graph,
                        contentPadding = padding,
                        onChannelClick = { selectedTab = NavTab.Feed },
                    )

                    NavTab.Saved -> SimpleFeed(
                        title = stringResource(R.string.web_saved_title),
                        posts = bookmarked,
                        isRefreshing = false,
                        isInitialEmpty = false,
                        emptyText = stringResource(R.string.web_empty_saved),
                        loadingText = stringResource(R.string.web_loading),
                        onRefresh = { /* bookmarked has no refresh */ },
                        contentPadding = padding,
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
