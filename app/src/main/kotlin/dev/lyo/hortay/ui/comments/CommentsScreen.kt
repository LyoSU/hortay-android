@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.comments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.components.PremiumStatusBadge
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.stableKey
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.ThreadRow
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.flow.map
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.main.rememberFloatingTopBarBehavior
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.media.toAlbumItems
import dev.lyo.hortay.ui.timeline.PostBody
import dev.lyo.hortay.ui.timeline.formatRelative
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import dev.lyo.hortay.ui.timeline.ReactionChip
import dev.lyo.hortay.ui.timeline.ReactionPickerStrip
import dev.lyo.hortay.ui.timeline.label
import dev.lyo.hortay.ui.timeline.symbolName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Disabled-state copy + glyph used when the screen renders without a live thread
 * source — currently the guest-mode "comments unavailable, sign in to read"
 * surface, but any future "comments locked / channel migrated / etc." reason
 * can drop into the same shape. When non-null, the screen short-circuits
 * `repo.observeThread` and renders [CommentsEmptyState] directly under the
 * pinned anchor post; the rest of the shell (top bar, pinned PostCard) is
 * identical to the live-thread path so the user reads it as the same
 * "post detail" surface.
 */
@androidx.compose.runtime.Immutable
data class CommentsDisabledOverride(
    val symbol: String,
    val title: String,
    val body: String,
    /** Optional CTA below the body — used by guest mode to surface "Sign in". */
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * Reddit/Twitter-style discussion overlay with live updates. While this screen is on top
 * we tell TDLib that the linked discussion chat is "open" so view counts register and new
 * messages stream in via the shared updates flow.
 *
 * Predictive back (slide + parallax peek) is owned by the host `NavDisplay` — this screen
 * renders plain and the framework animates it as a scene, so no back-progress plumbing
 * crosses the call boundary.
 *
 * [repo] / [feedRepo] are nullable so the same screen renders in guest (web) mode where
 * there's no TDLib session and therefore no [CommentsRepository] / no
 * [PostsRepository.posts] flow to subscribe to. In that mode the caller passes a
 * [disabledOverride] and the screen renders the pinned anchor + a single empty-state
 * hero explaining the situation, reusing every visual primitive the live path uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: TimelinePost,
    /**
     * Hero-open anchor: the post's ABSOLUTE on-screen Y (positionInWindow) captured in the
     * feed at tap time. On open the list is scrolled so the anchor lands at that same Y, so the
     * post keeps its reading position instead of snapping to the top (see the scroll math in
     * the content body). Can be negative for a long post whose top was above the viewport. The
     * screen is always opaque. `null` = a normal open (deep link) that lands the post at the top.
     */
    heroAnchorY: Int? = null,
    repo: CommentsRepository?,
    /**
     * Live feed source so the pinned anchor PostCard reflects the same reactions,
     * view counts and comment counts the user would see in the feed below. Without
     * this the anchor is rendered from the frozen CommentsKey snapshot
     * (captured at navigation time) — optimistic toggles and incoming
     * `UpdateMessageInteractionInfo` updates go to `PostsRepository._posts` but
     * never flow back to the visible chip on this screen. When the post is no
     * longer in the feed window (deep-link to an evicted post, fresh deep-link
     * before ingest), this falls through and the frozen snapshot stays — the
     * chip just won't animate on tap until the post lands in `_posts`.
     *
     * Null in guest (web) mode: there's no [PostsRepository] there, so live
     * sync simply isn't available — the anchor renders from the frozen
     * snapshot and reaction taps are no-ops via the default `onReactionToggle`.
     */
    feedRepo: PostsRepository?,
    onDismiss: () -> Unit,
    /**
     * When non-null, the screen skips `repo.observeThread` entirely and renders a
     * single [CommentsEmptyState] hero under the pinned anchor post with the
     * supplied copy/glyph. Used by guest mode where the thread source isn't
     * reachable; could be reused for any future "thread isn't available for
     * this post" reason. Passing this with a live [repo] simply suppresses the
     * thread — the override wins.
     */
    disabledOverride: CommentsDisabledOverride? = null,
    onChannelClick: (TimelinePost) -> Unit = {},
    /**
     * Fired when the user taps a non-anonymous author header that resolves to a
     * different chat than the post's host channel (TDLib's foreign
     * [MessageSenderChat] case — admin posting "as one of my other channels", or a
     * comment authored "on behalf of" some chat in the discussion supergroup).
     * Default no-op; MainScaffold wires this to [safelyOpenChannel] so groups /
     * users / private chats surface the kind-keyed snackbar instead of an empty
     * ChannelScreen.
     */
    onAuthorChatClick: (chatId: Long) -> Unit = {},
    /**
     * Fired when the user taps the inline reply quote card on the pinned anchor
     * post. Default no-op preserves the previous behaviour; production wiring in
     * MainScaffold routes this through [safelyOpenChannel] with the replied-to
     * messageId baked into the new ChannelKey so the new screen lands at the target
     * and pulses the highlight there. The feed beneath is never scrolled — same
     * discipline as [TimelineScreen]'s own `onQuotedSourceClick`.
     */
    onQuotedSourceClick: (TimelinePost) -> Unit = {},
    /**
     * Fired when the user taps a reaction chip — either on the pinned anchor post
     * (chatId = channel chat id, messageId = album anchor id) or on a comment
     * (chatId = linked discussion supergroup, messageId = comment id). The caller
     * receives the [Reactions] snapshot the UI just rendered so it can apply an
     * optimistic local toggle through the right repository without a re-read.
     * Default is a no-op so the screen stays self-contained in previews/tests;
     * production wiring lives in [MainScaffold] and routes to either
     * `PostsRepository.applyOptimisticReaction` (anchor) or
     * `CommentsRepository.applyOptimisticReaction` (comment), then to
     * `ChannelActionsRepository.toggleReaction` with revert-on-failure.
     */
    onReactionToggle: (chatId: Long, messageId: Long, snapshot: Reactions, kind: ReactionKind, isChosen: Boolean) -> Unit = { _, _, _, _, _ -> },
    /**
     * Fetch the reactions available to cast on a message (anchor post or comment) so the
     * long-press picker can offer reactions not yet applied. On-demand per-message read.
     * Default returns empty (guest mode / previews / tests show no picker). Production
     * wiring routes to [ChannelActionsRepository.availableReactions].
     */
    fetchAvailableReactions: suspend (chatId: Long, messageId: Long) -> List<ReactionKind> = { _, _ -> emptyList() },
    /**
     * Fired when the user votes on the pinned anchor post's poll. Default no-op so the
     * screen stays self-contained in previews/tests; production wiring lives in
     * [MainScaffold] and routes to `PostsRepository.applyOptimisticPollAnswer` +
     * `ChannelActionsRepository.setPollAnswer` with revert-on-failure. Empty array =
     * retract (regular polls only).
     */
    onPollVote: (chatId: Long, messageId: Long, chosenIndices: IntArray) -> Unit = { _, _, _ -> },
) {
    // Live anchor: track the feed entry whose chat+id matches the post we were
    // opened with so reactions / view count / comment count stay fresh while the
    // user is on this screen. `firstOrNull` keys on the anchor id directly —
    // `PostsRepository` stores the album-coalesced anchor under its first
    // member's id, and the CommentsKey snapshot was minted from the same source, so
    // ids match by construction. `distinctUntilChanged` is implicit via
    // [collectAsStateWithLifecycle] keyed on the post identity; re-keying on
    // post.id keeps a fresh subscription per pushComments(...). When the live
    // lookup misses (post evicted from the 1000-entry window, or never ingested
    // because it's a deep-link target) the frozen snapshot stays so the chip
    // is still drawable.
    val anchorChatId = post.chatId
    val anchorId = post.id
    // Skip the live-anchor subscription when there's no PostsRepository to read
    // from (guest mode). The frozen CommentsKey snapshot is the only truth there;
    // reactions / view counts can't move because the underlying flow doesn't
    // exist, and Telegram's t.me/s/ rendering doesn't report interaction info
    // we could refresh from anyway.
    val liveAnchor: TimelinePost? = if (feedRepo != null) {
        val collected by remember(feedRepo, anchorChatId, anchorId) {
            feedRepo.posts.map { list ->
                list.firstOrNull { it.chatId == anchorChatId && it.id == anchorId }
            }
        }.collectAsStateWithLifecycle(initialValue = null)
        collected
    } else {
        null
    }
    val anchor = liveAnchor ?: post

    // For an album, all sibling ids are candidates — the thread carrier may be any of
    // them. For a standalone post the only candidate is post.id.
    val candidateIds = remember(post.id, post.albumMessageIds) {
        post.albumMessageIds.ifEmpty { listOf(post.id) }
    }
    // Short-circuit the thread subscription when an override is supplied or no
    // repository is available — Loading is the right default in both cases
    // because the `when` arm below picks up the override directly. Keeps the
    // viewport-dwell ack effect dormant for the same reason (no thread to ack
    // against).
    val threadDisabled = disabledOverride != null || repo == null
    val state by remember(post.chatId, candidateIds, threadDisabled, repo) {
        if (threadDisabled || repo == null) {
            kotlinx.coroutines.flow.flowOf(CommentsRepository.ThreadState.Loading)
        } else {
            repo.observeThread(post.chatId, candidateIds)
        }
    }.collectAsStateWithLifecycle(initialValue = CommentsRepository.ThreadState.Loading)

    val viewer = LocalMediaViewer.current
    val pinnedPostInteractions = remember(
        viewer,
        onChannelClick,
        onAuthorChatClick,
        onQuotedSourceClick,
        onReactionToggle,
        fetchAvailableReactions,
        onPollVote,
    ) {
        PostInteractions(
            onMediaClick = { p, idx -> viewer.openFor(p.content, idx) },
            onChannelClick = onChannelClick,
            onAuthorChatClick = onAuthorChatClick,
            // Anchor post (and any tappable inline preview the user gets to via the
            // post-detail screen) — the reply quote card now drills into the original
            // instead of being a dead surface.
            onQuotedSourceClick = onQuotedSourceClick,
            // Album anchor: Telegram pins reaction state to the FIRST message of an
            // album (the carrier the rest of the members link back to). Mirrors the
            // wiring in [TimelineScreen] — keep the two surfaces consistent so a
            // reaction toggled in the feed reads as toggled on the post-detail card
            // and vice versa.
            onReactionToggle = { p, item ->
                val target = p.albumMessageIds.firstOrNull() ?: p.id
                onReactionToggle(p.chatId, target, p.reactions, item.kind, item.isChosen)
            },
            availableReactions = { p ->
                val target = p.albumMessageIds.firstOrNull() ?: p.id
                fetchAvailableReactions(p.chatId, target)
            },
            // Poll votes on the pinned post follow the same pattern as the feed — the
            // album-anchor convention applies, but in practice polls never share a
            // media-group (TDLib only groups photo/video into albums) so the .firstOrNull
            // is just defensive.
            onPollVote = { p, indices ->
                val target = p.albumMessageIds.firstOrNull() ?: p.id
                onPollVote(p.chatId, target, indices)
            },
            pollVotingEnabled = true,
        )
    }

    // Open/close of the thread chat is owned by CommentsRepository.threadFlow now —
    // TDLib needs the chat opened *before* the first GetMessageThreadHistory so updates
    // stream in and the daemon prioritises loading. Doing it here meant we only opened
    // *after* the bootstrap finished, paying the cold-cache penalty on the first call.

    // Pinned color-only — height is owned by the floating-bar nested scroll
    // connection below. Mirrors the [TimelineScreen] pattern so both
    // destination-style bars feel identical to the user.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    // Scroll state preservation is parent-owned: MainScaffold wraps each
    // CommentsScreen mount in
    //   commentsStateHolder.SaveableStateProvider(key = "post:<chatId>:<postId>")
    // so reopening the same post restores the user's scroll position in the thread
    // without any explicit keying here. The rememberLazyListState() participates in
    // that scope automatically via Compose's standard Saver.
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // One-shot guard so the hero scroll runs only on the first open of this entry — not again
    // after the screen leaves & re-enters composition (e.g. a channel pushed on top then
    // popped), which would stomp the user's restored scroll position. rememberSaveable lives in
    // the per-entry SaveableStateProvider scope, so it's true for the rest of this entry's life.
    var heroScrollApplied by rememberSaveable { mutableStateOf(false) }

    // Twitter / Instagram floating-bar pattern — see [TimelineScreen]'s
    // `topBarOffsetPx` block for the full reasoning. Scroll delta directly
    // drives the bar's vertical offset, [Modifier.layout] shrinks the
    // measured height in lockstep so Scaffold's body padding tracks the same
    // signal — no separate animation timeline, no reflow jolt as the user
    // scrolls between the post header and the comment thread below.
    val floatingBar = rememberFloatingTopBarBehavior()
    val topBarFullHeightPx = floatingBar.fullHeightPx
    val topBarOffsetPx = floatingBar.offsetPx
    val topBarNestedScroll = floatingBar.nestedScroll

    // Read-state ack for visible comments. Mirrors the feed's dwell logic, scoped to
    // the comments overlay's discussion-thread chat. Until this existed, comments were
    // never marked as read — the discussion group's lastReadInboxMessageId stayed put
    // and the user kept seeing an unread badge in the official Telegram client even
    // after they'd scrolled through the whole thread here.
    //
    // The comment list is keyed by `it.message.id` (a Long), and the post header /
    // label / loading items are keyed by stable strings — so filtering visibleItemsInfo
    // to entries whose key is a Long isolates real comment rows from chrome.
    //
    // forceRead is left at the CommentsRepository default (false): the discussion chat
    // is already opened by [CommentsRepository.threadFlow]'s withOpenChat for as long
    // as the SharedFlow has subscribers (i.e. for the lifetime of this overlay plus
    // a 30 s linger), and TDLib advances read state automatically for opened chats.
    val ackedRead = remember { HashSet<Long>() }
    val scope = rememberCoroutineScope()
    // Skip the dwell-ack effect when there's no live thread to ack against —
    // guest mode + override branches both fall here. The empty-state body has
    // no comment rows whose ids could land in `visibleItemsInfo` anyway, so
    // the only cost saved is the snapshotFlow subscription itself, but the
    // contract is clearer this way.
    if (!threadDisabled && repo != null) {
        val liveRepo = repo
        LaunchedEffect(listState, liveRepo, post.chatId) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    if (ids.isEmpty()) return@collectLatest
                    delay(COMMENT_READ_DWELL_MS)
                    val ready = state as? CommentsRepository.ThreadState.Ready ?: return@collectLatest
                    val fresh = ids.filter { it !in ackedRead }
                    if (fresh.isEmpty()) return@collectLatest
                    // Populate ackedRead BEFORE dispatching, and detach the suspending ack
                    // into [scope] so a fresh viewport emission cancelling this collector
                    // doesn't take the in-flight call with it. Mirrors TimelineScreen's
                    // read-mark effect — same reasoning applies for the discussion
                    // thread's lastReadInboxMessageId.
                    fresh.forEach { ackedRead.add(it) }
                    scope.launch { liveRepo.viewMessages(ready.threadChatId, fresh) }
                }
        }
    }

    // A hot LRU hit on a previously-opened thread or a fast cold-path resolve
    // (cached anchor + small / empty thread) lands Ready inside ~50-200 ms,
    // helped along by [CommentsRepository.primeCommentsForOpen] which kicks
    // off in parallel with the push. Without a grace window the
    // LoadingIndicator paints for one frame and then unmounts as the empty-
    // state hero takes over — reads as a flicker. [rememberDeferredLoading]
    // gates it on [SCREEN_MOUNT_GRACE_MS] (120 ms) so fast loads paint zero
    // skeleton; only genuinely slow threads cross the threshold and surface
    // feedback. Keyed on (chatId, anchorKey) so navigating to a different
    // post starts a fresh grace window.
    //
    // Suppress the deferred spinner in the disabled / no-repo path —
    // `state` is pinned at Loading there but we never resolve to Ready,
    // so without this gate the spinner would unconditionally land after
    // the grace and sit on top of the empty-state hero forever.
    val showLoadingOverlay = rememberDeferredLoading(
        pending = !threadDisabled && state is CommentsRepository.ThreadState.Loading,
        key = post.chatId to (candidateIds.minOrNull() ?: post.id),
        graceMs = dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS,
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(topBarNestedScroll),
        topBar = {
            // Title is the constant "Допис" / "Post" — Twitter/X pattern for a
            // post-detail screen. The previous "Обговорення" framing assumed the
            // user arrived to discuss, but the dominant entry flow is "tapped a
            // card to read the full thing", with replies as a supporting section
            // below. Subtitle surfaces only when there ARE replies (count via
            // pluralStringResource); Loading / empty / Error keep the chrome
            // calm and let the body's empty-state hero communicate the situation.
            // Seed the count from the post's already-known [TimelinePost.commentCount] so the
            // subtitle is present from the first frame. Deriving it only from the loaded thread
            // (rows.size) meant the subtitle popped in when the thread resolved and reflowed the
            // title — the "Допис" header visibly jumped as the count loaded. Once the thread is
            // Ready the live row count takes over (it's the authoritative figure); until then the
            // header sits at its final position with the cached count already shown.
            val replyCount = (state as? CommentsRepository.ThreadState.Ready)?.rows?.size
                ?: (post.commentCount ?: 0)
            val subtitleText = if (replyCount > 0) {
                pluralStringResource(R.plurals.comments_count, replyCount, replyCount)
            } else {
                null
            }
            // Same two-zone pattern as TimelineScreen — see that screen's
            // topBar comment for the reasoning. Persistent zone-1 strip
            // for status-bar height with the app's background colour, then
            // a layout-shrunk zone-2 holding the bar with `windowInsets =
            // WindowInsets(0)` so its content never travels into the system
            // status-bar area.
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                Spacer(modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                )
                Box(modifier = Modifier
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (topBarFullHeightPx.floatValue == 0f && placeable.height > 0) {
                            topBarFullHeightPx.floatValue = placeable.height.toFloat()
                        }
                        val offset = topBarOffsetPx.floatValue.toInt()
                        val height = (placeable.height + offset).coerceAtLeast(0)
                        layout(placeable.width, height) {
                            placeable.placeRelative(0, offset)
                        }
                    }
                ) {
                    HortayTopBar(
                        title = stringResource(R.string.comments_title),
                        subtitle = subtitleText,
                        size = HortayTopBarSize.Medium,
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Symbol(name = "arrow_back", contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        windowInsets = WindowInsets(0),
                    )
                }
            }
        },
    ) { padding ->
        // Hero-open: land the anchor post where it sat in the feed. [heroAnchorY] is its
        // absolute on-screen Y; the list content starts [topInsetPx] below the screen top
        // (status bar + top app bar), so the scroll needed to bring the anchor's top to
        // heroAnchorY is `topInsetPx - heroAnchorY`. We can only scroll the list DOWN into its
        // content, so a negative target (a short post sitting below the inset) clamps to 0 and
        // the post just opens at the top — no gap, no transparency.
        val heroTarget = if (heroAnchorY != null) {
            (with(density) { padding.calculateTopPadding().roundToPx() } - heroAnchorY).coerceAtLeast(0)
        } else {
            0
        }
        // Apply the scroll while the list is held at alpha 0, then reveal it only after a short
        // motion-scaled grace so the repositioned layout paints in one settled frame instead of
        // strobing the scroll. scrollToItem suspends until applied, but padding / floating-bar
        // measurement and onGloballyPositioned can still settle a frame or two later — revealing
        // the instant the scroll lands let that residual settle read as a "fast scroll" blink.
        // This is the app's "defer the paint a hair" anti-blink pattern — the same
        // [dev.lyo.hortay.data.effectiveSkeletonGrace] / [dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS]
        // budget [rememberDeferredLoading] uses for destination skeletons and the media reveal —
        // applied to a reposition rather than a skeleton. Telegram / Apple bind instantly but
        // defer the first paint so the jump is never seen. Reduced-motion (scale 0) collapses the
        // grace to 0 and reveals immediately: with no transition to hide behind, any delay would
        // just stall feedback.
        val heroPending = heroTarget > 0 && !heroScrollApplied
        if (heroPending) {
            LaunchedEffect(Unit) {
                listState.scrollToItem(0, heroTarget)
                delay(dev.lyo.hortay.data.effectiveSkeletonGrace(dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS))
                heroScrollApplied = true
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (heroPending) 0f else 1f },
        ) {
            item(key = "post") {
                // Render the live feed entry when available so optimistic toggles
                // and server-driven UpdateMessageInteractionInfo updates flow into
                // this card without remounting the screen. Falls back to the
                // frozen CommentsKey snapshot for posts that aren't in the feed
                // window.
                // clickable = false (tapping the anchor would re-open this very screen),
                // but actionsEnabled = true so long-press still surfaces the reaction
                // picker + share/open sheet.
                PostCard(
                    post = anchor,
                    interactions = pinnedPostInteractions,
                    clickable = false,
                    actionsEnabled = true,
                    expanded = true,
                )
            }

            // The previous inline "X replies" / "no comments yet" label has been
            // hoisted: the count travels into the top-bar subtitle (visible only when
            // there ARE replies), and the empty / error branches render a full M3E
            // empty-state hero so a channel without discussion no longer reads as
            // "missing list, no explanation" — same visual idiom as
            // [TimelineScreen.EmptyState], so the two screens speak one vocabulary.
            //
            // Loading branch is gated on [rememberDeferredLoading] so a cached
            // anchor + cached empty rows (or a hot LRU hit on a previously-opened
            // thread) goes Loading → Ready in microseconds without ever flashing
            // the spinner — the empty-state hero just appears under the post. The
            // grace window matches [LOADING_OVERLAY_GRACE_MS] used by media
            // overlays, so the entire app shares one "what counts as a slow load
            // worth surfacing?" threshold.
            // Override short-circuit: same empty-state hero shape as the live
            // disabled/empty branches, just with caller-supplied copy. Wins over
            // the live thread state so callers without a repository never have
            // to construct a stub flow.
            if (disabledOverride != null) {
                val override = disabledOverride
                item(key = "override") {
                    CommentsEmptyState(
                        symbol = override.symbol,
                        title = override.title,
                        body = override.body,
                        actionLabel = override.actionLabel,
                        onAction = override.onAction,
                    )
                }
                return@LazyColumn
            }
            when (val s = state) {
                CommentsRepository.ThreadState.Loading -> if (showLoadingOverlay) {
                    item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                }
                is CommentsRepository.ThreadState.Ready -> if (s.rows.isEmpty()) {
                    item(key = "empty") {
                        CommentsEmptyState(
                            symbol = "forum",
                            title = stringResource(R.string.comments_empty_title),
                            body = stringResource(R.string.comments_empty_body),
                        )
                    }
                } else {
                    items(items = s.rows, key = { it.message.id }) { row ->
                        // [s.threadChatId] is the linked discussion supergroup — the
                        // chat reactions live in for every comment. Captured fresh
                        // per `Ready` emission so a re-resolved anchor (rare, but
                        // possible if TDLib migrates the discussion group) routes
                        // subsequent taps to the new chat without a screen rebuild.
                        var pickerOpen by remember(row.message.id) { mutableStateOf(false) }
                        CommentNode(
                            row = row,
                            onMediaClick = { items, idx -> viewer.open(items, idx) },
                            onReactionTap = { item ->
                                onReactionToggle(
                                    s.threadChatId,
                                    row.message.id,
                                    row.message.reactions,
                                    item.kind,
                                    item.isChosen,
                                )
                            },
                            // Long-press a comment to react with a reaction it doesn't
                            // already carry — same picker the feed/anchor uses.
                            onLongPress = { pickerOpen = true },
                        )
                        if (pickerOpen) {
                            CommentReactionSheet(
                                currentReactions = row.message.reactions,
                                commentText = row.message.content.captionPlain,
                                fetchAvailableReactions = { fetchAvailableReactions(s.threadChatId, row.message.id) },
                                onPick = { kind, alreadyChosen ->
                                    onReactionToggle(
                                        s.threadChatId,
                                        row.message.id,
                                        row.message.reactions,
                                        kind,
                                        alreadyChosen,
                                    )
                                },
                                onDismiss = { pickerOpen = false },
                            )
                        }
                    }
                }
                is CommentsRepository.ThreadState.Error -> item(key = "disabled") {
                    CommentsEmptyState(
                        symbol = "chat_bubble",
                        title = stringResource(R.string.comments_disabled_title),
                        body = stringResource(R.string.comments_disabled_body),
                    )
                }
            }
        }
    }
}

