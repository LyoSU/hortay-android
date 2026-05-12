@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import android.util.Log
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChatInvitePreview
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.InviteLinkKind
import dev.lyo.hortay.data.PublicHandleKind
import dev.lyo.hortay.data.PublicHandleResult
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.channels.ChannelsScreen
import dev.lyo.hortay.ui.comments.CommentsScreen
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.report.ReportFlowSheet
import dev.lyo.hortay.ui.text.ChatInvitePreviewDialog
import dev.lyo.hortay.ui.timeline.ChannelScreen
import dev.lyo.hortay.ui.timeline.TimelineScreen
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Predictive-back progress contract shared with [CommentsScreen.graphicsLayer]:
 *  - 0f .. 1f = gesture peek (translate ~10%, scale to 0.9, alpha to 0.7)
 *  - 1f .. EXIT_PROGRESS = commit exit (translate to full width, scale to 0.85, alpha to 0)
 * Going past 1f on commit keeps the overlay visually "leaving" instead of freezing at peek.
 */
private const val EXIT_PROGRESS = 2f

/**
 * TDLib encodes message ids as `serverPostId shl 20` internally (MTProto convention; see
 * tdlib/td#946). Deep-link variants carrying a server-side post number need this shift
 * before being handed to TimelineScreen's scroll-to-message dispatcher. [DeepLink.Message]
 * skips the shift because TDLib's own `GetMessageLinkInfo` returns the id already in
 * internal form.
 */
private const val SERVER_TO_TD_SHIFT = 20

