@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.activity.BackEventCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.NavEntry
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.archive.ArchiveScreen
import dev.lyo.hortay.ui.archive.ArchiveSettingsScreen
import dev.lyo.hortay.ui.archive.ArchiveSettingsViewModel
import dev.lyo.hortay.ui.archive.ArchiveViewModel
import dev.lyo.hortay.ui.comments.CommentsScreen
import dev.lyo.hortay.ui.timeline.ChannelScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Per-[NavEntry] [ViewModelStoreOwner] with deterministic cleanup. The host
 * creates an isolated [ViewModelStore] keyed on [entry]'s stable UUID and
 * clears it when the entry leaves composition (i.e. when the user pops the
 * stack past it).
 *
 * Why custom and not [androidx.lifecycle.viewmodel.compose.viewModel] keyed
 * on a chatId string: that helper caches into the surrounding Activity-scoped
 * [ViewModelStore], so the cached ViewModel never gets cleared until the
 * Activity dies. Repeatedly drilling into channels accumulated one VM per
 * unique chatId for the whole session — a real (if slow) heap leak. This host
 * is the canonical Google-recommended workaround (and the same pattern that
 * AndroidX Navigation uses internally): a [ViewModelStoreOwner] scoped to the
 * navigation entry, [DisposableEffect]-cleared on exit.
 */
@Composable
private fun NavEntryHost(entry: NavEntry, content: @Composable () -> Unit) {
    val owner = remember(entry.entryId) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(entry.entryId) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}

/**
 * Renders the top-2 entries of the polymorphic nav stack as stacked layers
 * above the always-mounted feed.
 *
 * Why TWO and not just the top: during a predictive-back swipe the user expects
 * to see the layer underneath peeking through — a single-slot top render would
 * reveal the static feed underneath instead of the next-below stack entry
 * (Channel underneath Comments, or vice-versa). Telegram / Twitter / Instagram
 * all behave this way. The extra cost is one screen composition; the bottom
 * entry stays mounted across the swipe so its scroll position and ViewModel
 * are untouched.
 *
 * No push/pop slide animation: M3 Expressive favours immediacy for
 * detail-screen pushes (TG-Android and Twitter both render new detail screens
 * in place). The only motion that matters here is the predictive-back transform
 * on the current top, which the graphicsLayer drives via [navBackProgress].
 *
 * Render visible entries in ONE forEach so each entry has a single stable
 * position in the composition tree. After a pop, the entry that was at index 0
 * (the "below" layer) is still at index 0 in the new takeLast(2) result —
 * Compose's position+key identity matching preserves its remember-group across
 * the transition: ViewModelStore stays the same instance, saveable state stays
 * bound, no remount-flicker on comment counts or list rows.
 *
 * The opposite — rendering "below" and "top" in two separate top-level
 * branches — created two distinct composition positions, and an entry
 * migrating between them looked like an unmount + remount to Compose.
 */
