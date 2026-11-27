package dev.lyo.hortay.ui.channels

import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import dev.lyo.hortay.ui.theme.LocalHortayDarkTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import dev.lyo.hortay.ui.timeline.READING_WIDTH_CAP
import dev.lyo.hortay.ui.theme.CHROME_MAX_FONT_SCALE
import dev.lyo.hortay.ui.theme.HortaySpacing
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.lyo.hortay.ui.discover.DiscoverSearchField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.ui.main.openTelegramApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.subscriptions.TransferMode
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.timeline.LocalReadCursors
import dev.lyo.hortay.ui.timeline.formatRelative
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch
import androidx.compose.ui.text.intl.Locale

/**
 * List of channels the user is subscribed to. Data is derived from the same feed as the
 * timeline (each unique chat is a channel), so it stays in sync without a second TDLib query.
 *
 * Rows render through [SegmentedListItem] with [ListItemDefaults.segmentedShapes] — first/last
 * rows get a larger outer radius, inner rows pinch to a tighter corner, and the column gap
 * (`ListItemDefaults.SegmentedGap`) is the spacing that the shape math expects. Mixing this
 * with custom `Arrangement.spacedBy(8.dp)` would visually drift away from the Material 3
 * Expressive segmented-list metric.
 *
 * Reading metadata (WS-F):
 * - **Recency** — relative time of the newest post in [formatRelative], read straight off the
 *   feed slice ([ChannelSummary.lastPostDate]). No extra query.
 * - **Unread** — derived honestly from [LocalReadCursors]: a channel is unread when its newest
 *   (album-aware) post id sits above the chat's read cursor — exactly the rule PostCard's unread
 *   strip uses ([TimelinePost.isUnreadAt]). This yields an unread **dot**, not a numeric badge:
 *   the cold-start slice carries ~1 post per channel, so the UI can only prove a channel HAS
 *   unread (`id > cursor`), never how many — a real count would need TDLib `Chat.unreadCount`
 *   exposed through the repository layer (same constraint for a muted-state glyph: mute state
 *   is only reachable via imperative `GetChat`, which the no-TDLib-from-UI rule forbids here).
 * - **Hidden** — [AppGraph.ignoredChannels] is the live, reactive set of channels hidden from
 *   the merged feed. This screen reads [PostsRepository.posts] (the RAW slice, not the
 *   ignored-filtered [PostsRepository.subscribedPosts]), so hidden channels DO appear here and
 *   would otherwise be indistinguishable; the trailing `visibility_off` toggle marks them and
 *   flips the state optimistically (the DataStore-backed flow re-emits immediately).
 * - **Read without joining** — followed channels are unioned in from the followed store
 *   ([channelSummaries]), so one with no post in the slice (just followed, Unavailable) still
 *   has a row. Their unread badge counts slice posts above the local cursor — Telegram keeps
 *   no unread count for a non-member — and they sit under the "Not joined" chip. A long-press
 *   offers Join and Stop reading; the subtitle changes only for an Unavailable channel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsScreen(
    graph: AppGraph,
    repo: PostsRepository,
    contentPadding: PaddingValues,
    onChannelClick: (chatId: Long) -> Unit,
) {
    val posts by repo.posts.collectAsStateWithLifecycle()
    val followed by graph.followedChannelsStore.channels.collectAsStateWithLifecycle()
    val followUi = remember(graph) { graph.followedChannelsUi() }
    val follow = rememberFollowController(followUi)
    val baseChannels = remember(posts, followed) { channelSummaries(posts, followed) }
    // A followed channel with no post in the slice has no avatar source in the feed; its
    // photo comes from TDLib's local chat object instead (no request).
    val photoless = remember(baseChannels) {
        baseChannels.filter { it.isFollowed && it.avatarFileId == null && it.avatarThumb == null }.map { it.chatId }
    }
    val followedPhotos by produceState(emptyMap<Long, Pair<Int?, ByteArray?>>(), photoless) {
        value = photoless.mapNotNull { id -> graph.channelDirectory.chatAvatar(id)?.let { id to it } }.toMap()
    }
    val channels = remember(baseChannels, followedPhotos) {
        if (followedPhotos.isEmpty()) {
            baseChannels
        } else {
            baseChannels.map { ch ->
                followedPhotos[ch.chatId]?.let { (fileId, thumb) -> ch.copy(avatarFileId = fileId, avatarThumb = thumb) } ?: ch
            }
        }
    }
    val unreadCounts by repo.chatUnreadCounts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChannelsFilter.All) }
    val hiddenChatIds by graph.ignoredChannels.ignored.collectAsStateWithLifecycle(
        initialValue = persistentSetOf(),
    )
    val scope = rememberCoroutineScope()
    // Default true while loading so the hint never flashes for users who already dismissed it.
    val swipeHintSeen by graph.onboardingStore.channelsSwipeHintSeen.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val cursors = LocalReadCursors.current
    // One pass decides which filter each channel falls under; the chips show the counts.
    // derivedStateOf: read cursors live in a snapshot map, so the Unread bucket follows reading.
    val buckets by remember(channels, hiddenChatIds, unreadCounts, cursors) {
        derivedStateOf {
            val dayAgo = System.currentTimeMillis() - DAY_MS
            ChannelsFilter.entries.associateWith { f ->
                channels.filter { ch ->
                    when (f) {
                        ChannelsFilter.All -> true
                        ChannelsFilter.Unread -> if (ch.isFollowed) {
                            followedUnreadCount(ch, cursors[ch.chatId]) > 0
                        } else {
                            (unreadCounts[ch.chatId] ?: 0) > 0 ||
                                (ch.lastPostId > 0L && ch.lastPostId.isUnreadAgainst(cursors[ch.chatId]))
                        }
                        ChannelsFilter.Today -> ch.lastPostDate >= dayAgo
                        ChannelsFilter.Hidden -> ch.chatId in hiddenChatIds
                        ChannelsFilter.NotJoined -> ch.isFollowed
                    }
                }
            }
        }
    }
    // A filter that emptied out (last hidden channel shown again) falls back to All.
    val activeFilter = if (filter != ChannelsFilter.All && buckets[filter].isNullOrEmpty()) ChannelsFilter.All else filter
    // Persist the fallback, but ONLY on an observed non-empty → empty transition of the
    // selected bucket. Left in [filter], a bucket the user emptied (last hidden channel
    // shown again) silently re-activated the moment a new matching post arrived. A bucket
    // that is empty from the first frame is different: `filter` is saveable while posts,
    // unread counts and the hidden set start empty (initialValue), so resetting there
    // would wipe the user's choice before data lands — leave `filter` alone and let
    // [activeFilter] show All meanwhile.
    var seenNonEmptyFilter by remember { mutableStateOf<ChannelsFilter?>(null) }
    val selectedBucketEmpty = buckets[filter].isNullOrEmpty()
    LaunchedEffect(filter, selectedBucketEmpty) {
        when {
            !selectedBucketEmpty -> seenNonEmptyFilter = filter
            filter != ChannelsFilter.All && seenNonEmptyFilter == filter -> {
                seenNonEmptyFilter = null
                filter = ChannelsFilter.All
            }
        }
    }
    val shown = remember(buckets, activeFilter, query) {
        val base = buckets[activeFilter].orEmpty()
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.title.contains(q, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    // One "away from the top" signal drives both the header tint and the FAB collapse.
    val scrolledAway by remember { derivedStateOf { listState.canScrollBackward } }
    // On a short window (landscape phone) the expanded FAB would sit over the search field
    // and the first rows, so it stays icon-only there.
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val shortWindow = with(density) { windowInfo.containerSize.height.toDp() } < SHORT_WINDOW_HEIGHT
    // At a large font scale the label makes the FAB as wide as a row and it covers the list.
    val largeFont = density.fontScale > CHROME_MAX_FONT_SCALE
    val fabExpanded = !scrolledAway && !shortWindow && !largeFont
    var addSheetOpen by remember { mutableStateOf(false) }
    var feedMenuOpen by remember { mutableStateOf(false) }
    var bulkConfirm by remember { mutableStateOf<FeedBulkAction?>(null) }
    // Bulk hide/show acts on every subscribed or followed channel, not just the rows on
    // screen: a channel whose post hasn't landed yet would otherwise slip past "Hide all",
    // and a transient drill/deep-link row of an unsubscribed chat would be hidden for good.
    // Followed (read-without-joining) channels are included because their posts reach the
    // same merged feed: "Hide all" leaving them visible, or "Mark all read" leaving their
    // unread strips, would read as the bulk action having failed.
    val bulkScope = { repo.feedChannelIds().ifEmpty { channels.map { it.chatId }.toSet() } }

    bulkConfirm?.let { action ->
        AlertDialog(
            onDismissRequest = { bulkConfirm = null },
            title = { Text(stringResource(action.title)) },
            text = { Text(stringResource(action.confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        bulkConfirm = null
                        scope.launch {
                            when (action) {
                                FeedBulkAction.HideAll -> graph.ignoredChannels.addAll(bulkScope())
                                FeedBulkAction.ShowAll -> graph.ignoredChannels.removeAll(bulkScope())
                                // In the session scope, not this screen's: the acks trickle
                                // out over several seconds and must outlive a tab switch,
                                // but not a logout.
                                FeedBulkAction.MarkAllRead -> {
                                    val ids = bulkScope()
                                    graph.session.scope.launch { repo.markChannelsRead(ids) }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(action.confirmLabel))
                }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ChannelsHeader(
                title = stringResource(R.string.channels_title),
                scrolled = scrolledAway,
                actions = {
                    if (channels.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { feedMenuOpen = true }) {
                                Symbol(
                                    name = "dynamic_feed",
                                    contentDescription = stringResource(R.string.channels_feed_menu),
                                )
                            }
                            DropdownMenu(expanded = feedMenuOpen, onDismissRequest = { feedMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.channels_mark_all_read)) },
                                    leadingIcon = { Symbol(name = "check_circle", contentDescription = null) },
                                    enabled = unreadCounts.any { it.value > 0 },
                                    onClick = { feedMenuOpen = false; bulkConfirm = FeedBulkAction.MarkAllRead },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.channels_hide_all)) },
                                    leadingIcon = { Symbol(name = "visibility_off", contentDescription = null) },
                                    onClick = { feedMenuOpen = false; bulkConfirm = FeedBulkAction.HideAll },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.channels_show_all)) },
                                    leadingIcon = { Symbol(name = "visibility", contentDescription = null) },
                                    enabled = hiddenChatIds.any { it in bulkScope() },
                                    onClick = { feedMenuOpen = false; bulkConfirm = FeedBulkAction.ShowAll },
                                )
                            }
                        }
                    }
                    ChannelListTransferMenu(
                        transfer = graph.subscriptionTransfer,
                        mode = TransferMode.Account,
                        hasChannels = channels.isNotEmpty(),
                        userMessages = graph.userMessages,
                    )
                },
            )
        },
        floatingActionButton = {
            val fabLabel = stringResource(R.string.discover_add_channel)
            // Collapses to its icon while the list is scrolled away from the top (and on a
            // short window), so it stops covering the unread badges at the row ends; the
            // `expanded` overload animates the morph with the theme's MotionScheme.
            ExtendedFloatingActionButton(
                onClick = { addSheetOpen = true },
                expanded = fabExpanded,
                // This inner Scaffold doesn't own the FloatingNavBar inset (the
                // HomeKey scaffold draws it), so lift the FAB clear of it manually.
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                // The label text leaves the tree when collapsed, so the icon carries the
                // description then — exactly one of the two is announced at a time.
                icon = {
                    Symbol(
                        name = "add",
                        contentDescription = if (fabExpanded) null else fabLabel,
                        size = 20.dp,
                    )
                },
                text = { Text(fabLabel) },
            )
        },
        containerColor = channelsCanvasColor(),
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyChannels(
                modifier = Modifier.padding(padding),
                onFindChannels = { addSheetOpen = true },
            )
        } else {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    // Clear the floating nav bar AND the "Find channels" FAB stacked above
                    // it, so the last rows can scroll fully into view instead of sitting
                    // under the button.
                    bottom = contentPadding.calculateBottomPadding() + FAB_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = READING_WIDTH_CAP)
                    .fillMaxSize(),
            ) {
                item(key = "search", contentType = "search") {
                    Column(Modifier.padding(bottom = 10.dp)) {
                        DiscoverSearchField(
                            value = query,
                            onValueChange = { query = it },
                            hint = stringResource(R.string.channels_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ChannelsFilterChips(
                            selected = activeFilter,
                            counts = remember(buckets) { buckets.mapValues { it.value.size } },
                            onSelect = { filter = it },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        // Teach the swipe once: a dismissible hint until the user has either
                        // closed it or used the gesture.
                        if (!swipeHintSeen && query.isBlank() && activeFilter == ChannelsFilter.All) {
                            SwipeHintRow(
                                onDismiss = { scope.launch { graph.onboardingStore.setChannelsSwipeHintSeen() } },
                                modifier = Modifier.padding(top = HortaySpacing.md),
                            )
                        }
                    }
                }
                itemsIndexed(items = shown, key = { _, it -> it.chatId }) { index, ch ->
                    val isHidden = ch.chatId in hiddenChatIds
                    // Honest per-post unread, identical rule to PostCard's strip:
                    // newest (album-aware) id above the chat's read cursor.
                    val isUnread = ch.lastPostId > 0L && ch.lastPostId.isUnreadAgainst(cursors[ch.chatId])
                    val onClick = remember(ch.chatId) { { onChannelClick(ch.chatId) } }
                    val onHideToggle = remember(ch.chatId) {
                        {
                            scope.launch {
                                graph.ignoredChannels.toggle(ch.chatId)
                                graph.onboardingStore.setChannelsSwipeHintSeen()
                            }
                            Unit
                        }
                    }
                    // Followed rows: Telegram keeps no unread count for a non-member, so the
                    // badge counts slice posts above the local cursor instead.
                    val unreadCount = if (ch.isFollowed) {
                        followedUnreadCount(ch, cursors[ch.chatId])
                    } else {
                        unreadCounts[ch.chatId] ?: 0
                    }
                    val followActions = if (ch.isFollowed && follow != null) {
                        remember(ch.chatId, ch.title, follow) {
                            FollowedRowActions(
                                onJoin = { graph.session.scope.launch { graph.channelActions.joinChat(ch.chatId) } },
                                onStopReading = { follow.stopReading(ch.chatId, ch.title) },
                            )
                        }
                    } else {
                        null
                    }
                    ChannelRow(
                        channel = ch,
                        index = index,
                        count = shown.size,
                        isHidden = isHidden,
                        isUnread = isUnread,
                        unreadCount = unreadCount,
                        onClick = onClick,
                        onHideToggle = onHideToggle,
                        followActions = followActions,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            }
        }
    }

    if (addSheetOpen) {
        AddChannelTdSheet(
            suggestionsRepo = graph.channelSuggestions,
            discovery = graph.channelDiscovery,
            actions = graph.channelActions,
            recommended = graph.recommendedChannels,
            locale = Locale.current.language.lowercase(),
            onDismiss = { addSheetOpen = false },
            onOpenChannel = { chatId ->
                addSheetOpen = false
                onChannelClick(chatId)
            },
            followedIds = followed.keys,
        )
    }
}

/** True when the newest post id sits above the chat's read cursor (null cursor → read). */
private fun Long.isUnreadAgainst(cursor: Long?): Boolean = cursor != null && this > cursor

