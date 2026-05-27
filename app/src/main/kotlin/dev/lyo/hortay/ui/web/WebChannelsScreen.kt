package dev.lyo.hortay.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.web.ChannelEntry
import dev.lyo.hortay.data.web.ChannelFetchStatus
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.ui.components.HortayTopBar
import kotlinx.collections.immutable.persistentSetOf
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import kotlinx.coroutines.launch

/**
 * Subscribed-channels list for guest mode. Mirrors [dev.lyo.hortay.ui.channels.ChannelsScreen]
 * shape: own [Scaffold] + collapsing [HortayTopBar] (Large), Material 3 Expressive
 * [SegmentedListItem] rows with a 48dp [TdAvatar] in the leading slot and a per-channel
 * status indicator folded into the supporting line.
 *
 * Unsubscribe affordance: explicit trailing `close` icon button on each row, plus
 * a confirmation dialog. Long-press alone was discoverability-hostile.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebChannelsScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    onChannelClick: (String) -> Unit,
    onAddChannel: () -> Unit = {},
    /**
     * Tapping a curated quick-pick in the empty state. Carries the channel handle
     * so the host can open the add sheet pre-filled — one tap surfaces the preview
     * instead of making a fresh user type a handle they don't know yet.
     */
    onAddCurated: (String) -> Unit = {},
) {
    val channels by graph.webFeedSource.channels.collectAsStateWithLifecycle()
    // Hidden chatIds for the inline "hide from feed" toggle on each row. Same
    // store as TDLib mode; chatIds for guest channels come from the username
    // hash so a channel hidden in TDLib mode stays hidden if the user falls
    // back to guest, and vice versa.
    val hiddenChatIds by graph.ignoredChannels.ignored.collectAsStateWithLifecycle(
        initialValue = persistentSetOf(),
    )
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    val subscribed = channels.filter { it.isSubscribed }
    val listState = rememberLazyListState()
    var pendingUnsubscribe by remember { mutableStateOf<ChannelEntry?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.web_channels_title),
                size = HortayTopBarSize.Large,
                actions = { GuestModeBadge() },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (subscribed.isEmpty()) {
            // Curated quick-picks defuse the empty-state "paralysis of choice":
            // a brand-new user with nothing subscribed gets a few popular handles
            // to tap instead of a blank field. Capped to keep the centred layout
            // from overflowing on short screens.
            val curated = remember {
                curatedSuggestions(Locale.getDefault().language.lowercase()).take(5)
            }
            EmptyChannelsState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onAddChannel = onAddChannel,
                curated = curated,
                onPickCurated = onAddCurated,
            )
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(subscribed, key = { _, it -> it.info.username }) { index, entry ->
                val chatId = remember(entry.info.username) {
                    WebPostAdapter.stableChatId(entry.info.username)
                }
                ChannelRow(
                    entry = entry,
                    index = index,
                    count = subscribed.size,
                    isHidden = chatId in hiddenChatIds,
                    onClick = { onChannelClick(entry.info.username) },
                    onRetryClick = { graph.webFeedSource.retry(entry.info.username) },
                    onHideToggle = { scope.launch { graph.ignoredChannels.toggle(chatId) } },
                    onUnsubscribeClick = { pendingUnsubscribe = entry },
                )
            }
        }
    }

    pendingUnsubscribe?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            title = { Text(stringResource(R.string.web_unsubscribe_title)) },
            text = { Text(stringResource(R.string.web_unsubscribe_body, entry.info.username)) },
            confirmButton = {
                TextButton(onClick = {
                    val u = entry.info.username
                    pendingUnsubscribe = null
                    scope.launch { graph.webSubscriptions.remove(u) }
                }) {
                    Text(
                        stringResource(R.string.web_unsubscribe_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text(stringResource(R.string.web_cancel))
                }
            },
        )
    }
}

