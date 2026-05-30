@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
                    // Captured ABSOLUTE screen-Y of this card's top, refreshed on every
                    // (re)layout via onGloballyPositioned. Tapping the post or its "Показати
                    // більше" opens the full post (comments) hero pinned to this exact Y, so
                    // the post stays where it sits and the feed behind it dims — works the
                    // same in both feed orders because it's a real on-screen coordinate, not
                    // the reverseLayout-flipped LazyListItemInfo.offset. floatArray (not
                    // State) so the per-frame position writes don't recompose the card.
                    val topY = remember(item.key) { floatArrayOf(0f) }
                    // Hero-open wiring only when a handler exists (auth feed). In guest mode
                    // onShowFull is null, so "Показати більше" falls back to inline expand and
                    // a tap keeps the screen's own onPostClick (guest sign-in snackbar).
                    val showFull = remember(post, interactions, topY) {
                        interactions.onShowFull?.let { hero -> { hero(post, topY[0].toInt()) } }
                    }
                    val itemInteractions = remember(post, interactions, topY) {
                        val hero = interactions.onShowFull
                        if (hero != null) {
                            interactions.copy(onPostClick = { hero(post, topY[0].toInt()) })
                        } else {
                            interactions
                        }
                    }
                    CompositionLocalProvider(
                        LocalIsCenteredItem provides isCenteredState,
                        LocalIsHighlightedItem provides highlighted,
                        LocalShowFullPost provides showFull,
                    ) {
                        Box(modifier = Modifier.onGloballyPositioned { topY[0] = it.positionInWindow().y }) {
                            PostCard(post = post, interactions = itemInteractions, onTapRevisions = onTapRevisions)
                        }
                    }
                }
            }
        }
    }
}

