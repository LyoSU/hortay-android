@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlin.coroutines.cancellation.CancellationException
import android.util.Log
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChatInvitePreview
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.InviteLinkKind
import dev.lyo.hortay.data.NavEntry
import dev.lyo.hortay.data.PublicHandleKind
import dev.lyo.hortay.data.PublicHandleResult
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.report.ReportTarget
import dev.lyo.hortay.ui.channels.ChannelsScreen
import dev.lyo.hortay.ui.comments.CommentsScreen
import dev.lyo.hortay.ui.settings.SettingsScreen
import dev.lyo.hortay.ui.report.ReportFlowSheet
import dev.lyo.hortay.ui.text.ChatInvitePreviewDialog
import dev.lyo.hortay.ui.timeline.ChannelScreen
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.timeline.TimelineScreen
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
 * Top-level container that owns nav-tab state, the global channel filter and the comments
 * overlay, then dispatches the four primary surfaces.
 */
@Composable
fun MainScaffold(graph: AppGraph) {
    // Navigation state — plain `remember`, deliberately NOT `rememberSaveable`. Tab
    // selection and the channel back-stack reset to defaults on every fresh Activity
    // create (cold launch, swipe-from-recents, memory-pressure restart), so opening
    // the app always lands on the Feed top. The previous saveable form caused a
    // recurring UX complaint: closing on the Saved tab (or several channels deep)
    // reopened the app exactly there, even after an overnight gap. Twitter / Telegram
    // / Instagram all reset their top-level navigation on cold launch — restoring
    // multi-hour-old navigation reads as the app teleporting the user somewhere
    // stale.
    //
    // Trade-off: rotation also resets navigation. Hortay is portrait-default with
    // no landscape-specific layout, so the practical cost is near-zero. Scroll
    // positions inside individual screens stay `rememberSaveable` (via the parent
    // `SaveableStateProvider` chain) so configuration changes and memory-pressure
    // recoveries within a session preserve in-screen state — only the top-level
    // route resets.
    //
    // Unified polymorphic nav-stack on [AppGraph.nav]. Each push is a new layer
    // (no dedup on repeated chatIds) — permits unlimited nesting in the
    // Telegram-Android pattern: channel → comments → channel → comments → …
    //
    // Replaces the three previously-disjoint nav states that lived here:
    //   1. `channelStack: List<Long>`        — channel-drill overlay
    //   2. `commentsForPost` + `pendingCommentsKey` — comments overlay
    //   3. `pendingScrollTarget`             — deep-link scroll target
    //
    // Top entry receives back gestures + predictive back. Each entry has its
    // own stable [NavEntry.entryId] (UUID), used as the key for the per-entry
    // [SaveableStateProvider] and `viewModel(key)` so each push is an isolated
    // screen instance with its own scroll position and ViewModel — pushing the
    // same channel twice produces two independent screens. This is also the
    // fix for the per-chatId ViewModelStore leak that previously accumulated
    // VMs across the Activity lifetime.
    //
    // Single owner of the "are we currently overlaying something" answer.
    // Tab-reset back-handler / Home-tap re-route both gate on `stack.isEmpty()`.
    var selectedTab by remember { mutableStateOf(NavTab.Feed) }
    val stack by graph.nav.stack.collectAsStateWithLifecycle()
    val topEntry = stack.lastOrNull()

    // Nav helpers route through [AppGraph.nav]. The active tab is NOT
    // touched on push — under the nav-overlay the user's originating tab
    // (Channels, Saved, …) keeps rendering, so a predictive-back swipe
    // reveals the right content underneath. Pop just removes the top
    // overlay layer; tab restoration is automatic because we never moved
    // away from it.
    fun pushChannel(chatId: Long, scrollTo: Long? = null) {
        graph.nav.push(NavEntry.Channel(chatId = chatId, scrollToMessageId = scrollTo))
    }

    fun pushComments(post: TimelinePost) {
        graph.nav.push(NavEntry.Comments(anchor = post))
    }

    fun popNav() {
        graph.nav.pop()
    }

    fun clearNav() {
        graph.nav.clear()
    }
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
                // that pushes a Channel entry onto the nav stack".
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
                        // broadcast channel). Without the gate we would push a chatId
                        // onto the nav stack that loadChannelHistory short-circuits on,
                        // leaving an empty skeleton with no error path.
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
                        // Hortay. Without the gate, the nav stack would land on a chat
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
                                pushChannel(preview.chatId)
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
                // Deep-link to a specific channel: push a Channel entry — Back
                // returns the user to whichever tab they were on, not a forced
                // reset. The optional message anchor rides
                // [NavEntry.Channel.scrollToMessageId] so the channel screen
                // lands on the linked post on first frame.
                pushChannel(targetChat, scrollTo = tdMessageId)
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Log.w("MainScaffold", "deep-link dispatch failed for $link", t)
            }
        }
    }

    // Pending report: (chatId, messageId, token). Hoisted off local state onto
    // [AppGraph.reportDialogs] so a rotation mid-flow doesn't drop the sheet on
    // the floor — `ReportFlowViewModel` keeps partial answers across TDLib
    // roundtrips, and a re-created composition starting with null target would
    // erase the user's progress visibly. See [dev.lyo.hortay.data.report.ReportDialogState]
    // for the lifetime contract (rotation-safe, logout-cleared, NOT process-death-safe).
    val pendingReport by graph.reportDialogs.target.collectAsStateWithLifecycle()
    fun openReport(chatId: Long, messageId: Long?) {
        graph.reportDialogs.open(ReportTarget(chatId, messageId, System.nanoTime()))
    }

    // Single predictive-back handler for the top nav-entry. Translates,
    // scales, fades the visible top layer under the user's finger; edge
    // (LEFT vs RIGHT) is forwarded because users can bind back to either
    // edge — a hard-coded translateX direction would invert the motion on
    // right-handed setups. Motion springs captured here in @Composable scope
    // (`progress.collect` is a plain coroutine; `MaterialTheme` reads require
    // composable context) — same M3E `fastEffectsSpec` the tab AnimatedContent
    // rides, so the predictive-back commit / rewind shares physics with the
    // rest of the chrome instead of riding a one-off duration-tween.
    //
    // Replaces the previous TWO handlers (comments + channel separately).
    // With a single polymorphic stack, the top entry — whichever variant —
    // is what back gestures act on; the layer underneath (next-top) animates
    // in naturally via the overlay AnimatedContent below.
    val backCommitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val backRewindSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val navBackProgress = remember { Animatable(0f) }
    var navBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = topEntry != null) { progress ->
        try {
            progress.collect { event ->
                navBackEdge = event.swipeEdge
                // System emits at pointer-move rate (60–120 Hz). Epsilon-skip
                // sub-pixel deltas so we don't pay an Animatable snapshot write
                // (and the resulting graphicsLayer re-evaluation in the top
                // entry's content) for invisible motion. 0.005f ≈ half a pixel
                // on a 1080px-wide screen at translation 0.1 — below perceptual
                // threshold.
                val next = event.progress
                if (kotlin.math.abs(next - navBackProgress.value) >= 0.005f) {
                    navBackProgress.snapTo(next)
                }
            }
            // Commit: extend past peek (1f) to full exit (2f) so the top
            // layer continues translating off-screen and fades to zero alpha
            // before we drop it from composition. Without this leg the
            // overlay would freeze at peek (~70% visible) for the duration
            // of the commit animation and then snap away — janky on flagship.
            navBackProgress.animateTo(EXIT_PROGRESS, backCommitSpec)
            popNav()
            navBackProgress.snapTo(0f)
        } catch (_: CancellationException) {
            navBackProgress.animateTo(0f, backRewindSpec)
        }
    }
    // Stack empty + not on Feed: return to Feed tab. Plain BackHandler — no
    // overlay to animate at this point.
    BackHandler(enabled = topEntry == null && selectedTab != NavTab.Feed) {
        selectedTab = NavTab.Feed
    }

    // SaveableStateHolders must live in MainScaffold's @Composable body, NOT inside
    // the Scaffold content lambda. The Scaffold body owns the tab AnimatedContent;
    // the nav-overlay sits OUTSIDE that lambda (above the tab chrome and
    // FloatingNavBar). Declaring the holder inside the Scaffold lambda would put it
    // out of scope for the overlay's SaveableStateProvider call. One declaration at
    // this level lets both call-sites capture the same reference.
    //
    // [navStateHolder] keys per-NavEntry by its stable UUID `entryId`. Each push
    // — channel or comments — gets its own SaveableStateProvider scope, so
    // pushing the same channel twice (legitimate in unlimited-nesting flows)
    // produces two independent screens with their own scroll positions.
    val tabStateHolder = rememberSaveableStateHolder()
    val navStateHolder = rememberSaveableStateHolder()

    // Wrap the entire content tree with the in-app UriHandler. Every descendant call
    // — LinkAnnotation.Url taps in post bodies, WebPreviewCard opens, AddChannelSheet
    // affordances, settings author rows — goes through this handler, which checks each
    // URL against the Telegram link resolver before falling back to the OS. One
    // interceptor wired here is cheaper than wrapping every Text call-site individually
    // and guarantees no path leaks straight to ACTION_VIEW.
    // Live cursor holder collected once, mutated in place via diff-apply so
    // per-key Compose snapshot subscribers (PostCard, ↓N counter, boundary
    // derivedStateOf) are invalidated only when their own chat's cursor
    // changes. The previous `collectAsStateWithLifecycle()` over a
    // PersistentMap-typed flow swapped a fresh map identity into the
    // `staticCompositionLocalOf<ReadCursors>` on every put — which
    // invalidated the entire CompositionLocalProvider subtree (including
    // the feed LazyColumn) for every dwell-ack and external read sync,
    // producing the per-frame jank the user reported during scroll.
    val cursorHolder =
        dev.lyo.hortay.ui.timeline.rememberCursorHolder(graph.postsRepository.chatReadCursors)
    val feedOrder by graph.settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst,
    )
    val snapScroll by graph.settingsStore.snapScroll.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val inlineVideoAutoplay by graph.settingsStore.inlineVideoAutoplay.collectAsStateWithLifecycle(
        initialValue = true,
    )
    // Mode-agnostic read-state ack handed to TimelineScreen / ChannelScreen. TDLib
    // mode groups the dwell-batch by chatId and bridges to viewMessages(forceRead=true)
    // — the canonical TDLib path that advances `lastReadInboxMessageId` server-side
    // and surfaces the read through to the official Telegram client.
    //
    // `remember`-wrapped on the stable AppGraph identity so the lambda referenced
    // by `markAsRead` keeps the same instance across MainScaffold recompositions.
    // Without this, every recomposition allocates a fresh closure, breaking
    // skippability of TimelineScreen's `interactions = remember(...)` and
    // `ackedRead = remember(markAsRead)` blocks — which would trigger redundant
    // `viewMessages` RPCs on every dwell-batch evaluation.
    val tdlibMarkAsRead: suspend (List<TimelinePost>) -> Unit = remember(graph) {
        { batch ->
            batch.groupBy { it.chatId }.forEach { (chatId, group) ->
                // Expand each post to every album-member id so TDLib advances
                // lastReadInboxMessageId past the LAST member, not just the
                // anchor (anchor = lowest id, so an album-aware comparison in
                // isUnreadIn would otherwise re-light the card as unread until
                // the cursor crossed every member). Solo posts contribute
                // their own id via the ifEmpty fallback.
                val ids = group.flatMap { post ->
                    post.albumMessageIds.ifEmpty { listOf(post.id) }
                }.distinct()
                graph.postsRepository.viewMessages(chatId, ids)
            }
        }
    }
    // Same stability concern as [tdlibMarkAsRead]: TimelineScreen / ChannelScreen
    // hold `onReportClick` and `canReport` as parameters that feed into
    // `interactions = remember(...)`. Fresh lambdas per recomposition would invalidate
    // that remember block and propagate unstable callbacks down to PostCard.
    val onPostReportClick = remember(graph) {
        { post: TimelinePost ->
            graph.reportDialogs.open(
                ReportTarget(
                    post.chatId,
                    if (post.id != 0L) post.id else null,
                    System.nanoTime(),
                ),
            )
        }
    }
    val canReportPost = remember { { post: TimelinePost -> post.canReportChat } }
    LinkAwareScaffold(graph) {
    CompositionLocalProvider(
        LocalReadCursors provides cursorHolder,
        dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay provides inlineVideoAutoplay,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        bottomBar = {
            // Hide the nav-bar while a nav-overlay is visible (Telegram /
            // Twitter / Instagram all do this for drilled-in screens — the
            // overlay owns the bottom edge so the last row of content isn't
            // occluded). Animated through M3E motion springs:
            // height-shrinks + fades so the surrounding Scaffold content
            // padding eases instead of snapping.
            androidx.compose.animation.AnimatedVisibility(
                visible = topEntry == null,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
            ) {
            FloatingNavBar(
                selected = selectedTab,
                onSelect = { tab ->
                    // Three distinct cases when the user taps the Home pill while
                    // selectedTab is already Feed. Telegram-Android / Twitter / X all
                    // settle on the same rule, surfaced explicitly here:
                    //  (a) User on Feed AND re-tapping the active Home tab.
                    //      Canonical "tap home twice" gesture: bump homeTapTrigger
                    //      so TimelineScreen scrolls to top (or refreshes if already
                    //      there).
                    //  (b) User on a different tab. Just switch tabs.
                    // (Home-tap-while-drilled is unreachable here because the
                    // nav-bar is hidden in that state — see the early return
                    // above.)
                    val reselectingActiveFeed =
                        tab == NavTab.Feed && tab == selectedTab
                    if (reselectingActiveFeed) homeTapTrigger = System.nanoTime()
                    selectedTab = tab
                },
            )
            }
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
                            onChannelOpen = { id -> pushChannel(id) },
                            onOpenComments = { post -> pushComments(post) },
                            homeTapTrigger = homeTapTrigger,
                            onBrandTap = { homeTapTrigger = System.nanoTime() },
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
                            coveredByOverlay = stack.isNotEmpty(),
                        )
                    }
                }
                NavTab.Channels -> ChannelsScreen(
                    repo = graph.postsRepository,
                    contentPadding = padding,
                    onChannelClick = { chatId ->
                        pushChannel(chatId)
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
                        // Tapping a channel from Saved pushes a Channel entry — Back
                        // returns the user to Saved instead of always resetting to
                        // all-feed.
                        pushChannel(id)
                    },
                    onOpenComments = { post -> pushComments(post) },
                    homeTapTrigger = 0L,
                    onBrandTap = {},
                    startupPhase = graph.startupCoordinator.phase,
                    onReportClick = onPostReportClick,
                    canReport = canReportPost,
                    markAsRead = tdlibMarkAsRead,
                    feedOrder = feedOrder,
                    snapScroll = snapScroll,
                    coveredByOverlay = stack.isNotEmpty(),
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

        // Top-2 entries rendered as stacked layers above the always-mounted
        // feed. Why TWO and not just the top: during a predictive-back swipe
        // the user expects to see the layer underneath peeking through — a
        // single-slot top render would reveal the static feed underneath
        // instead of the next-below stack entry (Channel underneath Comments,
        // or vice-versa). Telegram / Twitter / Instagram all behave this way.
        // The extra cost is one screen composition; the bottom entry stays
        // mounted across the swipe so its scroll position and ViewModel are
        // untouched.
        //
        // No push/pop slide animation: M3 Expressive favours immediacy for
        // detail-screen pushes (TG-Android and Twitter both render new
        // detail screens in place). The only motion that matters here is
        // the predictive-back transform on the current top, which the
        // graphicsLayer drives via [navBackProgress].
        @Composable
        fun RenderNavEntry(entry: NavEntry, isCurrent: Boolean) {
            when (entry) {
                is NavEntry.Channel -> ChannelScreen(
                    chatId = entry.chatId,
                    repo = graph.postsRepository,
                    commentsRepo = graph.commentsRepository,
                    bookmarks = graph.bookmarkStore,
                    translations = graph.translations,
                    channelActions = graph.channelActions,
                    contentPadding = padding,
                    onBack = ::popNav,
                    onChannelOpen = { cid -> pushChannel(cid) },
                    onOpenComments = { post -> pushComments(post) },
                    scrollToMessage = entry.scrollToMessageId
                        ?.let { entry.chatId to it }
                        ?.takeIf { isCurrent },
                    onScrollHandled = {},
                    onScrollMissed = {
                        graph.userMessages.post(
                            res.getString(R.string.link_not_found),
                            UserMessageBus.Severity.Info,
                        )
                    },
                    onReportClick = onPostReportClick,
                    canReport = canReportPost,
                    onReportChannel = { openReport(entry.chatId, null) },
                    feedOrder = feedOrder,
                    startupPhase = graph.startupCoordinator.phase,
                )
                is NavEntry.Comments -> CommentsScreen(
                    post = entry.anchor,
                    repo = graph.commentsRepository,
                    feedRepo = graph.postsRepository,
                    onDismiss = ::popNav,
                    onChannelClick = { p -> pushChannel(p.chatId) },
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
                    backProgress = if (isCurrent) navBackProgress.value else 0f,
                    backSwipeEdge = navBackEdge,
                )
                // Guest-mode variant — never composed here in practice
                // (MainActivity routes WebChannel into WebModeScaffold), but
                // making the `when` exhaustive over the sealed hierarchy keeps
                // the compiler honest if the routing rule ever changes.
                is NavEntry.WebChannel -> Unit
            }
        }

        // Render visible entries in ONE forEach so each entry has a single
        // stable position in the composition tree. After a pop, the entry
        // that was at index 0 (the "below" layer) is still at index 0 in
        // the new takeLast(2) result — Compose's position+key identity
        // matching preserves its remember-group across the transition:
        // ViewModelStore stays the same instance, saveable state stays
        // bound, no remount-flicker on comment counts or list rows.
        //
        // The opposite — rendering "below" and "top" in two separate
        // top-level branches — created two distinct composition positions,
        // and an entry migrating between them looked like an unmount +
        // remount to Compose.
        val visibleEntries = stack.takeLast(2)
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
                                            val p = navBackProgress.value.coerceIn(0f, 2f)
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
                            RenderNavEntry(entry = entry, isCurrent = isTop)
                        }
                    }
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

    pendingInvitePreview?.let { preview ->
        ChatInvitePreviewDialog(
            preview = preview,
            onConfirm = {
                graph.linkDialogs.dismissInvitePreview()
                scope.launch {
                    val joinedId = graph.channelActions.joinByInvite(preview.inviteLink)
                    if (joinedId != null) {
                        pushChannel(joinedId)
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
    pendingReport?.let { target ->
        ReportFlowSheet(
            chatId = target.chatId,
            messageId = target.messageId,
            openToken = target.token,
            channelUsername = null,
            onDismiss = { success ->
                graph.reportDialogs.close()
                // Surface a confirmation snackbar via the existing UserMessageBus
                // (Severity.Info, not Error — the report succeeded). The bus is
                // already wired to the scaffold's SnackbarHost; manual dismissals
                // skip this path so the user only sees feedback when something
                // actually happened.
                if (success) {
                    graph.userMessages.post(
                        res.getString(R.string.report_success),
                        UserMessageBus.Severity.Info,
                    )
                }
            },
            reportRepository = graph.reportRepository,
            explainerStore = graph.reportExplainerStore,
        )
    }
    }
    }
}
