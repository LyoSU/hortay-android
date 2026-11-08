package dev.lyo.hortay.ui.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
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
    val channels = remember(posts) { aggregate(posts) }
    val unreadCounts by repo.chatUnreadCounts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(channels, query) {
        val q = query.trim()
        if (q.isEmpty()) channels else channels.filter { it.title.contains(q, ignoreCase = true) }
    }
    val hiddenChatIds by graph.ignoredChannels.ignored.collectAsStateWithLifecycle(
        initialValue = persistentSetOf(),
    )
    val scope = rememberCoroutineScope()
    val cursors = LocalReadCursors.current
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var addSheetOpen by remember { mutableStateOf(false) }
    var feedMenuOpen by remember { mutableStateOf(false) }
    var bulkConfirm by remember { mutableStateOf<FeedBulkAction?>(null) }
    // Bulk hide/show acts on every subscribed channel, not just the rows on screen: a
    // channel whose post hasn't landed yet would otherwise slip past "Hide all", and a
    // transient drill/deep-link row of an unsubscribed chat would be hidden for good.
    val bulkScope = { repo.subscribedChatIds().ifEmpty { channels.map { it.chatId }.toSet() } }

    bulkConfirm?.let { action ->
        AlertDialog(
            onDismissRequest = { bulkConfirm = null },
            title = {
                Text(
                    stringResource(
                        if (action == FeedBulkAction.HideAll) R.string.channels_hide_all else R.string.channels_show_all,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (action == FeedBulkAction.HideAll) R.string.channels_hide_all_confirm
                        else R.string.channels_show_all_confirm,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bulkConfirm = null
                        scope.launch {
                            when (action) {
                                FeedBulkAction.HideAll -> graph.ignoredChannels.addAll(bulkScope())
                                FeedBulkAction.ShowAll -> graph.ignoredChannels.removeAll(bulkScope())
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (action == FeedBulkAction.HideAll) R.string.channels_hide_all else R.string.channels_show_all,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.channels_title),
                size = HortayTopBarSize.Medium,
                scrollBehavior = scrollBehavior,
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
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addSheetOpen = true },
                // This inner Scaffold doesn't own the FloatingNavBar inset (the
                // HomeKey scaffold draws it), so lift the FAB clear of it manually.
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
                icon = { Symbol(name = "add", contentDescription = null, size = 20.dp) },
                text = { Text(stringResource(R.string.discover_add_channel)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyChannels(
                modifier = Modifier.padding(padding),
                onFindChannels = { addSheetOpen = true },
            )
        } else {
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
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "search", contentType = "search") {
                    Column(Modifier.padding(bottom = 10.dp)) {
                        DiscoverSearchField(
                            value = query,
                            onValueChange = { query = it },
                            hint = stringResource(R.string.channels_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Teach the swipe once it matters: shown until the user has hidden
                        // anything, after which they evidently know the gesture.
                        if (hiddenChatIds.isEmpty() && query.isBlank()) {
                            Text(
                                text = stringResource(R.string.channels_swipe_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
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
                        { scope.launch { graph.ignoredChannels.toggle(ch.chatId) }; Unit }
                    }
                    ChannelRow(
                        channel = ch,
                        index = index,
                        count = shown.size,
                        isHidden = isHidden,
                        isUnread = isUnread,
                        unreadCount = unreadCounts[ch.chatId] ?: 0,
                        onClick = onClick,
                        onHideToggle = onHideToggle,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (addSheetOpen) {
        AddChannelTdSheet(
            suggestionsRepo = graph.channelSuggestions,
            discovery = graph.channelDiscovery,
            actions = graph.channelActions,
            locale = Locale.current.language.lowercase(),
            onDismiss = { addSheetOpen = false },
        )
    }
}

/** True when the newest post id sits above the chat's read cursor (null cursor → read). */
private fun Long.isUnreadAgainst(cursor: Long?): Boolean = cursor != null && this > cursor

// @Immutable required: the ByteArray field defeats Compose's automatic
// stability inference (arrays are mutable references), so without the
// annotation every ChannelRow in a 200-channel LazyColumn re-composes on any
// upstream list mutation — even if the row's own ChannelSummary didn't change.
// The annotation is a contract: ChannelSummary instances are never mutated
// after construction (and `aggregate` builds fresh ones every recomposition,
// so this trivially holds).
@androidx.compose.runtime.Immutable
private data class ChannelSummary(
    val chatId: Long,
    val title: String,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val lastPostExcerpt: String,
    val lastPostDate: Long,
    /**
     * Highest (album-aware) message id this channel contributed to the loaded slice.
     * Compared against the read cursor to derive the unread dot — same album-aware rule
     * as [TimelinePost.isUnreadAt] (an album stays unread until every member is acked).
     */
    val lastPostId: Long,
)

private fun aggregate(posts: List<TimelinePost>): List<ChannelSummary> = posts
    .groupBy { it.chatId }
    .map { (chatId, list) ->
        val anchor = list.maxByOrNull { it.date }!!
        // Personal-author channel mode: each post's senderName / avatar is the admin who
        // wrote it, NOT the channel. We need the channel's own identity here, which lives
        // in [channelContext]. Prefer ANY post in the group whose channelContext is set
        // (or, equivalently, whose own senderName is already the channel) so a channel
        // with multiple posting admins still surfaces as one row with the right name and
        // photo. Falling back to anchor.senderName covers the all-channel-as-sender case
        // where channelContext is null on every post.
        val channelLike = list.firstNotNullOfOrNull { it.channelContext }
        val title = channelLike?.name ?: anchor.senderName
        val thumb = channelLike?.avatarThumb ?: anchor.avatarThumb
        val fileId = channelLike?.avatarFileId ?: anchor.avatarFileId
        // Highest id across every post (and album member) this channel put in the slice.
        // Album-aware so a partially-read album still reads as unread, mirroring isUnreadAt.
        val highestId = list.maxOf { p -> p.albumMessageIds.maxOrNull() ?: p.id }
        ChannelSummary(
            chatId = chatId,
            title = title,
            avatarThumb = thumb,
            avatarFileId = fileId,
            lastPostExcerpt = anchor.content.captionPlain.take(120),
            lastPostDate = anchor.date,
            lastPostId = highestId,
        )
    }
    .sortedByDescending { it.lastPostDate }

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
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(toggleLabel) { currentToggle(); true })
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
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
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
                if (isHidden) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatRelative(channel.lastPostDate),
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
                        color = if (unreadCount > 0 && !isHidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        unreadCount > 0 -> UnreadBadge(count = unreadCount, muted = isHidden)
                        isUnread -> UnreadDot()
                        else -> Spacer(Modifier.height(20.dp))
                    }
                }
            },
            content = {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(dim),
                )
            },
        )
    }
}

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

/** Bulk feed-visibility actions from the Channels top bar (issue #3: 500-channel users). */
private enum class FeedBulkAction { HideAll, ShowAll }