/**
 * One subscribed channel. Swipe left (or right) toggles "hidden from feed" — the row snaps
 * back and dims, so the list keeps every channel in place and the state is reversible with
 * the same gesture. TalkBack gets the same toggle as a custom action. The trailing column
 * carries the recency and TDLib's real unread count as a badge (a dot when the count is
 * unknown but the slice proves the channel has unread).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: ChannelSummary,
    index: Int,
    count: Int,
    isHidden: Boolean,
    isUnread: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    onHideToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Join / Stop reading for a channel read without joining; null for joined rows. */
    followActions: FollowedRowActions? = null,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    val haptics = LocalHapticFeedback.current
    val currentToggle by rememberUpdatedState(onHideToggle)
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                currentToggle()
            }
            // Never actually dismiss: the row snaps back and re-renders in its new state.
            false
        },
    )
    val toggleLabel = stringResource(
        if (isHidden) R.string.channels_unhide_from_feed_for else R.string.channels_hide_from_feed_for,
        channel.title,
    )
    val dim = if (isHidden) 0.5f else 1f
    val unavailable = showsUnavailableLine(channel)
    val hasSupportingLine = unavailable || isHidden || channel.lastPostExcerpt.isNotBlank()
    var menuOpen by remember { mutableStateOf(false) }
    val joinLabel = stringResource(R.string.channels_join)
    val stopLabel = stringResource(R.string.follow_stop_reading)
    val menuLabel = stringResource(R.string.follow_row_actions)
    // At large font scale the title and excerpt may wrap to a second line instead of
    // truncating after a word or two.
    val textLines = if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) 2 else 1
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(toggleLabel) { currentToggle(); true })
                if (followActions != null) {
                    add(CustomAccessibilityAction(joinLabel) { followActions.onJoin(); true })
                    add(CustomAccessibilityAction(stopLabel) { followActions.onStopReading(); true })
                }
            }
        },
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Arrangement.Start
                } else {
                    Arrangement.End
                },
            ) {
                Symbol(
                    name = if (isHidden) "visibility" else "visibility_off",
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 22.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isHidden) R.string.channels_unhide_from_feed else R.string.channels_hide_from_feed,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    ) {
        Box {
        SegmentedListItem(
            onClick = onClick,
            // Followed rows: long-press opens Join / Stop reading (also offered to screen
            // readers as custom actions above). Joined rows keep plain taps.
            onLongClick = followActions?.let { { menuOpen = true } },
            onLongClickLabel = followActions?.let { menuLabel },
            shapes = shapes,
            colors = ListItemDefaults.colors(containerColor = channelsRowColor()),
            leadingContent = {
                Box(Modifier.alpha(dim)) {
                    TdAvatar(
                        name = channel.title,
                        thumb = channel.avatarThumb,
                        fileId = channel.avatarFileId,
                        size = 52.dp,
                    )
                }
            },
            supportingContent = {
                if (unavailable) {
                    Text(
                        text = stringResource(R.string.follow_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = textLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (isHidden) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Symbol(
                            name = "visibility_off",
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 14.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.channels_hidden_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else if (channel.lastPostExcerpt.isNotBlank()) {
                    Text(
                        text = channel.lastPostExcerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = textLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A hidden channel gets a visible way back besides the swipe; visible
                    // rows deliberately carry no eye button (swipe + TalkBack action only).
                    if (isHidden) {
                        IconButton(onClick = onHideToggle) {
                            Symbol(
                                name = "visibility_off",
                                contentDescription = toggleLabel,
                                tint = MaterialTheme.colorScheme.primary,
                                size = 22.dp,
                            )
                        }
                    }
                Column(horizontalAlignment = Alignment.End) {
                    // A followed channel with no post loaded yet has no time to show.
                    if (channel.lastPostDate > 0L) {
                        Text(
                            text = formatRelative(channel.lastPostDate),
                            style = MaterialTheme.typography.labelSmall.tabularFigures().capFontScale(METADATA_MAX_FONT_SCALE),
                            maxLines = 1,
                            color = if (unreadCount > 0 && !isHidden) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        unreadCount > 0 -> {
                            Spacer(Modifier.height(6.dp))
                            UnreadBadge(count = unreadCount, muted = isHidden)
                        }
                        isUnread -> {
                            Spacer(Modifier.height(6.dp))
                            UnreadDot()
                        }
                        // A two-line row keeps the badge slot so the time stays level with
                        // the title. A one-line row (no excerpt) drops it: the reserved 26 dp
                        // pushed the time above the centred title.
                        hasSupportingLine -> Spacer(Modifier.height(26.dp))
                    }
                }
                }
            },
            content = {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = textLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(dim),
                )
            },
        )
        if (followActions != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(joinLabel) },
                    leadingIcon = { Symbol(name = "add", contentDescription = null) },
                    onClick = { menuOpen = false; followActions.onJoin() },
                )
                DropdownMenuItem(
                    text = { Text(stopLabel) },
                    leadingIcon = { Symbol(name = "close", contentDescription = null) },
                    onClick = { menuOpen = false; followActions.onStopReading() },
                )
            }
        }
        }
    }
}

/** Long-press actions of a row read without joining. */
@Immutable
internal class FollowedRowActions(val onJoin: () -> Unit, val onStopReading: () -> Unit)

/**
 * TDLib's unread count as a Telegram-style pill. Muted (neutral tone) for channels hidden
 * from the feed, so the eye isn't drawn to counts the user chose to ignore.
 */
@Composable
private fun UnreadBadge(count: Int, muted: Boolean) {
    val container = if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary
    val content = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
    val description = pluralStringResource(R.plurals.channels_unread_count, count, count)
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.tabularFigures(),
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

private const val MAX_BADGE = 999
private val FAB_CLEARANCE = 88.dp
private val SHORT_WINDOW_HEIGHT = 480.dp
private const val LARGE_FONT_SCALE = 1.3f

/**
 * Ceiling on the effective font scale of the row's trailing time label. At 2.0 the
 * uncapped label ("щойно", "1 хв") grew wide enough to squeeze the title down to a few
 * letters; the label is glanceable metadata next to a fully scaled title, so it stops
 * growing at 1.3× while the title and excerpt keep the user's full scale.
 */
private const val METADATA_MAX_FONT_SCALE = 1.3f

/** Shrinks this style's sp size so its rendered size never exceeds [maxScale] × nominal. */
@Composable
private fun TextStyle.capFontScale(maxScale: Float): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= maxScale || !fontSize.isSp) return this
    return copy(fontSize = (fontSize.value * maxScale / fontScale).sp)
}