@Composable
internal fun NavOverlayRenderer(
    visibleEntries: List<NavEntry>,
    navStateHolder: SaveableStateHolder,
    navBackProgress: Float,
    navBackEdge: Int,
    graph: AppGraph,
    padding: PaddingValues,
    feedOrder: FeedOrder,
    scope: CoroutineScope,
    onPopNav: () -> Unit,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushComments: (TimelinePost) -> Unit,
    onShowFullPost: (TimelinePost, Int) -> Unit,
    onSafelyOpenChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onOpenReport: (chatId: Long, messageId: Long?) -> Unit,
    onPostReportClick: (TimelinePost) -> Unit,
    canReportPost: (TimelinePost) -> Boolean,
) {
    val res = androidx.compose.ui.platform.LocalContext.current.resources

    visibleEntries.forEachIndexed { idx, entry ->
        val isTop = idx == visibleEntries.lastIndex
        key(entry.entryId) {
            navStateHolder.SaveableStateProvider(key = entry.entryId) {
                NavEntryHost(entry = entry) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isTop) {
                                    Modifier.graphicsLayer {
                                        val p = navBackProgress.coerceIn(0f, 2f)
                                        val signed = when (navBackEdge) {
                                            BackEventCompat.EDGE_RIGHT -> -p
                                            else -> p
                                        }
                                        translationX = signed * size.width * 0.25f
                                        val s = 1f - p.coerceAtMost(1f) * 0.05f
                                        scaleX = s; scaleY = s
                                        alpha = (1f - p.coerceAtMost(1f) * 0.9f)
                                            .coerceAtLeast(0f)
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        RenderNavEntry(
                            entry = entry,
                            isCurrent = isTop,
                            graph = graph,
                            padding = padding,
                            feedOrder = feedOrder,
                            scope = scope,
                            navBackProgress = navBackProgress,
                            navBackEdge = navBackEdge,
                            onPopNav = onPopNav,
                            onPushChannel = onPushChannel,
                            onPushComments = onPushComments,
                            onShowFullPost = onShowFullPost,
                            onSafelyOpenChannel = onSafelyOpenChannel,
                            onOpenReport = onOpenReport,
                            onPostReportClick = onPostReportClick,
                            canReportPost = canReportPost,
                            onLinkNotFound = {
                                graph.userMessages.post(
                                    res.getString(R.string.link_not_found),
                                    UserMessageBus.Severity.Info,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderNavEntry(
    entry: NavEntry,
    isCurrent: Boolean,
    graph: AppGraph,
    padding: PaddingValues,
    feedOrder: FeedOrder,
    scope: CoroutineScope,
    navBackProgress: Float,
    navBackEdge: Int,
    onPopNav: () -> Unit,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onPushComments: (TimelinePost) -> Unit,
    onShowFullPost: (TimelinePost, Int) -> Unit,
    onSafelyOpenChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    onOpenReport: (chatId: Long, messageId: Long?) -> Unit,
    onPostReportClick: (TimelinePost) -> Unit,
    canReportPost: (TimelinePost) -> Boolean,
    onLinkNotFound: () -> Unit,
) {
    when (entry) {
        is NavEntry.Channel -> ChannelScreen(
            chatId = entry.chatId,
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
            scrollToMessage = entry.scrollToMessageId
                ?.let { entry.chatId to it }
                ?.takeIf { isCurrent },
            onScrollHandled = {},
            onScrollMissed = onLinkNotFound,
            onReportClick = onPostReportClick,
            canReport = canReportPost,
            onReportChannel = { onOpenReport(entry.chatId, null) },
            feedOrder = feedOrder,
            startupPhase = graph.startupCoordinator.phase,
        )
        is NavEntry.Comments -> CommentsScreen(
            post = entry.anchor,
            heroTopPaddingPx = entry.heroTopPaddingPx,
            repo = graph.commentsRepository,
            feedRepo = graph.postsRepository,
            onDismiss = onPopNav,
            // The three "leave this detail view for somewhere else" hooks
            // below — channel-chip tap, foreign-author header tap, reply
            // quote-card tap — all route through [onSafelyOpenChannel],
            // which keeps the regular back-stack semantics every other
            // surface uses: forward push, back-swipe returns to this
            // Comments overlay, swipe again returns to whatever was
            // underneath.
            //
            // The helper carries a smart-pop shortcut for the case where
            // the destination matches the entry directly below the
            // current top (Comments anchored at a post of the channel
            // that's right below us in the stack). It pops the overlay
            // instead of stacking a duplicate Channel-A on top of
            // [Channel-A, Comments]. That preserves the existing
            // Channel-A's scroll / ViewModel and avoids the
            // double-back-to-exit a duplicate would force.
            //
            // The earlier `replaceTop` design optimised for the same
            // deep-stack case but at the cost of the shallow one (feed →
            // Comments → tap channel): collapsing Comments meant
            // back-swipe from the destination skipped the post detail
            // the user just opened, landing on the feed instead. The
            // smart-pop variant fixes both.
            onChannelClick = { p -> onSafelyOpenChannel(p.chatId, null) },
            onAuthorChatClick = { id -> onSafelyOpenChannel(id, null) },
            onQuotedSourceClick = { post ->
                // `replyToChatId` is already normalised at the mapping
                // boundary ([MessageMapper.mapReply]) — TDLib's
                // "unknown chat" sentinel `chat_id = 0` is rewritten to
                // the host post's own chatId for the same-chat case, so
                // we pass it through verbatim here. Same-channel and
                // cross-channel replies share the path: the new entry's
                // `scrollToMessageId` lands the destination at the
                // replied-to message and pulses the highlight there.
                // The non-null `scrollToMessageId` also opts out of the
                // smart-pop shortcut in [safelyOpenChannel] — an
                // already-mounted channel below would keep its own
                // scroll state and ignore the new anchor.
                post.reply?.let { r ->
                    onSafelyOpenChannel(r.replyToChatId, r.replyToMessageId)
                }
            },
            onReactionToggle = { chatId, messageId, snapshot, kind, wasChosen ->
                // Tap origin is encoded in [chatId]: the anchor's chat is the
                // channel post's chatId; comments live in the linked discussion
                // supergroup ([CommentsRepository.ThreadState.Ready.threadChatId]).
                // We route the optimistic mutation to the repository that owns
                // that state and let TDLib reconcile via the usual
                // UpdateMessageInteractionInfo path.
                val isAnchor = chatId == entry.anchor.chatId
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
                        // Revert: for the feed-backed anchor we re-toggle to the
                        // pre-tap state (the inverse delta); for a comment we
                        // simply drop the override so the chip falls back to
                        // whatever TDLib last said.
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
                // Optimistic flip on the anchor's poll → SetPollAnswer → clear pending.
                // The pinned post in CommentsScreen is always the feed's anchor; the
                // anchor lives in `PostsRepository`, not `CommentsRepository`.
                graph.postsRepository.applyOptimisticPollAnswer(chatId, messageId, indices)
                scope.launch {
                    val ok = graph.channelActions.setPollAnswer(chatId, messageId, indices)
                    graph.postsRepository.clearPollPending(chatId, messageId, revert = !ok)
                }
            },
            backProgress = if (isCurrent) navBackProgress else 0f,
            backSwipeEdge = navBackEdge,
        )
        // Guest-mode variant — never composed here in practice
        // (MainActivity routes WebChannel into WebModeScaffold), but
        // making the `when` exhaustive over the sealed hierarchy keeps
        // the compiler honest if the routing rule ever changes.
        is NavEntry.WebChannel -> Unit

        is NavEntry.Archive -> {
            val vm = remember { ArchiveViewModel(graph.archiveRepository) }
            ArchiveScreen(
                viewModel = vm,
                onBack = onPopNav,
                mediaStore = graph.archivedMediaStore,
            )
        }

        is NavEntry.ArchiveSettings -> {
            val vm = remember {
                ArchiveSettingsViewModel(
                    store = graph.archiveSettingsStore,
                    repo = graph.archiveRepository,
                    sweep = graph.archiveSweep,
                )
            }
            ArchiveSettingsScreen(
                viewModel = vm,
                onBack = onPopNav,
                onOpenArchive = { graph.nav.push(dev.lyo.hortay.data.NavEntry.Archive()) },
            )
        }
    }
}