/**
 * Top-level container that owns nav-tab state, the global channel filter and the comments
 * overlay, then dispatches the four primary surfaces.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }

    // Channel back-stack. Both are saveable across process death — Long list and
    // nullable enum are primitive / Serializable, no custom Saver needed.
    //
    // channelStack is the full history of channel-filter entries since the user
    // last drilled in from a top-level tab. The last element is the currently
    // visible channel filter; an empty stack means "all-feed / no filter".
    //
    // channelStackEntryTab records which top-level tab initiated the drill —
    // popping back to an empty stack restores that tab so the user lands where
    // they started rather than always falling back to Feed.
    //
    // Pop-to-existing dedup (see enterChannel): if the user taps a link to a
    // channel already in the stack (e.g. a tg:// cycle: Feed → A → B → back to
    // A via a link) we truncate to that depth instead of pushing a duplicate.
    var channelStack by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var channelStackEntryTab by rememberSaveable { mutableStateOf<NavTab?>(null) }
    val channelFilter = channelStack.lastOrNull()

    // Two-state pair for the comments overlay so it survives a process kill:
    //   • pendingCommentsKey — (chatId, post.id), saveable across process death
    //     because TimelinePost itself is not Parcelable (deep @Immutable graph
    //     including PersistentList/ByteArray fields → big Parcelize blast
    //     radius and ongoing schema-stability cost). The pair is.
    //   • commentsForPost — transient TimelinePost?, derived. Setters update
    //     both in lockstep via [openComments] / clearing both via the back
    //     stack. After process restoration, the LaunchedEffect below
    //     re-resolves the post from the live feed once it loads — small
    //     latency price, but the overlay reappears on the same post the
    //     user was reading instead of vanishing.
    var pendingCommentsKey by rememberSaveable { mutableStateOf<Pair<Long, Long>?>(null) }
    var commentsForPost by remember { mutableStateOf<TimelinePost?>(null) }
    val openComments: (TimelinePost?) -> Unit = { post ->
        commentsForPost = post
        pendingCommentsKey = post?.let { it.chatId to it.id }
    }

    // Channel back-stack navigation helpers, defined after [openComments] so
    // enterChannel can call openComments(null) when drilling clears any open thread.
    //
    // enterChannel: push chatId onto the stack (or truncate to an existing depth).
    //   Pop-to-existing dedup prevents stack bloat on tg:// cycles where a link
    //   brings the user back to a channel they were already viewing.
    //
    // popChannel: pop one level. On empty stack, restore the tab the user was on
    //   when they first drilled in (channelStackEntryTab), then reset that record.
    //
    // clearChannelStack: used when the user re-taps Home — go back to the
    //   all-feed state on the Feed tab, discarding the entire drill history.
    fun enterChannel(chatId: Long, fromTab: NavTab) {
        if (channelStack.isEmpty()) channelStackEntryTab = fromTab
        val existingIdx = channelStack.indexOf(chatId)
        channelStack = if (existingIdx >= 0) {
            channelStack.subList(0, existingIdx + 1).toList()
        } else {
            channelStack + chatId
        }
        selectedTab = NavTab.Feed
        openComments(null)
    }

    fun popChannel() {
        if (channelStack.isEmpty()) return
        channelStack = channelStack.dropLast(1)
        if (channelStack.isEmpty()) {
            selectedTab = channelStackEntryTab ?: NavTab.Feed
            channelStackEntryTab = null
        }
    }

    fun clearChannelStack() {
        channelStack = emptyList()
        channelStackEntryTab = null
    }

    // Restoration: after process kill, commentsForPost is null but pendingCommentsKey
    // survives. Watch the live feed and re-derive the post once it loads — match
    // either by anchor id or by any album member id (the user could have been
    // reading the album-anchor's comments before the kill, and on restore the
    // anchor id may have shuffled — see PostFilterStrategy album-id stability).
    LaunchedEffect(pendingCommentsKey) {
        val key = pendingCommentsKey ?: return@LaunchedEffect
        if (commentsForPost?.let { it.chatId to it.id } == key) return@LaunchedEffect
        val match = graph.postsRepository.posts
            .map { posts ->
                posts.firstOrNull { post ->
                    post.chatId == key.first &&
                        (post.id == key.second || key.second in post.albumMessageIds)
                }
            }
            .filterNotNull()
            .first()
        commentsForPost = match
    }
    // One-shot scroll-to-message request (deep link arrived with a post id, or any
    // future caller that needs TimelineScreen to land on a specific row). The pair is
    // (chatId, TDLib-shifted messageId). TimelineScreen consumes via [onScrollHandled]
    // so the request fires exactly once even if MainScaffold recomposes.
    var pendingScrollTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // Pending invite-link confirmation. Stored on the graph rather than in a local
    // [rememberSaveable] because TDLib's `CheckChatInviteLink` is suspending and runs
    // on the app scope — a rotation between the user tapping the link and the
    // preview arriving would otherwise drop the dialog on the floor. See
    // [dev.lyo.hortay.data.LinkDialogState] for the lifecycle contract.
    val pendingInvitePreview by graph.linkDialogs.invitePreview.collectAsStateWithLifecycle()
    // Monotonic counter: each re-tap on Home (or brand) bumps it once. The Feed observes the
    // value and decides scroll-to-top vs refresh based on its own scroll position.
    var homeTapTrigger by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val connection by graph.tdClient.connection.collectAsStateWithLifecycle()
    val floodWaitUntilMs by graph.tdClient.floodWaitUntilMs.collectAsStateWithLifecycle()

    // Single SnackbarHost owned by the scaffold so transient errors land on whichever
    // tab the user is currently looking at. Subscribing to the bus only while composed
    // means messages buffered during foreground transitions get delivered as soon as
    // we resume; a flooded bus drops oldest (see [UserMessageBus]) so we never queue
    // a stale apology that no longer reflects the current state.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        graph.userMessages.messages.collect { msg ->
            snackbarHostState.showSnackbar(message = msg.text)
        }
    }

    // Deep-link dispatcher. Resolved Telegram links arrive here typed; we map each to
    // (chatId, optional TDLib-shaped messageId) and switch nav state. [DeepLink.Message]
    // already carries the TDLib-internal `messageId` (resolved server-side via
    // `GetMessageLinkInfo`); the public/private channel variants still carry a server-
    // side post number which we shift here before handing to TimelineScreen.
    //
    // Failure isolation: every link is dispatched inside a `runCatching` so one bad
    // input (TDLib throw on a malformed handle, transient FLOOD_WAIT inside
    // resolvePublicHandle, etc.) cannot kill the entire collector — without this
    // wrapper a single uncaught throw inside `collect { }` permanently silences every
    // future deep-link tap until the process restarts. `CancellationException` is
    // re-thrown so the LaunchedEffect can be cancelled cleanly when the scaffold
    // leaves composition.
    val systemUriHandler = LocalUriHandler.current
    val res = LocalContext.current.resources

    fun unsupportedHandleMessageId(kind: PublicHandleKind): Int = when (kind) {
        PublicHandleKind.User -> R.string.link_unsupported_user
        PublicHandleKind.Group -> R.string.link_unsupported_group
        PublicHandleKind.Unknown -> R.string.link_unsupported_other
    }

    LaunchedEffect(Unit) {
        graph.deepLinkRouter.events.collect { link ->
            try {
                // Filled in by the channel-routed branches below; all other branches
                // (External, UnsupportedFeature, ChatInvite, Unsupported handle, NotFound)
                // hit `return@collect` before reaching the post-when block, so by the
                // time we test `targetChat != null` it is in fact always set in the
                // taken path. Compiler tracks this via DFA but we keep the explicit
                // null-check for readability — it documents "this is the only path
                // that flips channelFilter".
                val targetChat: Long
                val tdMessageId: Long?
                when (link) {
                    is DeepLink.PublicChannel -> {
                        when (val resolved = graph.postsRepository.resolvePublicHandle(link.handle)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.serverPostId?.let { it shl SERVER_TO_TD_SHIFT }
                            }
                            is PublicHandleResult.Unsupported -> {
                                graph.userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                graph.userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.PrivateChannel -> {
                        // Same kind-gate as Message — `t.me/c/<rawId>/...` *should* point
                        // at a channel but TDLib can have the chat cached as a private
                        // supergroup-chat (the user is in the group but it's not a
                        // broadcast channel). Without the gate, channelFilter flips to a
                        // chatId that loadChannelHistory short-circuits on, leaving an
                        // empty skeleton with no error path.
                        when (val resolved = graph.postsRepository.resolveChatKind(link.chatId)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.serverPostId?.let { it shl SERVER_TO_TD_SHIFT }
                            }
                            is PublicHandleResult.Unsupported -> {
                                graph.userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                graph.userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.Message -> {
                        // Same kind-gate. `GetMessageLinkInfo` happily returned a
                        // (chatId, message) pair, but the chat could be a basic group
                        // or 1:1 DM the user is in — neither has a feed surface in
                        // Hortay. Without the gate, channelFilter would land on a chat
                        // whose loadChannelHistory returns false, freezing the user.
                        when (val resolved = graph.postsRepository.resolveChatKind(link.chatId)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.messageId
                            }
                            is PublicHandleResult.Unsupported -> {
                                graph.userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                graph.userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.External -> {
                        runCatching { systemUriHandler.openUri(link.originalUrl) }
                        return@collect
                    }
                    is DeepLink.HashtagSearch -> {
                        // No in-app hashtag-search UI yet — surface a snackbar that
                        // tells the user what scope was inferred. Scoped form
                        // ("Пошук #foo у @channel") arrives when:
                        //   - the user tapped a `#tag@channel` entity (suffix carried
                        //     the scope), or
                        //   - PostBody's scoped LocalHashtagTap captured the
                        //     enclosing channel handle for a bare `#tag`.
                        // Unscoped form ("Пошук #foo") arrives for global taps —
                        // Comments thread bodies, settings text, and `tg://search`
                        // URLs (no channel scope is documented for that shape).
                        val msg = if (link.channelHandle != null) {
                            res.getString(
                                R.string.link_hashtag_search_in_channel,
                                link.tag,
                                "@${link.channelHandle}",
                            )
                        } else {
                            res.getString(R.string.link_hashtag_search, link.tag)
                        }
                        graph.userMessages.post(msg, UserMessageBus.Severity.Info)
                        return@collect
                    }
                    is DeepLink.ChatInvite -> {
                        val preview = graph.channelActions.previewChatInvite(link.inviteLink)
                        when {
                            preview == null -> {
                                graph.userMessages.post(res.getString(R.string.link_not_found))
                            }
                            preview.chatId != null -> {
                                // Already a member — drill into the channel via the back-stack
                                // so Back returns the user to where they came from rather than
                                // clearing the filter to all-feed.
                                enterChannel(preview.chatId, fromTab = NavTab.Feed)
                            }
                            preview.kind == InviteLinkKind.Channel -> {
                                graph.linkDialogs.showInvitePreview(preview)
                            }
                            else -> {
                                graph.userMessages.post(
                                    res.getString(R.string.link_unsupported_group),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                            }
                        }
                        return@collect
                    }
                }
                // Deep-link to a specific channel: push onto the back-stack so Back
                // returns the user to where they were before the tap, rather than
                // always resetting to the all-feed view. fromTab = Feed is the
                // canonical entry point for external links — the user was not inside
                // a named tab when the link arrived.
                enterChannel(targetChat, fromTab = NavTab.Feed)
                if (tdMessageId != null) {
                    pendingScrollTarget = targetChat to tdMessageId
                }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Log.w("MainScaffold", "deep-link dispatch failed for $link", t)
            }
        }
    }

    // Pending report: (chatId, messageId). Set when the user taps Report in the
    // post action sheet; cleared on sheet dismiss (or after ReportState.Success).
    // Not saveable across process death — a mid-flow report kill is acceptable
    // abandonment; the delegate try-chain in guest mode is stateless anyway.
    var pendingReport by remember { mutableStateOf<Pair<Long, Long?>?>(null) }

    // Predictive back for the comments overlay. We track live gesture progress so
    // CommentsScreen can translate / scale / fade under the user's finger instead of
    // just snapping closed on release. The edge (LEFT vs RIGHT) is forwarded because
    // users can bind back to either side of the screen — a hard-coded translateX
    // direction would invert the motion on right-handed setups.
    val commentsBackProgress = remember { Animatable(0f) }
    var commentsBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = commentsForPost != null) { progress ->
        try {
            progress.collect { event ->
                commentsBackEdge = event.swipeEdge
                commentsBackProgress.snapTo(event.progress)
            }
            // Commit: extend progress past the peek state (1f) to a full exit (2f) so
            // CommentsScreen continues translating off-screen and fading to zero alpha
            // before we drop it from composition. Without this leg the overlay would
            // freeze at peek (~70% visible) for the duration of the commit animation
            // and then snap away — janky on a flagship-class device.
            // FastOutLinearInEasing accelerates outwards, the canonical M3 exit curve.
            commentsBackProgress.animateTo(EXIT_PROGRESS, tween(220, easing = FastOutLinearInEasing))
            openComments(null)
            commentsBackProgress.snapTo(0f)
        } catch (_: CancellationException) {
            // User released before the threshold — rewind smoothly.
            commentsBackProgress.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
        }
    }
    // Back priority: pop channel stack → return to Feed tab → system close.
    // These are intra-surface state changes (no z-stacked screen above), so a
    // non-progress BackHandler is the correct primitive — PredictiveBackHandler
    // would just throw away gesture progress and add noise.
    //
    // Stack non-empty: pop one level (may restore channelStackEntryTab on
    //   last pop — see popChannel). The comments overlay is not open at this
    //   point because PredictiveBackHandler(enabled = commentsForPost != null)
    //   takes priority higher up in the composition.
    //
    // Stack empty + not on Feed: return to Feed tab.
    BackHandler(enabled = commentsForPost == null && channelStack.isNotEmpty()) { popChannel() }
    BackHandler(enabled = commentsForPost == null && channelStack.isEmpty() && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // SaveableStateHolders must live in MainScaffold's @Composable body, NOT inside
    // the Scaffold content lambda. The Scaffold body owns the tab AnimatedContent;
    // the commentsForPost overlay sits OUTSIDE that lambda (above the tab chrome and
    // FloatingNavBar — see the let-block below the Scaffold). Declaring the holder
    // inside the Scaffold lambda would put it out of scope for the overlay's
    // SaveableStateProvider call. One declaration at this level lets both call-sites
    // capture the same reference. Both holders are `remember`-backed under the hood,
    // so they survive recomposition and configuration changes.
    val tabStateHolder = rememberSaveableStateHolder()
    val commentsStateHolder = rememberSaveableStateHolder()

    // Wrap the entire content tree with the in-app UriHandler. Every descendant call
    // — LinkAnnotation.Url taps in post bodies, WebPreviewCard opens, AddChannelSheet
    // affordances, settings author rows — goes through this handler, which checks each
    // URL against the Telegram link resolver before falling back to the OS. One
    // interceptor wired here is cheaper than wrapping every Text call-site individually
    // and guarantees no path leaks straight to ACTION_VIEW.
    LinkAwareScaffold(graph) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        bottomBar = {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab ->
                    // Three distinct cases when the user taps the Home pill while
                    // selectedTab is already Feed. Telegram-Android / Twitter / X all
                    // settle on the same rule, surfaced explicitly here:
                    //
                    //  (a) User is drilled into a channel (channelStack non-empty).
                    //      Tap Home = "exit this channel back to the all-feed", do
                    //      NOT scroll the feed to the top — preserve where they were
                    //      before drilling in. Clear the stack; no homeTapTrigger bump.
                    //
                    //  (b) User is on the all-feed (channelStack empty) AND re-tapping
                    //      the active Home tab. This is the canonical "tap home twice"
                    //      gesture: bump homeTapTrigger so TimelineScreen scrolls to
                    //      top (or refreshes if already there).
                    //
                    //  (c) User is on a different tab (Channels / Saved / Profile) and
                    //      taps Home. Just switch tabs — no scroll-to-top, no stack
                    //      change (it's already empty by definition).
                    val tappingHomeWhileInChannel =
                        tab == NavTab.Feed && channelStack.isNotEmpty()
                    val reselectingActiveFeed =
                        tab == NavTab.Feed && tab == selectedTab && channelStack.isEmpty()
                    when {
                        tappingHomeWhileInChannel -> clearChannelStack()
                        reselectingActiveFeed -> homeTapTrigger = System.nanoTime()
                    }
                    selectedTab = tab
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Tab swap = pure crossfade. fastEffectsSpec is M3E's correct channel for
        // non-spatial state changes; on the same spring the FloatingNavBar's
        // selection container/colour/icon-fill morph runs, so the bottom-nav
        // morph and the content crossfade land together (no out-of-sync blink).
        //
        // [tabStateHolder] (declared at MainScaffold scope above) gives each tab its
        // own independent saveable scope via SaveableStateProvider(key = tab.name),
        // so rememberSaveable / rememberLazyListState / rememberScrollState inside
        // each tab survive AnimatedContent's mount/unmount lifecycle — the user's
        // scroll position on Channels, Saved, Profile, and the Feed all-chats view
        // is preserved across tab switches without any in-screen dual-state tricks.
        //
        // For NavTab.Feed a NESTED per-channel provider wraps TimelineScreen so
        // every visited channel (and the all-feed "no filter" view) gets its own
        // independent scroll/search state. Returning to a previously-visited channel
        // in the back-stack restores that channel's exact scroll position.
        val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

        Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec) },
            label = "tab-switch",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            tabStateHolder.SaveableStateProvider(key = tab.name) {
            when (tab) {
                NavTab.Feed -> {
                    // Per-channel scope: each channel (and the __all__ all-feed view)
                    // gets its own independent saveable scope so scroll position, search
                    // state, and any other rememberSaveable inside the active screen is
                    // preserved per context. The stack key is stable as long as the
                    // user is on that channel; navigating away and back (via the stack)
                    // restores the exact state for that channel.
                    val channelKey = channelStack.lastOrNull()?.toString() ?: "__all__"
                    tabStateHolder.SaveableStateProvider(key = "feed-channel:$channelKey") {
                        val currentChatId = channelStack.lastOrNull()
                        if (currentChatId != null) {
                            // Channel drill: dedicated ChannelScreen backed by ChannelViewModel.
                            // ChannelScreen owns its own LazyListState, search state, pagination,
                            // and read-ack — no channel-filter branches needed in TimelineScreen.
                            ChannelScreen(
                                chatId = currentChatId,
                                repo = graph.postsRepository,
                                commentsRepo = graph.commentsRepository,
                                bookmarks = graph.bookmarkStore,
                                translations = graph.translations,
                                channelActions = graph.channelActions,
                                contentPadding = padding,
                                onBack = ::popChannel,
                                onChannelOpen = { id -> enterChannel(id, fromTab = NavTab.Feed) },
                                onOpenComments = openComments,
                                scrollToMessage = pendingScrollTarget,
                                onScrollHandled = { pendingScrollTarget = null },
                                onScrollMissed = {
                                    graph.userMessages.post(
                                        res.getString(R.string.link_not_found),
                                        UserMessageBus.Severity.Info,
                                    )
                                },
                                onReportClick = { post ->
                                    pendingReport = post.chatId to if (post.id != 0L) post.id else null
                                },
                                canReport = { post -> post.canReportChat },
                                // Channel-level Report row inside the info sheet:
                                // route to ReportFlowSheet with messageId=null so
                                // TDLib treats the report as scoped to the whole
                                // chat instead of a specific message.
                                onReportChannel = { pendingReport = currentChatId to null },
                            )
                        } else {
                            // All-feed view: TimelineScreen with no channel filter.
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
                                onChannelOpen = { id -> enterChannel(id, fromTab = NavTab.Feed) },
                                onOpenComments = openComments,
                                homeTapTrigger = homeTapTrigger,
                                onBrandTap = { homeTapTrigger = System.nanoTime() },
                                scrollToMessage = pendingScrollTarget,
                                onScrollHandled = { pendingScrollTarget = null },
                                onScrollMissed = {
                                    graph.userMessages.post(
                                        res.getString(R.string.link_not_found),
                                        UserMessageBus.Severity.Info,
                                    )
                                },
                                startupPhase = graph.startupCoordinator.phase,
                                onReportClick = { post ->
                                    pendingReport = post.chatId to if (post.id != 0L) post.id else null
                                },
                                canReport = { post -> post.canReportChat },
                            )
                        }
                    }
                }
                NavTab.Channels -> ChannelsScreen(
                    repo = graph.postsRepository,
                    contentPadding = padding,
                    onChannelClick = { chatId ->
                        enterChannel(chatId, fromTab = NavTab.Channels)
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
                    onChannelOpen = { id ->
                        // Tapping a channel from Saved jumps to ChannelScreen for that
                        // channel. enterChannel pushes onto the back-stack so Back returns
                        // the user to Saved instead of always resetting to all-feed.
                        enterChannel(id, fromTab = NavTab.Saved)
                    },
                    onOpenComments = openComments,
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                    startupPhase = graph.startupCoordinator.phase,
                    onReportClick = { post ->
                        pendingReport = post.chatId to if (post.id != 0L) post.id else null
                    },
                    canReport = { post -> post.canReportChat },
                )
                NavTab.Profile -> SettingsScreen(
                    settings = graph.settingsStore,
                    stats = graph.statsRepository,
                    contentPadding = padding,
                    onLogout = { scope.launch { graph.tdClient.logOut() } },
                    autoDownload = graph.autoDownloadStore,
                )
            }
            }
        }

        ConnectionBanner(
            status = connection,
            floodWaitUntilMs = floodWaitUntilMs,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )
        }
    }


    commentsForPost?.let { post ->
        // commentsStateHolder keys each overlay on (chatId, post.id) so that
        // reopening the same post restores the thread's exact scroll position.
        // State preservation is parent-owned via SaveableStateProvider here —
        // CommentsScreen's own rememberLazyListState() at line 111 does not need
        // any custom keying; it benefits automatically from this scope.
        commentsStateHolder.SaveableStateProvider(key = "post:${post.chatId}:${post.id}") {
        CommentsScreen(
            post = post,
            repo = graph.commentsRepository,
            onDismiss = { openComments(null) },
            onChannelClick = { p ->
                enterChannel(p.chatId, fromTab = NavTab.Feed)
            },
            backProgress = commentsBackProgress.value,
            backSwipeEdge = commentsBackEdge,
        )
        }
    }
    pendingInvitePreview?.let { preview ->
        ChatInvitePreviewDialog(
            preview = preview,
            onConfirm = {
                graph.linkDialogs.dismissInvitePreview()
                scope.launch {
                    val joinedId = graph.channelActions.joinByInvite(preview.inviteLink)
                    if (joinedId != null) {
                        enterChannel(joinedId, fromTab = NavTab.Feed)
                    }
                }
            },
            onDismiss = { graph.linkDialogs.dismissInvitePreview() },
        )
    }

    // In-app reporting flow (auth mode). Rendered as a ModalBottomSheet here so it
    // outlives the PostCard that triggered it and survives tab/channel changes while
    // the user is mid-flow. Keyed on (chatId, messageId) so reopening the same post
    // restores progress. Clears on Success (LaunchedEffect inside ReportFlowSheet)
    // or on manual dismiss.
    pendingReport?.let { (chatId, messageId) ->
        ReportFlowSheet(
            chatId = chatId,
            messageId = messageId,
            channelUsername = null,
            onDismiss = { pendingReport = null },
            reportRepository = graph.reportRepository,
            explainerStore = graph.reportExplainerStore,
        )
    }
    }
}