/**
 * Single-row header: title and the feed-visibility action share one row, so the list starts
 * right under it instead of below an action strip plus a two-row title. The row sits in the
 * same centred, [READING_WIDTH_CAP]-wide column as the list, so on a wide (landscape) window
 * the title lines up with the search field and rows instead of hugging the screen edge. The
 * background (status-bar band included) tints as one surface once the list scrolls under it.
 */
@Composable
private fun ChannelsHeader(
    title: String,
    scrolled: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (scrolled) MaterialTheme.colorScheme.surfaceContainer
        else channelsCanvasColor(),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "channels-header-tint",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = READING_WIDTH_CAP)
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                // Start matches the list's 16 dp content padding; the end is tighter
                // because the 48 dp icon button carries its own inner padding.
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .semantics { heading() },
            )
            actions()
        }
    }
}

/**
 * Unread dot in the trailing column — the honest signal for "this channel has unread"
 * (the UI cannot know HOW MANY; see the file-level KDoc for the data constraint).
 */
@Composable
private fun UnreadDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun EmptyChannels(
    modifier: Modifier = Modifier,
    onFindChannels: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.channels_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.channels_empty_helper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        // Primary path: discover channels in-app (search + curated suggestions).
        FilledTonalButton(
            onClick = onFindChannels,
            shapes = ButtonDefaults.shapes(),
        ) {
            Symbol(name = "add", contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discover_add_channel))
        }
        Spacer(Modifier.height(8.dp))
        // Secondary: subscriptions made in the official Telegram client propagate
        // to Hortay via TDLib's UpdateNewChat stream, no explicit refresh needed.
        val context = LocalContext.current
        TextButton(onClick = { openTelegramApp(context) }) {
            Text(stringResource(R.string.empty_action_open_telegram))
        }
    }
}

