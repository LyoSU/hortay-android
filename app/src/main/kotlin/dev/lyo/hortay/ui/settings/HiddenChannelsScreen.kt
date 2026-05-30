@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.web.WebPostAdapter
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch

/**
 * Manage-list for channels the user has hidden from the merged feed. Reached
 * from Settings → "Hidden channels (N)" row. Two affordances per row:
 *
 *   - Tap the row → no-op (no drill destination; we'd otherwise need to push
 *     a ChannelKey through a host scaffold that this screen doesn't own
 *     a handle to. The Unhide button is the single intentional action here).
 *   - Trailing "eye" button → flips the channel out of the ignore set; the
 *     row fades on the next [IgnoredChannelsStore.ignored] emit.
 *
 * Channel metadata resolution:
 *   - TDLib mode (auth): [channelActions] is non-null; we call channelInfo()
 *     for each hidden chatId. Best fidelity — server-side title, handle,
 *     subscriber count are accurate.
 *   - Guest mode: [webChannelByChatId] resolves through the local DB
 *     (web_feed_source.channels). chatIds in guest mode come from
 *     [WebPostAdapter.stableChatId] so the same chatId-keyed store works.
 *   - Both: cross-mode users see a mix — a channel hidden under TDLib mode
 *     might not be in the guest channels list if they later sign out, in
 *     which case we render a "Unknown channel" placeholder with the bare id.
 *     User can still un-hide it.
 *
 * Why not split into two screens (one per mode): the user is one person with
 * one ignore set. A combined screen keeps the management surface predictable
 * regardless of which mode they happen to be in when they reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenChannelsScreen(
    store: IgnoredChannelsStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** TDLib mode resolver. Null in guest mode. */
    channelActions: ChannelActionsRepository? = null,
    /** Guest-mode resolver: chatId → (title, username) or null. */
    webChannelByChatId: ((Long) -> WebChannelDescriptor?)? = null,
) {
    val hidden by store.ignored.collectAsStateWithLifecycle(initialValue = persistentSetOf())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Resolved display metadata, keyed by chatId. Resolution is async (TDLib
    // round-trip); the row renders a placeholder until the LaunchedEffect
    // below settles. Using a SnapshotStateMap so per-key writes recompose
    // only the row whose chatId just resolved, not the entire list.
    val resolved: SnapshotStateMap<Long, HiddenChannelDisplay> = remember { mutableStateMapOf() }

    // Materialise the hidden set into a stable ordered list so LazyColumn
    // keys are deterministic across re-emits. Sort by chatId so re-ordering
    // doesn't shuffle on every keystroke-style toggle.
    val hiddenList = remember(hidden) { hidden.sorted() }

    LaunchedEffect(hiddenList) {
        // Fetch missing metadata for any newly-hidden chatIds. Already-resolved
        // entries are skipped so a toggle on one channel doesn't re-fetch
        // metadata for the other 47 in the list.
        hiddenList.forEach { chatId ->
            if (chatId in resolved) return@forEach
            val display = resolve(chatId, channelActions, webChannelByChatId)
            if (display != null) resolved[chatId] = display
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.hidden_channels_title),
                size = HortayTopBarSize.Large,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 24.dp,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (hiddenList.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(hiddenList, key = { _, id -> id }) { index, chatId ->
                val display = resolved[chatId]
                HiddenChannelRow(
                    chatId = chatId,
                    display = display,
                    index = index,
                    count = hiddenList.size,
                    onUnhide = { scope.launch { store.remove(chatId) } },
                )
            }
        }
    }
}

@Composable
private fun HiddenChannelRow(
    chatId: Long,
    display: HiddenChannelDisplay?,
    index: Int,
    count: Int,
    onUnhide: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    val title = display?.title ?: stringResource(R.string.hidden_channels_unknown)
    val subtitle = display?.handle?.let { "@$it" }
        ?: stringResource(R.string.hidden_channels_id_placeholder, chatId)
    SegmentedListItem(
        onClick = onUnhide,
        shapes = shapes,
        leadingContent = { Symbol(name = "visibility_off", size = 22.dp) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onUnhide) {
                Symbol(
                    name = "visibility",
                    contentDescription = stringResource(
                        R.string.hidden_channels_unhide_for,
                        title,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp,
                )
            }
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Symbol(
                name = "visibility",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 48.dp,
            )
            Text(
                text = stringResource(R.string.hidden_channels_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.hidden_channels_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private suspend fun resolve(
    chatId: Long,
    channelActions: ChannelActionsRepository?,
    webChannelByChatId: ((Long) -> WebChannelDescriptor?)?,
): HiddenChannelDisplay? {
    // TDLib first when available — server-side title beats local cache.
    if (channelActions != null) {
        runCatching { channelActions.channelInfo(chatId) }.getOrNull()?.let { info ->
            return HiddenChannelDisplay(
                title = info.title.ifBlank { "—" },
                handle = info.handle?.removePrefix("@"),
            )
        }
    }
    // Guest fallback / cross-mode channels.
    if (webChannelByChatId != null) {
        webChannelByChatId(chatId)?.let { return HiddenChannelDisplay(it.title, it.username) }
    }
    return null
}

/** Per-row display state. Avatar is intentionally not surfaced here — the
 *  resolved title + handle reads cleanly without an avatar lookup that would
 *  cost an extra TDLib round-trip / a Coil mount per row. */
internal data class HiddenChannelDisplay(
    val title: String,
    val handle: String?,
)

/** Lightweight channel descriptor for the guest-mode resolver. Keeps this
 *  screen decoupled from `dev.lyo.hortay.data.web.ChannelEntry`. */
data class WebChannelDescriptor(val title: String, val username: String)
