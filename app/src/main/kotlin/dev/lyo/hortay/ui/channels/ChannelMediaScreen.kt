@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelMediaItem
import dev.lyo.hortay.data.ChannelMediaState
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.LocalScrollGate
import dev.lyo.hortay.ui.media.MinithumbImage
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.media.rememberViewerSourceAnchor
import dev.lyo.hortay.ui.media.viewerSource
import dev.lyo.hortay.ui.timeline.DurationChip
import dev.lyo.hortay.ui.timeline.formatDuration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * A channel's shared media: its photos and videos as a square-cell grid, newest first,
 * paged on scroll through [ChannelMediaViewModel]. Three columns on a phone; wider windows
 * get more via [GridCells.Adaptive] (cells stay at least [CELL_MIN_SIZE]).
 *
 * Cells are deliberately quiet — no frame, card or progress chrome, 2 dp gaps — and only
 * ever download the small thumbnail tier (see [ChannelMediaItem]). Downloads follow the
 * feed's scroll gate ([LocalScrollGate]): a fling past a hundred cells doesn't fill
 * TDLib's download pool with thumbnails that are gone a frame later, and each cell gives
 * its slot back when it leaves composition. Visible thumbnails load regardless of the
 * auto-download settings, like visible feed media; nothing is prefetched ahead.
 *
 * Tapping a cell opens the shared fullscreen viewer over every loaded item, growing out of
 * the tapped cell.
 */
@Composable
fun ChannelMediaScreen(
    viewModel: ChannelMediaViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channelTitle by viewModel.title.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = { MediaTitle(channelTitle) },
                size = HortayTopBarSize.Compact,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        // The scaffold pads for the status bar + top bar and the navigation bar; the host's
        // [contentPadding] may reserve more at the bottom (the audio mini-player).
        val gridPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection),
            bottom = maxOf(padding.calculateBottomPadding(), contentPadding.calculateBottomPadding()),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding),
        ) {
            val s = state
            when (s.view) {
                ChannelMediaState.View.Grid -> MediaGrid(
                    state = s,
                    gridState = gridState,
                    contentPadding = gridPadding,
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::retry,
                )
                ChannelMediaState.View.Loading -> {
                    val showLoading = rememberDeferredLoading(
                        pending = true,
                        key = Unit,
                        graceMs = SCREEN_MOUNT_GRACE_MS,
                    )
                    if (showLoading) {
                        Box(Modifier.fillMaxSize().padding(gridPadding), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                }
                ChannelMediaState.View.Error -> CenteredMessage(
                    text = stringResource(R.string.channel_media_error),
                    actionLabel = stringResource(R.string.channel_media_retry),
                    onAction = viewModel::retry,
                    modifier = Modifier.padding(gridPadding),
                )
                // Each tap walks one more bounded stretch (see ChannelMediaViewModel.fetch),
                // so no request loop runs without the user asking for it.
                ChannelMediaState.View.KeepLooking -> CenteredMessage(
                    text = stringResource(R.string.channel_media_none_so_far),
                    actionLabel = stringResource(R.string.channel_media_keep_looking),
                    onAction = viewModel::loadMore,
                    modifier = Modifier.padding(gridPadding),
                )
                ChannelMediaState.View.Empty -> CenteredMessage(
                    text = stringResource(R.string.channel_media_empty),
                    actionLabel = null,
                    onAction = null,
                    modifier = Modifier.padding(gridPadding),
                )
            }
        }
    }
}