/** One-time inline explainer for the swipe-to-hide gesture, with a "Got it" dismiss. */
@Composable
private fun SwipeHintRow(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = HortaySpacing.lg, top = HortaySpacing.xs, bottom = HortaySpacing.xs, end = HortaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                name = "swap_horiz",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
            )
            Spacer(Modifier.width(HortaySpacing.md))
            Text(
                text = stringResource(R.string.channels_swipe_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_got_it)) }
        }
    }
}

/** Bulk feed-visibility actions from the Channels top bar (issue #3: 500-channel users). */
private enum class FeedBulkAction(
    @param:StringRes val title: Int,
    @param:StringRes val confirm: Int,
    /** Confirm button text; a short verb where repeating [title] would not fit beside Cancel. */
    @param:StringRes val confirmLabel: Int = title,
) {
    HideAll(R.string.channels_hide_all, R.string.channels_hide_all_confirm),
    ShowAll(R.string.channels_show_all, R.string.channels_show_all_confirm),
    MarkAllRead(
        R.string.channels_mark_all_read,
        R.string.channels_mark_all_read_confirm,
        R.string.channels_mark_all_read_action,
    ),
}

/** Quick filters above the channel list. */
internal enum class ChannelsFilter(@param:StringRes val label: Int) {
    All(R.string.channels_filter_all),
    Unread(R.string.channels_filter_unread),
    Today(R.string.channels_filter_today),
    Hidden(R.string.channels_filter_hidden),
    /** Channels read without joining; the chip appears only when there is one. */
    NotJoined(R.string.follow_not_joined),
}