/**
 * Friendly empty-state for first-launch / fully-unsubscribed users. Replaces
 * the bare centered string ("Поки немає каналів") with a Material-3-shaped
 * card: a soft tinted icon disc, a title, an explanatory subtitle, and an
 * explicit "Add channel" CTA. The CTA duplicates the [WebModeScaffold] FAB
 * — both routes work — but having it in the empty state means a user who
 * landed on the Channels tab via the bottom nav doesn't have to discover
 * the FAB to make progress.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChannelsState(
    modifier: Modifier = Modifier,
    onAddChannel: () -> Unit,
    curated: List<CuratedChannel> = emptyList(),
    onPickCurated: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = "rss_feed",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 48.dp,
            )
        }
        Spacer(Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.web_empty_channels_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.web_empty_channels_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        FilledTonalButton(
            onClick = onAddChannel,
            shapes = ButtonDefaults.shapes(),
        ) {
            Symbol(name = "add", contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.web_add_channel))
        }
        if (curated.isNotEmpty()) {
            Spacer(Modifier.size(28.dp))
            Text(
                text = stringResource(R.string.web_add_curated_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                curated.forEach { suggestion ->
                    AssistChip(
                        onClick = { onPickCurated(suggestion.username) },
                        label = { Text("@${suggestion.username}") },
                        leadingIcon = {
                            Symbol(name = "add", contentDescription = null, size = 16.dp)
                        },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelRow(
    entry: ChannelEntry,
    index: Int,
    count: Int,
    isHidden: Boolean,
    onClick: () -> Unit,
    onRetryClick: () -> Unit,
    onHideToggle: () -> Unit,
    onUnsubscribeClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = { ChannelAvatar(name = entry.info.title, avatarUrl = entry.info.avatarUrl) },
        supportingContent = { ChannelSubtitle(entry = entry) },
        trailingContent = {
            // Two icon buttons live in a Row inside the trailing slot. Retry
            // sits BEFORE close so the eye lands on "recover" before "remove"
            // for a row that's currently failing. While the fetch is in flight
            // (Loading) we swap the icon for a small spinner — same slot,
            // no layout shift. Tap target stays the full IconButton 48 dp so
            // the user doesn't have to aim at the 20 dp glyph.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.status.isRetryable()) {
                    IconButton(
                        onClick = onRetryClick,
                        enabled = entry.status != ChannelFetchStatus.Loading,
                    ) {
                        if (entry.status == ChannelFetchStatus.Loading) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Symbol(
                                name = "refresh",
                                contentDescription = stringResource(
                                    R.string.web_retry_for,
                                    entry.info.title,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 20.dp,
                            )
                        }
                    }
                }
                IconButton(onClick = onHideToggle) {
                    // Hide-from-feed toggle. Tinted primary when active so the
                    // user can scan a long list and see at a glance which
                    // channels they've muted out of the merged feed.
                    Symbol(
                        name = if (isHidden) "visibility_off" else "visibility",
                        contentDescription = stringResource(
                            if (isHidden) R.string.channels_unhide_from_feed_for
                            else R.string.channels_hide_from_feed_for,
                            entry.info.title,
                        ),
                        tint = if (isHidden) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 20.dp,
                    )
                }
                IconButton(onClick = onUnsubscribeClick) {
                    // Channel-aware Talkback label. "Unsubscribe" alone gave a
                    // user with N rows N identical-sounding buttons in row order
                    // — they had to land focus elsewhere first to discover which
                    // channel each one targeted. Now reads "Unsubscribe from <name>".
                    Symbol(
                        name = "close",
                        contentDescription = stringResource(
                            R.string.web_unsubscribe_from,
                            entry.info.title,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 20.dp,
                    )
                }
            }
        },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.info.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Symbol(
                        name = "verified",
                        tint = MaterialTheme.colorScheme.primary,
                        size = 14.dp,
                    )
                }
            }
        },
    )
}

/**
 * Retryable means "show the refresh affordance on the row". Manual retry now
 * covers every failure state, including [ChannelFetchStatus.NotFound] and
 * [ChannelFetchStatus.Private]: a channel can flip back to public at any time
 * (the original "no amount of client-side retry will change them" assumption
 * stops holding the moment the owner toggles visibility), and the auto-retry
 * skipper still keeps these out of the foreground sweep — only an explicit
 * tap pokes them. One deliberate user tap per channel cannot trigger a
 * FLOOD_WAIT, so the previous "learned helplessness" concern is outweighed by
 * the dead-channel-forever cost.
 *
 * [ChannelFetchStatus.Loading] stays here so the row keeps the same slot for
 * a spinner mid-fetch — flipping the icon between spinner and refresh without
 * removing the trailing IconButton from the layout avoids a width recalc.
 */