@Composable
private fun MediaTitle(channelTitle: String?) {
    Column {
        Text(
            text = stringResource(R.string.channels_media),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!channelTitle.isNullOrBlank()) {
            Text(
                text = channelTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaGrid(
    state: ChannelMediaState,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val viewer = LocalMediaViewer.current
    val currentItems by rememberUpdatedState(state.items)
    val canLoadMore by rememberUpdatedState(state.canLoadMore)

    // Near-end trigger. Keyed on `canLoadMore` too, so it re-arms when a page lands (or a
    // request settles) while the user is still parked at the end — a short page on a tall
    // window keeps paging until the viewport is full or the history runs out.
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            canLoadMore && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    // Same download gate the feed uses: thumbnails start once a fling settles.
    val scrollGate = remember(gridState) { derivedStateOf { !gridState.isScrollInProgress } }
    val openAt: (Long) -> Unit = remember(viewer) {
        { messageId ->
            val items = currentItems
            val index = items.indexOfFirst { it.messageId == messageId }
            // Protection is chat-wide in practice (`has_protected_content`), so one protected
            // item hides Save / Share / Copy for the whole swipeable set.
            if (index >= 0) {
                viewer.open(
                    items.map { it.viewerItem },
                    index,
                    protectedContent = items.any { it.protectedContent },
                )
            }
        }
    }

    CompositionLocalProvider(LocalScrollGate provides scrollGate) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CELL_MIN_SIZE),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            verticalArrangement = Arrangement.spacedBy(CELL_GAP),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = state.items,
                key = { it.messageId },
                contentType = { it.cellType() },
            ) { item ->
                val onClick = remember(item.messageId, openAt) { { openAt(item.messageId) } }
                MediaCell(item = item, onClick = onClick)
            }
            when (state.status) {
                ChannelMediaState.Status.Loading -> item(
                    key = FOOTER_KEY,
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = FOOTER_KEY,
                ) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                ChannelMediaState.Status.Error -> item(
                    key = FOOTER_KEY,
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = FOOTER_KEY,
                ) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.channel_media_retry))
                        }
                    }
                }
                ChannelMediaState.Status.Idle, ChannelMediaState.Status.End -> Unit
            }
        }
    }
}

@Composable
private fun MediaCell(item: ChannelMediaItem, onClick: () -> Unit) {
    // Registered under the viewer item's media (the same TdMedia the cell paints), so the
    // fullscreen viewer grows out of this cell and shrinks back into whichever cell the
    // user swiped to.
    val anchor = rememberViewerSourceAnchor(item.viewerItem.media, RectangleShape)
    val description = stringResource(
        if (item.durationSec != null) R.string.reply_kind_video else R.string.reply_kind_photo,
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .viewerSource(anchor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        if (item.hidden) {
            HiddenThumb(item)
        } else {
            TdMediaImage(
                media = item.thumb,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                showProgress = false,
            )
        }
        item.durationSec?.let { seconds ->
            DurationChip(
                text = formatDuration(seconds),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

/**
 * Spoiler / self-destructing media: only the blurred inline preview, never a download —
 * the content stays hidden until the user taps into the viewer.
 */
@Composable
private fun BoxScope.HiddenThumb(item: ChannelMediaItem) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
    item.thumb.minithumbBytes?.let { bytes ->
        MinithumbImage(
            bytes = bytes,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(HIDDEN_BLUR),
        )
    }
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(name = "visibility_off", tint = Color.White, size = 18.dp)
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) {
                Text(actionLabel)
            }
        }
    }
}

private fun ChannelMediaItem.cellType(): Int = when {
    hidden -> CELL_TYPE_HIDDEN
    durationSec != null -> CELL_TYPE_VIDEO
    else -> CELL_TYPE_PHOTO
}

/** Three columns on phones up to ~430 dp wide (3 × 104 + 2 × 2 ≤ width < 4 × 104 + 3 × 2). */
private val CELL_MIN_SIZE = 104.dp
private val CELL_GAP = 2.dp
private val HIDDEN_BLUR = 16.dp

/** Start the next page this many cells before the end (about five rows of three). */
private const val LOAD_MORE_THRESHOLD = 15
private const val FOOTER_KEY = "footer"
private const val CELL_TYPE_PHOTO = 0
private const val CELL_TYPE_VIDEO = 1
private const val CELL_TYPE_HIDDEN = 2