/**
 * Shared empty-state hero for the two terminal branches of [CommentsScreen]:
 * `Ready { rows.isEmpty() }` (discussion is wired but nobody posted yet) and
 * `Error` (channel has no linked discussion group). One composable for both
 * cases — they're the same UX shape, different copy — keeps the visual
 * vocabulary tight: same Flower polygon backdrop as [TimelineScreen.EmptyState]
 * so an empty comments overlay reads as a sibling of an empty feed rather
 * than an unrelated dialog.
 *
 * Sits BELOW the pinned post card in the LazyColumn rather than centring the
 * viewport — the user opened a post first; the post stays the visual anchor
 * and the empty state explains the situation right where comments would have
 * appeared. Generous top/bottom padding makes the hero dominate the empty
 * area below the post without fighting it for attention.
 */
@Composable
private fun CommentsEmptyState(
    symbol: String,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val composeShape = HortayExpressive.EmptyStateMask.asComposeShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, bottom = 64.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 96 dp polygon-masked tile + 40 dp glyph — single largest expressive
        // form on screen at this moment. Matches [TimelineScreen.ExpressiveEmptyHero]'s
        // recipe; kept inline (rather than extracted to a shared composable) so
        // the two screens evolve independently if comments empty state grows
        // its own affordances (e.g. retry on transient error) later.
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, composeShape),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = symbol,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 40.dp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun CommentNode(
    row: ThreadRow,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    onReactionTap: (ReactionItem) -> Unit,
    onLongPress: () -> Unit,
) {
    val indent = (row.depth * INDENT_DP).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (indent > 0.dp) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(indent),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
                )
            }
        }
        CommentBubble(
            row = row,
            onMediaClick = onMediaClick,
            onReactionTap = onReactionTap,
            onLongPress = onLongPress,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Threads-style flat comment: avatar in left rail, header (name + time) and body in right
 * column. No surface fill or rounded corners — just whitespace separates entries. The body
 * and reactions are rendered through the same [PostBody] / [ReactionChip] used by the feed
 * so any media type the timeline shows works here too — including new content types the
 * old comment renderer missed (animated emoji, checklists, expired-media placeholders).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentBubble(
    row: ThreadRow,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    onReactionTap: (ReactionItem) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = row.message
    val opener = dev.lyo.hortay.ui.users.LocalUserProfileOpener.current
    val haptics = LocalHapticFeedback.current
    // Tap surface for the avatar + author name → user-profile sheet. Guarded by
    // senderUserId because comments authored by a chat (channel posting on behalf
    // of the discussion supergroup, rare anonymous-admin case) have no human
    // identity to render — the tap stays inert there instead of opening an empty sheet.
    val userTap: (() -> Unit)? = message.senderUserId?.let { id -> { opener.open(id) } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                // No tap action on a comment — long-press opens the reaction picker.
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(vertical = 10.dp),
    ) {
        TdAvatar(
            name = message.senderName,
            thumb = message.avatarThumb,
            fileId = message.avatarFileId,
            size = 36.dp,
            textStyle = MaterialTheme.typography.titleSmall,
            modifier = if (userTap != null) {
                Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = userTap)
            } else Modifier,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .let { mod ->
                            if (userTap != null) {
                                mod.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = userTap)
                            } else mod
                        },
                )
                if (message.isSenderPremium || message.senderEmojiStatusId != null) {
                    Spacer(Modifier.width(4.dp))
                    PremiumStatusBadge(
                        isPremium = message.isSenderPremium,
                        emojiStatusId = message.senderEmojiStatusId,
                        size = 14.dp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (message.editDate > 0L) {
                    Symbol(
                        name = "edit",
                        contentDescription = stringResource(R.string.post_badge_edited),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 13.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = formatRelative(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Show the quote card ONLY for explicit user-selected quotes. Plain reply
            // relations are structural noise here — every top-level comment is a reply
            // to the host post, and every nested reply already sits visually under its
            // parent comment via the thread indentation. Surfacing a quote card for those
            // would just duplicate context the layout already conveys.
            message.reply?.takeIf { it.isQuote }?.let {
                Spacer(Modifier.height(6.dp))
                ReplyBlock(it)
            }

            Spacer(Modifier.height(6.dp))
            PostBody(
                content = message.content,
                onMediaClick = { _, idx ->
                    message.content.toAlbumItems()?.let { items -> onMediaClick(items, idx) }
                },
                expanded = true,
            )

            if (message.reactions.items.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Key by reaction-bucket identity, not slot — see [ReactionChip] call
                    // site in PostCard for why a positional loop renders the wrong custom
                    // emoji when TDLib re-ranks the buckets mid-read.
                    message.reactions.items.forEachIndexed { idx, item ->
                        key(item.kind.stableKey) {
                            if (idx > 0) Spacer(Modifier.width(6.dp))
                            ReactionChip(item, onClick = { onReactionTap(item) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Long-press reaction picker for a single comment. Mirrors the strip at the top of the
 * feed's post action sheet ([dev.lyo.hortay.ui.timeline.ReactionPickerStrip]); reuses the
 * same component so feed and thread reactions look and behave identically. While the
 * available reactions load the sheet shows a spinner; if the chat exposes none it
 * dismisses itself rather than sitting empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentReactionSheet(
    currentReactions: Reactions,
    commentText: String,
    fetchAvailableReactions: suspend () -> List<ReactionKind>,
    onPick: (kind: ReactionKind, alreadyChosen: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val canCopy = commentText.isNotBlank()
    var available by remember { mutableStateOf<List<ReactionKind>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        available = fetchAvailableReactions()
        loaded = true
    }
    // Nothing to offer at all (no reactions AND no text) → don't leave an empty sheet.
    LaunchedEffect(loaded, available) {
        if (loaded && available.isEmpty() && !canCopy) onDismiss()
    }
    val chosenKeys = remember(currentReactions) {
        currentReactions.items.filter { it.isChosen }.map { it.kind.stableKey }.toSet()
    }
    fun dismissAnimated() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            if (!loaded) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            } else {
                if (available.isNotEmpty()) {
                    ReactionPickerStrip(
                        reactions = available,
                        chosenKeys = chosenKeys,
                        onPick = { kind ->
                            val already = kind.stableKey in chosenKeys
                            haptics.performHapticFeedback(
                                if (already) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            onPick(kind, already)
                            dismissAnimated()
                        },
                    )
                }
                if (canCopy) {
                    if (available.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("comment", commentText))
                                dismissAnimated()
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol(name = "content_copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp)
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = stringResource(R.string.post_copy_text),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Comments-side reply preview. Visually mirrors PostCard's ReplyBlock (tinted bg,
 * accent bar, trailing reply glyph) so feed and thread surfaces read as one design
 * family — see PostCard.ReplyBlock KDoc for the colour / shape rationale.
 *
 * Sized a touch tighter than the feed variant: `labelSmall` author, 36 dp thumb,
 * 6 dp vertical padding. Comments are physically smaller cards than feed posts,
 * so the reply chip scales with them.
 */
@Composable
private fun ReplyBlock(reply: ReplyPreview) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = 8.dp,
                        end = if (reply.mediaThumb == null) 24.dp else 6.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
            ) {
                Text(
                    text = reply.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val bodyText = reply.excerpt.ifBlank { reply.mediaKind.label() }
                if (bodyText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (reply.excerpt.isBlank()) {
                            reply.mediaKind.symbolName()?.let { name ->
                                Symbol(
                                    name = name,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 12.dp,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            reply.mediaThumb?.let { thumb ->
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    TdMediaImage(
                        media = thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (reply.mediaThumb == null) {
            Symbol(
                name = "reply",
                tint = accent.copy(alpha = 0.55f),
                size = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 6.dp),
            )
        }
    }
}

// Label/symbol mapping + relative timestamps are shared with the feed via
// dev.lyo.hortay.ui.timeline — see ReplyKindResources.kt and TimeFormat.kt.

private const val INDENT_DP = 12

/**
 * Viewport-stable dwell before a visible comment is considered "read" and acked via
 * `viewMessages`. Same 500 ms threshold as the feed's read-mark dwell — comments scroll
 * in the same UX shape, so any other value would make the two screens feel
 * inconsistent. With the discussion-thread chat already opened by
 * [CommentsRepository.threadFlow], the ack only needs `force_read=false`; TDLib
 * advances the thread's `lastReadInboxMessageId` automatically once the message is
 * "viewed in an opened chat" (see tdlib/td#46).
 */
private const val COMMENT_READ_DWELL_MS = 500L