private fun ChannelFetchStatus.isRetryable(): Boolean = when (this) {
    ChannelFetchStatus.Error,
    ChannelFetchStatus.RateLimited,
    ChannelFetchStatus.ParseFailure,
    ChannelFetchStatus.NotFound,
    ChannelFetchStatus.Private,
    ChannelFetchStatus.Loading -> true
    ChannelFetchStatus.Idle,
    ChannelFetchStatus.Ok -> false
}

/**
 * Single-line subtitle that folds the live fetch status into the `@handle ·`
 * line instead of stacking a third row beneath it. The previous design painted
 * a separate [StatusBadge] under the subtitle — visually that read as part of
 * the channel's identifier ("@telegramtips connection error" wrapping as one
 * caption) instead of a transient sync state, and ate vertical space on every
 * row regardless of whether the channel was healthy. Folding gives one line
 * with a clear hierarchy: handle first, then status (when present), then
 * subscriber count (when known and no status is interesting).
 *
 * When the channel has a status worth surfacing AND no subscriber count yet
 * (first-fetch failure), the status replaces what would otherwise be a bare
 * `@handle` line. When subscribers are known from a previous successful fetch,
 * the status STILL wins over them — a stale "11.6M subscribers" sitting next
 * to a fresh "не вдалося оновити" reads as contradictory; the freshness of
 * the failure is what the user needs to act on.
 */
@Composable
private fun ChannelSubtitle(entry: ChannelEntry) {
    val statusRes = entry.status.subtitleStringRes()
    val statusLine = statusRes?.let { stringResource(it) }
    val subscriberLine = entry.info.subscribers?.let {
        stringResource(R.string.web_subscribers, it)
    }
    val tail = statusLine ?: subscriberLine
    val isError = statusLine != null && entry.status != ChannelFetchStatus.Loading
    val text = buildString {
        append("@${entry.info.username}")
        if (tail != null) {
            append(" · ")
            append(tail)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun ChannelFetchStatus.subtitleStringRes(): Int? = when (this) {
    ChannelFetchStatus.Idle, ChannelFetchStatus.Ok -> null
    ChannelFetchStatus.Loading -> R.string.web_status_loading
    ChannelFetchStatus.Error -> R.string.web_status_error
    ChannelFetchStatus.RateLimited -> R.string.web_status_rate_limited
    ChannelFetchStatus.NotFound -> R.string.web_status_not_found
    ChannelFetchStatus.Private -> R.string.web_status_private
    ChannelFetchStatus.ParseFailure -> R.string.web_status_parse_failure
}

@Composable
private fun ChannelAvatar(name: String, avatarUrl: String?) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val bg = palette[(name.hashCode().rem(palette.size) + palette.size) % palette.size]
    Box(modifier = Modifier.clip(CircleShape)) {
        TdAvatar(
            name = name,
            thumb = null,
            fileId = null,
            size = 48.dp,
            background = bg,
        )
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.avatar_for_channel, name),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            )
        }
    }
}

/**
 * Persistent reminder that the user is reading without authentication. Sits as
 * a passive `AssistChip` in the top-bar `actions` slot — non-interactive (the
 * onClick is a no-op) so it never competes with real action targets, but
 * carries a `visibility` glyph and the localized "Guest mode" label so the
 * mode is glanceable from the feed and channels screens. Without it users who
 * leave the app and return forget which mode they're in.
 */
@Composable
internal fun GuestModeBadge() {
    AssistChip(
        onClick = {},
        enabled = false,
        leadingIcon = {
            Symbol(name = "visibility", contentDescription = null, size = 16.dp)
        },
        label = {
            Text(
                text = stringResource(R.string.guest_mode_badge),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(end = 12.dp),
    )
}
