@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.nav.AppNavKey
import dev.lyo.hortay.data.nav.ArchiveKey
import dev.lyo.hortay.data.nav.ArchiveSettingsKey
import dev.lyo.hortay.data.nav.ChannelKey
import dev.lyo.hortay.data.nav.CommentsKey
import dev.lyo.hortay.data.nav.HomeKey
import dev.lyo.hortay.data.nav.WebChannelKey
import dev.lyo.hortay.ui.archive.ArchiveScreen
import dev.lyo.hortay.ui.archive.ArchiveSettingsScreen
import dev.lyo.hortay.ui.archive.ArchiveSettingsViewModel
import dev.lyo.hortay.ui.archive.ArchiveViewModel
import dev.lyo.hortay.ui.comments.CommentsScreen
import dev.lyo.hortay.ui.timeline.ChannelScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Renders a single Navigation 3 detail entry ([AppNavKey]) to its screen, with the exact
 * dependency/callback wiring the old `NavOverlayRenderer` + `RenderNavEntry` used.
 *
 * Called from `NavDisplay`'s `entryProvider` in [MainScaffold] (one `entry<T>` per detail key).
 * Nav3 owns what the hand-rolled stack used to: the back-stack list, predictive back (so the
 * per-layer graphicsLayer transform is gone), per-entry saveable state and per-entry
 * `ViewModelStore` (via the NavDisplay entry decorators — replacing the custom `NavEntryHost`,
 * which is why drilling the same channel repeatedly no longer leaks a ViewModel).
 *
 * [HomeKey] is rendered inline in [MainScaffold] (the tab scaffold sits beneath the NavDisplay),
 * and [WebChannelKey] is guest-mode only — both are no-ops here.
 */
@Composable
internal fun RenderNavKey(
    key: AppNavKey,
    graph: AppGraph,
    padding: PaddingValues,
    feedOrder: FeedOrder,
    scope: CoroutineScope,
    onPopNav: () -> Unit,
    onPushComments: (TimelinePost) -> Unit,
    onShowFullPost: (TimelinePost, Int) -> Unit,
    onSafelyOpenChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onOpenReport: (chatId: Long, messageId: Long?) -> Unit,
    onPostReportClick: (TimelinePost) -> Unit,
    canReportPost: (TimelinePost) -> Boolean,
    onLinkNotFound: () -> Unit,
) {
    when (key) {
        is ChannelKey -> ChannelScreen(
            chatId = key.chatId,
            repo = graph.postsRepository,
            commentsRepo = graph.commentsRepository,
            bookmarks = graph.bookmarkStore,
            translations = graph.translations,
            channelActions = graph.channelActions,
            ignoredChannels = graph.ignoredChannels,
            contentPadding = padding,
            onBack = onPopNav,
            onChannelOpen = { cid, scrollTo -> onSafelyOpenChannel(cid, scrollTo) },
            onOpenComments = { post -> onPushComments(post) },
            onShowFullPost = onShowFullPost,
            scrollToMessage = key.scrollToMessageId?.let { key.chatId to it },
            onScrollHandled = {},
            onScrollMissed = onLinkNotFound,
            onReportClick = onPostReportClick,
            canReport = canReportPost,
            onReportChannel = { onOpenReport(key.chatId, null) },
            feedOrder = feedOrder,
            startupPhase = graph.startupCoordinator.phase,
        )

        is CommentsKey -> CommentsScreen(
            post = key.anchor,
            heroAnchorY = key.heroAnchorY,
            repo = graph.commentsRepository,
            feedRepo = graph.postsRepository,
            onDismiss = onPopNav,
            onChannelClick = { p -> onSafelyOpenChannel(p.chatId, null) },
            onAuthorChatClick = { id -> onSafelyOpenChannel(id, null) },
            onQuotedSourceClick = { post ->
                post.reply?.let { r ->
                    onSafelyOpenChannel(r.replyToChatId, r.replyToMessageId)
                }
            },
            onReactionToggle = { chatId, messageId, snapshot, kind, wasChosen ->
                val isAnchor = chatId == key.anchor.chatId
                val nowChosen = !wasChosen
                if (isAnchor) {
                    graph.postsRepository.applyOptimisticReaction(chatId, messageId, kind, nowChosen)
                } else {
                    graph.commentsRepository.applyOptimisticReaction(chatId, messageId, snapshot, kind, nowChosen)
                }
                scope.launch {
                    val ok = graph.channelActions.toggleReaction(
                        chatId = chatId,
                        messageId = messageId,
                        kind = kind,
                        isChosen = wasChosen,
                    )
                    if (!ok) {
                        if (isAnchor) {
                            graph.postsRepository.applyOptimisticReaction(chatId, messageId, kind, wasChosen)
                        } else {
                            graph.commentsRepository.clearOptimisticReaction(chatId, messageId)
                        }
                    }
                }
            },
            fetchAvailableReactions = { chatId, messageId ->
                graph.channelActions.availableReactions(chatId, messageId)
            },
            onPollVote = { chatId, messageId, indices ->
                graph.postsRepository.applyOptimisticPollAnswer(chatId, messageId, indices)
                scope.launch {
                    val ok = graph.channelActions.setPollAnswer(chatId, messageId, indices)
                    graph.postsRepository.clearPollPending(chatId, messageId, revert = !ok)
                }
            },
        )

        is ArchiveKey -> {
            val vm = viewModel { ArchiveViewModel(graph.archiveRepository) }
            ArchiveScreen(
                viewModel = vm,
                onBack = onPopNav,
                mediaStore = graph.archivedMediaStore,
            )
        }

        is ArchiveSettingsKey -> {
            val vm = viewModel {
                ArchiveSettingsViewModel(
                    store = graph.archiveSettingsStore,
                    repo = graph.archiveRepository,
                    sweep = graph.archiveSweep,
                )
            }
            ArchiveSettingsScreen(
                viewModel = vm,
                onBack = onPopNav,
                onOpenArchive = { graph.backStack.add(ArchiveKey) },
            )
        }

        // Tab host (rendered inline in MainScaffold beneath the NavDisplay) and guest-mode
        // web channel (handled by the guest scaffold) never reach this provider.
        HomeKey, is WebChannelKey -> Unit
    }
}