/**
 * Canvas behind the channel list. Rows must separate from it in every theme:
 *  - light: `surfaceContainerLow` canvas under `surfaceContainerLowest` (white) rows;
 *  - dark: `background` canvas under `surfaceContainer` rows. The light pairing inverted in
 *    dark would sink the rows below the canvas, and in pure black `surfaceContainerLowest`
 *    IS the canvas (see `withPureBlack`), so rows would vanish into it. Pure black keeps a
 *    #000 canvas with the container-toned rows on top.
 */
@Composable
private fun channelsCanvasColor(): Color =
    if (LocalHortayDarkTheme.current) MaterialTheme.colorScheme.background
    else MaterialTheme.colorScheme.surfaceContainerLow

/** Row tile colour paired with [channelsCanvasColor]. */
@Composable
private fun channelsRowColor(): Color =
    if (LocalHortayDarkTheme.current) MaterialTheme.colorScheme.surfaceContainer
    else MaterialTheme.colorScheme.surfaceContainerLowest

/**
 * Filter chips with live counts. An empty filter other than All is left out instead of
 * offered as a dead end, so with nothing hidden there is no "Hidden" chip.
 *
 * Borderless tonal pills: an outlined chip per filter drew four extra frames right above a
 * list of tiles. Unselected `surfaceContainerHigh`, selected `primaryContainer`.
 */
@Composable
private fun ChannelsFilterChips(
    selected: ChannelsFilter,
    counts: Map<ChannelsFilter, Int>,
    onSelect: (ChannelsFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelsFilter.entries.forEach { f ->
            val count = counts[f] ?: 0
            if (f != ChannelsFilter.All && count == 0) return@forEach
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                shape = CircleShape,
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                label = {
                    Text(
                        text = if (f == ChannelsFilter.All) stringResource(f.label) else "${stringResource(f.label)} · $count",
                        style = MaterialTheme.typography.labelLarge.tabularFigures(),
                    )
                },
            )
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
