@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.channels.ChannelsScreen
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.timeline.TimelineScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tab content switcher. Tab swap = pure crossfade. fastEffectsSpec is M3E's correct
 * channel for non-spatial state changes; on the same spring the FloatingNavBar's
 * selection container/colour/icon-fill morph runs, so the bottom-nav morph and the
 * content crossfade land together (no out-of-sync blink).
 *
 * [tabStateHolder] gives each tab its own independent saveable scope via
 * SaveableStateProvider(key = tab.name), so rememberSaveable /
 * rememberLazyListState / rememberScrollState inside each tab survive
 * AnimatedContent's mount/unmount lifecycle — the user's scroll position on
 * Channels, Saved, Profile, and the Feed all-chats view is preserved across tab
 * switches without any in-screen dual-state tricks.
 *
 * For NavTab.Feed a NESTED per-channel provider wraps TimelineScreen so every
 * visited channel (and the all-feed "no filter" view) gets its own independent
 * scroll/search state. Returning to a previously-visited channel in the
 * back-stack restores that channel's exact scroll position.
 */
@Composable
internal fun TabContentSwitcher(
    selectedTab: NavTab,
    tabStateHolder: SaveableStateHolder,
    graph: AppGraph,
    padding: PaddingValues,
    feedOrder: FeedOrder,
    snapScroll: Boolean,
    homeTapTrigger: Long,
    coveredByOverlay: Boolean,
    scope: CoroutineScope,
    onHomeTapTriggerBump: () -> Unit,
    onSafelyOpenChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushComments: (TimelinePost) -> Unit,
    onPostReportClick: (TimelinePost) -> Unit,
    canReportPost: (TimelinePost) -> Boolean,
    tdlibMarkAsRead: suspend (List<TimelinePost>) -> Unit,
) {
    val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val res = androidx.compose.ui.platform.LocalContext.current.resources

    AnimatedContent(
        targetState = selectedTab,
        transitionSpec = { fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec) },
        label = "tab-switch",
        modifier = Modifier.fillMaxSize(),
    ) { tab ->
        tabStateHolder.SaveableStateProvider(key = tab.name) {
            when (tab) {
                NavTab.Feed -> {
                    // Telegram-Android / Twitter / Instagram pattern: list screen
                    // stays mounted, detail screens render as nav-stack overlays
                    // OUTSIDE this tab branch (the top-2 entries of [graph.nav.stack],
                    // drawn just below the ConnectionBanner). Keeping TimelineScreen
                    // mounted across channel drills means:
                    //   - Scroll position is owned by [rememberLazyListState],
                    //     never serialised through SaveableStateProvider's
                    //     unmount→remount cycle. The shared `_posts` flow can
                    //     mutate (loadChannelHistory backfills, refresh streams)
                    //     while the user is in the channel overlay; viewport-driven
                    //     side effects are paused via coveredByOverlay, so on close
                    //     the feed is right where they left it.
                    //   - One source of subscriptions (avatars, comment prefetch,
                    //     dwell-ack focus tracking) instead of a re-init burst on
                    //     every drill-back.
                    //   - Comments overlay (already z-stacked above channel here)
                    //     and channel overlay use the same vocabulary, so the
                    //     gesture model and motion language are uniform.
                    tabStateHolder.SaveableStateProvider(key = "feed-channel:__all__") {
                        TimelineScreen(
                            feed = graph.postsRepository,
                            tdlibRepo = graph.postsRepository,
                            commentsRepo = graph.commentsRepository,
                            folders = graph.chatFoldersRepository,
                            translations = graph.translations,
                            channelActions = graph.channelActions,
                            bookmarks = graph.bookmarkStore,
                            contentPadding = padding,
                            showOnlyBookmarked = false,
                            onChannelOpen = { id, scrollTo -> onSafelyOpenChannel(id, scrollTo) },
                            onOpenComments = { post -> onPushComments(post) },
                            homeTapTrigger = homeTapTrigger,
                            onBrandTap = onHomeTapTriggerBump,
                            // Deep-link scroll targets are now baked into the nav-entry
                            // itself (NavEntry.Channel.scrollToMessageId). The all-feed
                            // TimelineScreen is never the deep-link landing — links push
                            // a channel entry that owns the scroll-target. Pass null here.
                            scrollToMessage = null,
                            onScrollHandled = {},
                            onScrollMissed = {
                                graph.userMessages.post(
                                    res.getString(R.string.link_not_found),
                                    UserMessageBus.Severity.Info,
                                )
                            },
                            startupPhase = graph.startupCoordinator.phase,
                            onReportClick = onPostReportClick,
                            canReport = canReportPost,
                            markAsRead = tdlibMarkAsRead,
                            feedOrder = feedOrder,
                            snapScroll = snapScroll,
                            coveredByOverlay = coveredByOverlay,
                        )
                    }
                }
                NavTab.Channels -> ChannelsScreen(
                    repo = graph.postsRepository,
                    contentPadding = padding,
                    onChannelClick = { chatId ->
                        onPushChannel(chatId, null)
                    },
                )
                NavTab.Saved -> TimelineScreen(
                    feed = graph.postsRepository,
                    tdlibRepo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    folders = graph.chatFoldersRepository,
                    translations = graph.translations,
                    channelActions = graph.channelActions,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = true,
                    onChannelOpen = { id, scrollTo ->
                        // Tapping a channel from Saved pushes a Channel entry — Back
                        // returns the user to Saved instead of always resetting to
                        // all-feed. Quote-tap on a Saved card lands the new channel
                        // at the replied-to message (scrollTo != null). Gated through
                        // [safelyOpenChannel] so a Saved card from a since-converted
                        // supergroup / private-no-access source doesn't dump the
                        // user on an empty ChannelScreen.
                        onSafelyOpenChannel(id, scrollTo)
                    },
                    onOpenComments = { post -> onPushComments(post) },
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                    startupPhase = graph.startupCoordinator.phase,
                    onReportClick = onPostReportClick,
                    canReport = canReportPost,
                    markAsRead = tdlibMarkAsRead,
                    feedOrder = feedOrder,
                    snapScroll = snapScroll,
                    coveredByOverlay = coveredByOverlay,
                )
                NavTab.Profile -> {
                    val me by graph.tdClient.me.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settings = graph.settingsStore,
                        stats = graph.statsRepository,
                        contentPadding = padding,
                        ignoredChannels = graph.ignoredChannels,
                        channelActions = graph.channelActions,
                        onLogout = { scope.launch { graph.tdClient.logOut() } },
                        autoDownload = graph.autoDownloadStore,
                        me = me,
                    )
                }
            }
        }
    }
}
