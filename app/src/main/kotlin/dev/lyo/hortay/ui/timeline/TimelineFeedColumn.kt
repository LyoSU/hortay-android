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
import dev.lyo.hortay.ui.text.LocalShowFullPost

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
    reverseLayout: Boolean,
    bottomPadding: Dp,
    feedItems: List<FeedItem>,
    centeredItemKeyState: State<Any?>,
    highlightedPostKey: Pair<Long, Long>?,
    interactions: PostInteractions,
    onTapRevisions: (dev.lyo.hortay.data.TimelinePost) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = state,
        flingBehavior = flingBehavior,
        reverseLayout = reverseLayout,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomPadding,
        ),
        modifier = modifier,
    ) {
        items(
            items = feedItems,
            key = { it.key },
            contentType = { item ->
                when (item) {
                    is FeedItem.Boundary -> "boundary"
                    is FeedItem.Post -> "post"
                }
            },
        ) { item ->
            when (item) {
                is FeedItem.Boundary -> UnreadBoundaryRow()
                is FeedItem.Post -> {
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
                    val post = item.post
                    val highlighted = highlightedPostKey?.let { (cid, mid) ->
                        post.chatId == cid && (post.id == mid || mid in post.albumMessageIds)
                    } == true
                    // "Показати більше" opens the post-detail (comments) screen — the same
                    // destination a plain tap on the card reaches via [onPostClick]. One
                    // predictable "open the post" action; no inline grow, no scroll nudge.
                    val showFull = remember(post, interactions) {
                        { interactions.onPostClick(post) }
                    }
                    CompositionLocalProvider(
                        LocalIsCenteredItem provides isCenteredState,
                        LocalIsHighlightedItem provides highlighted,
                        LocalShowFullPost provides showFull,
                    ) {
                        PostCard(post = post, interactions = interactions, onTapRevisions = onTapRevisions)
                    }
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
