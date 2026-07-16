@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.media.LocalIsCenteredItem
import dev.lyo.hortay.ui.media.LocalIsHighlightedItem
import dev.lyo.hortay.ui.text.LocalShowFullPost
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.delay

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
    // Reserves the collapsing header-overlay's full height (see TimelineScreen's overlay model);
    // the feed's old fixed 8dp top gap is folded into the caller's value. Default keeps the bare
    // 8dp for any caller that mounts the column without a floating header.
    topPadding: Dp = 8.dp,
    bottomPadding: Dp,
    feedItems: PersistentList<FeedItem>,
    centeredItemKeyState: State<Any?>,
    highlightedPostKey: Pair<Long, Long>?,
    interactions: PostInteractions,
    onTapRevisions: (dev.lyo.hortay.data.TimelinePost) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // J1 first-paint stagger: run ONCE per process, on the first cold feed mount —
    // NOT on tab returns (returning must paint in place, per the 0.9.0 "no skeleton
    // on tab return" contract) and NOT under reduced motion. We gate on a
    // process-global latch ([hasStaggeredThisProcess]) so a Feed → Channels → Feed
    // round-trip, which remounts this composable, does not replay the entrance.
    // The decision is latched in `remember` (stable for this composition's lifetime);
    // the global write happens in [SideEffect] — i.e. only after the composition is
    // APPLIED — so a discarded/speculative composition can't burn the one-shot.
    val reduced = rememberReducedMotion()
    val staggerEnabled = remember { !reduced && !hasStaggeredThisProcess }
    if (staggerEnabled) {
        SideEffect { hasStaggeredThisProcess = true }
    }
    // Stagger is scoped to the KEYS present at first mount, not to "whatever sits at
    // index < STAGGER_COUNT". `staggerEnabled` stays true for this composition's whole
    // lifetime, so an index-based gate replayed the entrance (alpha 0 + rise + delay)
    // on every LATER arrival landing in the top rows — accepted "new posts" blinked in
    // late and read as a glitch on the pill jump.
    val staggerKeys = remember {
        if (staggerEnabled) feedItems.take(STAGGER_COUNT).map { it.key }.toHashSet()
        else emptySet()
    }

    // Reading-width cap (tablets / desktop windowing): the LazyColumn is capped
    // to [READING_WIDTH_CAP] and centred inside the caller's box instead of
    // stretching edge to edge on wide windows. Below the cap (phones) this is
    // a no-op — [widthIn]'s max never binds tighter than the box's own width,
    // so the column still measures to fillMaxWidth() edge to edge exactly as
    // before this cap existed, and `align(TopCenter)` has no slack to centre
    // into. Scroll state, keys, reverseLayout and the anchoring logic in this
    // file are untouched — only the LazyColumn's own width/position changed.
    // Overlaid controls (arrivals pill, unread FAB, the collapsing header
    // overlay) are rendered by the caller ([TimelineScreen]) as siblings
    // outside this composable's [Box] and keep aligning to the window edge,
    // not this capped column — seen as an accepted caveat on wide windows
    // rather than a change made here (see Task 6 report).
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            reverseLayout = reverseLayout,
            contentPadding = PaddingValues(
                top = topPadding,
                bottom = bottomPadding,
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = READING_WIDTH_CAP)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            itemsIndexed(
                items = feedItems,
                key = { _, item -> item.key },
                contentType = { _, item ->
                    when (item) {
                        is FeedItem.Boundary -> "boundary"
                        is FeedItem.Post -> "post"
                    }
                },
            ) { index, item ->
                // M4 animateItem is fully RETIRED after two retreats — do not reintroduce.
                // (1) fade specs: a disappearing row faded out IN PLACE over the re-laid-out
                // list — "phantom channel avatars next to the wrong post". (2) placement
                // spec: placement springs fire on ANY relayout (live ingest at index 0,
                // reaction rows appearing, media height settling), and Compose keeps
                // drawing a mid-animation row even outside the viewport — during a fast
                // fling rows visibly lagged behind the scroll as phantom posts/avatars,
                // and on arrivals-pill accept the springs fought smartScrollTo's viewport
                // jump. Rows reflow instantly; keyed-scroll preservation still pins the
                // user's anchor item.
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
                        // J1: first-paint stagger — the first STAGGER_COUNT cards fade in
                        // and rise 12 dp, each delayed index × STAGGER_STEP_MS, ONCE per
                        // process. Membership is by first-mount KEY (see [staggerKeys]),
                        // so later arrivals occupying the same top indices never replay it.
                        val staggerThisItem = staggerEnabled && item.key in staggerKeys
                        val staggerModifier = if (staggerThisItem) {
                            rememberStaggerEntrance(itemKey = item.key, index = index)
                        } else {
                            Modifier
                        }
                        CompositionLocalProvider(
                            LocalIsCenteredItem provides isCenteredState,
                            LocalIsHighlightedItem provides highlighted,
                            LocalShowFullPost provides showFull,
                        ) {
                            Box(
                                modifier = staggerModifier
                                    .onGloballyPositioned { topY[0] = it.positionInWindow().y },
                            ) {
                                PostCard(post = post, interactions = itemInteractions, onTapRevisions = onTapRevisions)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Per-item fade+rise entrance for the J1 first-paint stagger. Keyed on the item's
 * stable key so the animation runs exactly once per row (a recomposition reuses the
 * latched `appeared` state and the spring rests at its target). The reveal is delayed
 * [index] × [STAGGER_STEP_MS] so the first cards cascade in.
 */
@Composable
private fun LazyItemScope.rememberStaggerEntrance(itemKey: Any, index: Int): Modifier {
    var appeared by remember(itemKey) { mutableStateOf(false) }
    LaunchedEffect(itemKey) {
        delay(index * STAGGER_STEP_MS)
        appeared = true
    }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "stagger-$index",
    )
    return Modifier.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * STAGGER_RISE_DP.toPx()
    }
}

/** First-paint stagger: how many leading cards animate in. */
private const val STAGGER_COUNT = 8

/** Per-card stagger delay. */
private const val STAGGER_STEP_MS = 25L

/** Rise distance — cards lift 12 dp into place. */
private val STAGGER_RISE_DP = 12.dp

/** Process-global one-shot latch for the J1 first-paint stagger (see usage KDoc). */
private var hasStaggeredThisProcess = false

/**
 * Reading-width cap for the feed's LazyColumn on tablets / desktop windowing.
 * Internal (not private) so [ChannelScreen] — same package — caps its own
 * post list to the same value; feed and channel read consistently on wide
 * windows. 640 dp comfortably fits a post card's photo/table/code-block
 * content while stopping the card from stretching into an unreadably wide
 * line-length on large-screen or freeform-windowed layouts.
 */
internal val READING_WIDTH_CAP = 640.dp

