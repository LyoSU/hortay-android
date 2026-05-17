@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.BrandRow
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem

/**
 * Mechanical extraction of the main feed LazyColumn from [TimelineScreen]. Behaviour
 * is identical to the inline body it replaced — captures from the outer scope are
 * hoisted into explicit parameters so the upcoming render gate (Task 5b) can swap
 * this for [SkeletonFeed] / [ExpressiveEmptyHero] via a clean `when(uiState)` switch
 * without re-touching the 2200-line outer composable.
 */
@Composable
internal fun TimelineFeedColumn(
    state: LazyListState,
    flingBehavior: FlingBehavior,
    bottomPadding: Dp,
    feedItems: List<FeedItem>,
    unreadBoundaryKey: Any?,
    centeredItemKeyState: State<Any?>,
    highlightedPostKey: Pair<Long, Long>?,
    interactions: PostInteractions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = state,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomPadding,
        ),
        modifier = modifier,
    ) {
        items(
            items = feedItems,
            key = { it.key },
            // Single vs Thread have different composition / layout
            // shapes, so distinct contentTypes let the LazyColumn reuse
            // pool serve each kind from its own slot — Single rows
            // entering view don't pay the cost of a slot that just held
            // a Thread (or vice versa).
            contentType = { if (it is FeedItem.Thread) "thread" else "single" },
        ) { item ->
            // "Unread starts here" divider rendered above the first
            // item that contains an unread post (OldestUnreadFirst
            // only). Composed at the start of the item lambda so it
            // sticks to the same FeedItem in LazyColumn — pull-to-
            // refresh updates the boundary cursors and the rule moves
            // naturally with the new snapshot.
            if (item.key == unreadBoundaryKey) {
                UnreadBoundaryRow()
            }
            // Per-item [derivedStateOf] reads [centeredItemKeyState]
            // INSIDE its lambda, not at the items() body level — so the
            // centre-flip stream invalidates only the two items whose
            // boolean output actually changed (old centre → false, new
            // centre → true). The previous `mutableStateOf` + write-
            // during-composition pattern was an antipattern (state
            // mutation in composition phase) and didn't even achieve
            // the localised-recomposition it claimed: every items()
            // lambda read centeredItemKey directly, so all visible item
            // lambdas re-ran on every centre flip during scroll.
            val isCenteredState = remember(item.key) {
                derivedStateOf { centeredItemKeyState.value == item.key }
            }
            val highlighted = highlightedPostKey?.let { (cid, mid) ->
                item.posts().any { p ->
                    p.chatId == cid && (p.id == mid || mid in p.albumMessageIds)
                }
            } == true
            CompositionLocalProvider(
                LocalIsCenteredItem provides isCenteredState,
                LocalIsHighlightedItem provides highlighted,
            ) {
                when (item) {
                    is FeedItem.Single -> PostCard(
                        post = item.post,
                        interactions = interactions,
                    )
                    is FeedItem.Thread -> ThreadedPostPair(
                        parent = item.parent,
                        reply = item.reply,
                        interactions = interactions,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineTopBar(
    showOnlyBookmarked: Boolean,
    onBrandTap: () -> Unit,
    onGlobalSearchClick: (() -> Unit)?,
    topBarBadge: (@Composable () -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    // Status-bar insets are owned by the persistent zone-1 strip in the
    // Scaffold's topBar slot — passing [WindowInsets] of 0 here prevents the
    // bar from doubling up on top padding (and keeps its content from
    // travelling into the system status-bar area when the layout shrinker
    // pushes the bar upward on scroll).
    val barInsets = WindowInsets(0)
    // Both destinations (Bookmarks and Home) are top-level: M3 Expressive
    // canon uses the Medium-size flexible bar — larger title on first paint
    // that collapses to compact 64 dp on scroll. Tool stages (search,
    // channel filter) now live in the dedicated [ChannelScreen].
    if (showOnlyBookmarked) {
        HortayTopBar(
            title = stringResource(R.string.timeline_saved_tab),
            size = HortayTopBarSize.Medium,
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
        )
    } else {
        HortayTopBar(
            title = {
                Box(modifier = Modifier.clickable(role = Role.Button, onClick = onBrandTap)) {
                    BrandRow()
                }
            },
            size = HortayTopBarSize.Medium,
            actions = {
                onGlobalSearchClick?.let { handler ->
                    IconButton(onClick = handler) {
                        Symbol(
                            name = "search",
                            contentDescription = stringResource(R.string.web_search_action),
                        )
                    }
                }
                // Trailing slot for mode-specific chips (e.g. guest-mode
                // badge). Rendered after the search action so it sits at the
                // edge of the bar, where users expect status indicators.
                topBarBadge?.invoke()
            },
            scrollBehavior = scrollBehavior,
            windowInsets = barInsets,
        )
    }
}
