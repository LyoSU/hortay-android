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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.channels.ChannelsScreen
import dev.lyo.hortay.ui.comments.CommentsScreen
import dev.lyo.hortay.ui.settings.SettingsScreen
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
 * Top-level container that owns nav-tab state, the global channel filter and the comments
 * overlay, then dispatches the four primary surfaces.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.Feed) }
    var channelFilter by rememberSaveable { mutableStateOf<Long?>(null) }
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

    // Deep-link dispatcher. tg:// and https://t.me/... arrivals come through the router
    // as already-parsed [DeepLink] events; we resolve handles to chat ids on demand and
    // switch the navigation state. Any failure (unresolvable handle, missing subscription)
    // is silently dropped — better than a crash on a user-tapped wild link.
    LaunchedEffect(Unit) {
        graph.deepLinkRouter.events.collect { link ->
            val targetChat: Long?
            val serverPostId: Long?
            when (link) {
                is DeepLink.PublicChannel -> {
                    targetChat = graph.postsRepository.resolvePublicChat(link.handle)
                    serverPostId = link.serverPostId
                }
                is DeepLink.PrivateChannel -> {
                    targetChat = link.chatId
                    serverPostId = link.serverPostId
                }
                is DeepLink.Message -> {
                    targetChat = link.chatId
                    serverPostId = link.serverPostId
                }
            }
            if (targetChat != null) {
                channelFilter = targetChat
                selectedTab = NavTab.Feed
                openComments(null)
                if (serverPostId != null) {
                    // TDLib message ids are server post number << 20. The deep-link parser
                    // already normalises to the server number, so we shift here once
                    // before handing the target to TimelineScreen.
                    pendingScrollTarget = targetChat to (serverPostId shl 20)
                }
            }
        }
    }

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
    // Back priority: clear channel filter → return to Feed → system close. These are
    // intra-surface state changes (no z-stacked screen above), so a non-progress
    // BackHandler is the correct primitive — a PredictiveBackHandler here would just
    // throw away the progress and add noise.
    BackHandler(enabled = commentsForPost == null && channelFilter != null) { channelFilter = null }
    BackHandler(enabled = commentsForPost == null && channelFilter == null && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

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
        // Tab swap = pure crossfade. fastEffectsSpec is M3E's correct channel for
        // non-spatial state changes; on the same spring the FloatingNavBar's
        // selection container/colour/icon-fill morph runs, so the bottom-nav
        // morph and the content crossfade land together (no out-of-sync blink).
        val tabEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn(tabEffectsSpec) togetherWith fadeOut(tabEffectsSpec) },
            label = "tab-switch",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                NavTab.Feed -> TimelineScreen(
                    feed = graph.postsRepository,
                    tdlibRepo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    folders = graph.chatFoldersRepository,
                    translations = graph.translations,
                    channelActions = graph.channelActions,
                    bookmarks = graph.bookmarkStore,
                    contentPadding = padding,
                    showOnlyBookmarked = false,
                    channelFilter = channelFilter,
                    onChannelFilterChange = { channelFilter = it },
                    onOpenComments = openComments,
                    homeTapTrigger = homeTapTrigger,
                    onBrandTap = { homeTapTrigger = System.nanoTime() },
                    scrollToMessage = pendingScrollTarget,
                    onScrollHandled = { pendingScrollTarget = null },
                    startupPhase = graph.startupCoordinator.phase,
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
                    feed = graph.postsRepository,
                    tdlibRepo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    folders = graph.chatFoldersRepository,
                    translations = graph.translations,
                    channelActions = graph.channelActions,
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
                    onOpenComments = openComments,
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                    startupPhase = graph.startupCoordinator.phase,
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
        CommentsScreen(
            post = post,
            repo = graph.commentsRepository,
            onDismiss = { openComments(null) },
            onChannelClick = { p ->
                channelFilter = p.chatId
                selectedTab = NavTab.Feed
                openComments(null)
            },
            backProgress = commentsBackProgress.value,
            backSwipeEdge = commentsBackEdge,
        )
    }

}
