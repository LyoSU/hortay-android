@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TimelinePost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fraction of an item's pixel span that must be visible inside the (padding-tightened)
 * viewport for [READ_DWELL_MS] before the post counts as read.
 *
 * 0.6 is the canonical chat-app threshold (Slack ~0.5, Telegram ~0.6). Two reasons we
 * sit at 0.6 and not the stricter "fully visible":
 *   1. Landings from the divider-anchored jump (and any centred legacy path) could
 *      leave 2-3 px of clip at the top/bottom edge due to layout-pass rounding;
 *      "fully visible" would never satisfy and the post would stay un-acked until
 *      the user scrolled further. This is the user-reported bug the rewrite fixes.
 *   2. Long-form posts (taller than the viewport) need a fallback — 0.6 captures
 *      both regimes uniformly without the dual `fullyVisible || dominatesViewport`
 *      split the legacy code carried.
 */
private const val READ_DWELL_VISIBLE_FRACTION = 0.6f

/**
 * Locates the (chatId, messageId) target in [items]. Returns the row index, or
 * -1 when not found. Albums hit on any member id.
 */
internal fun resolveTargetIndex(
    items: List<FeedItem>,
    chatId: Long,
    messageId: Long,
): Int = items.indexOfFirst { item ->
    item.posts().any { p ->
        p.chatId == chatId && (p.id == messageId || messageId in p.albumMessageIds)
    }
}

/**
 * Resolves a deferred "scroll to (chatId, messageId)" request once the target row
 * appears in [displayedItems]. On first miss, runs [loadHistoryAround] exactly
 * once. If the target still isn't present after the next snapshot emission within
 * [loadGraceMs], [onMissed] fires — prevents the silent-hang failure mode where
 * loadHistoryAround returned true but PostFilterStrategy / grouping pruned the
 * target out of [displayedItems].
 *
 * @param displayedItems Current LazyColumn data source; read through
 *   [rememberUpdatedState] internally so a long-running snapshotFlow sees fresh values
 *   without restarting on every list mutation.
 * @param pendingTarget (chatId, messageId) to scroll to, or null when idle. Effect
 *   keys on this — replacement target restarts the search.
 * @param loadHistoryAround TDLib `GetChatHistory(from = anchor, offset = -limit/2)`
 *   wrapper; returns false when the chat is inaccessible / window came back empty.
 * @param onLanded Called once with the resolved row index when the target appears.
 *   Caller is responsible for the actual scroll, highlight, and clearing
 *   [pendingTarget].
 * @param onMissed Called once when [loadHistoryAround] returns false OR when the
 *   grace window elapses after a successful load without the target appearing.
 *   Caller surfaces a "message not available" message and clears [pendingTarget].
 * @param loadGraceMs Grace window after a successful [loadHistoryAround] call. If the
 *   target doesn't appear in [displayedItems] within this many milliseconds,
 *   [onMissed] fires. Prevents silent-hang when PostFilterStrategy / album grouping
 *   prunes the target from the rendered list.
 */
@Composable
fun rememberPendingScrollToMessage(
    displayedItems: List<FeedItem>,
    pendingTarget: Pair<Long, Long>?,
    loadHistoryAround: suspend (chatId: Long, messageId: Long) -> Boolean,
    onLanded: suspend (chatId: Long, messageId: Long, index: Int) -> Unit,
    onMissed: () -> Unit,
    loadGraceMs: Long = 1500L,
) {
    val itemsState = rememberUpdatedState(displayedItems)
    LaunchedEffect(pendingTarget) {
        val (chatId, messageId) = pendingTarget ?: return@LaunchedEffect
        // First pass: target may already be in [displayedItems] from a prior
        // global feed harvest — try a single resolve before issuing an RPC.
        val initialIndex = resolveTargetIndex(itemsState.value, chatId, messageId)
        if (initialIndex >= 0) {
            onLanded(chatId, messageId, initialIndex)
            return@LaunchedEffect
        }
        // Miss: issue around-load. False return = chat inaccessible / window empty.
        val landed = loadHistoryAround(chatId, messageId)
        if (!landed) {
            onMissed()
            return@LaunchedEffect
        }
        // Around-load succeeded. Race the next snapshot emission against a grace
        // timeout. If a new emission contains the target → land. If grace elapses
        // first → onMissed (target got filtered out by PostFilterStrategy or by
        // album grouping that pruned a single member).
        val landedIndex = withTimeoutOrNull(loadGraceMs) {
            snapshotFlow { itemsState.value }
                .map { resolveTargetIndex(it, chatId, messageId) }
                .first { it >= 0 }
        }
        if (landedIndex != null) {
            onLanded(chatId, messageId, landedIndex)
        } else {
            onMissed()
        }
    }
}

/**
 * Viewport-stable read-ack dwell. Waits [dwellMs] of viewport stability with the
 * list **idle**, then dispatches [markAsRead] for the posts that are *fully* on
 * screen and haven't already been acked.
 *
 * "Fully on screen" = the post's bounds sit entirely inside
 * `[viewportStartOffset, viewportEndOffset]`. Loosely-visible items (the row
 * peeking 5–20% out the bottom edge after a `smartScrollTo`, the row just sliding
 * off the top during a flick) are excluded — counting them as "read" caused the
 * "tap ↓ N → next tap skips a post" symptom: the scroll landed on X, the next
 * row X+1 got dwell-acked while still partially visible, so the next jump
 * resolved to X+2 instead of X+1.
 *
 * "List idle" = [LazyListState.isScrollInProgress] is false. Mirrors
 * Telegram-Android's `SCROLL_STATE_IDLE` gate before `messages.readHistory`. A
 * fully-visible row during a slow drag would otherwise satisfy the dwell window
 * even though the user is on their way past it; an active fling that drops out
 * mid-screen would do the same. Gating on idle defers the timer until motion
 * settles, matching the user-intent reading of "I stopped here".
 *
 * Posts taller than the viewport can never satisfy strict containment — they get
 * a fallback: counted as visible while at least half the viewport is occupied by
 * the post. Without it, long-form posts would never get marked read.
 *
 * @param listState Drives the viewport snapshotFlow.
 * @param displayedItems Current LazyColumn data source; read through
 *   [rememberUpdatedState] so the collector picks up fresh feed mutations without
 *   restarting.
 * @param ackKey Reset key for the dedup set — pass `markAsRead` for the merged
 *   feed (resets the set when the mode-specific wrapper changes), pass `chatId`
 *   for a single-channel surface.
 * @param markAsRead Suspend dispatcher for `viewMessages(forceRead = true)`.
 *   Null disables the effect (e.g. guest mode without TDLib).
 * @param scope Caller's [CoroutineScope]; the suspending [markAsRead] is detached
 *   into it so a viewport change a frame later doesn't cancel an in-flight ack.
 * @param dwellMs Stability threshold; matches `READ_DWELL_MS` / `CHANNEL_READ_DWELL_MS`.
 * @return The same dedup set the effect populates, so callers can extend it from
 *   explicit-tap acks ("open this post") that should suppress the next dwell pass
 *   for that post.
 */
@Composable
fun rememberReadAckDwell(
    listState: LazyListState,
    displayedItems: List<FeedItem>,
    ackKey: Any?,
    markAsRead: (suspend (List<TimelinePost>) -> Unit)?,
    scope: CoroutineScope,
    dwellMs: Long = 500L,
): MutableSet<Pair<Long, Long>> {
    val ackedRead = remember(ackKey) { HashSet<Pair<Long, Long>>() }
    val itemsState = rememberUpdatedState(displayedItems)
    // Gate the ack on the screen actually being foreground+interactive. A post left
    // in the viewport when the user locks the phone or backgrounds the app would
    // otherwise get marked read by the dwell timer firing against a composition that
    // is still alive but not on screen. We can't detect "eyes off a lit screen", but
    // screen-off / backgrounded is a real, fixable false-read window — see CHANGELOG
    // "A post you're viewing no longer gets marked as read while the screen is off".
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (markAsRead != null) {
        LaunchedEffect(listState, ackKey) {
            // Track viewport by item KEY, not by index. During the dwell delay a
            // fresh post can insert above the visible window (UpdateNewMessage)
            // or the feed can re-sort (pull-to-refresh re-snapshot in
            // OldestUnreadFirst), shifting the same index to a different post —
            // dwell-by-index would then mark-as-read posts the user never
            // actually saw. Key-based lookup keeps the ack honest: a key that's
            // no longer in the rendered list silently drops out.
            snapshotFlow {
                val info = listState.layoutInfo
                // Tighten the bounds by the LazyColumn's contentPadding so the
                // user-visible items area is what we measure against — not
                // Compose's wider "any item that overlaps the container".
                // Hortay's feed runs under a floating bottom NavBar whose
                // height is reflected as the LazyColumn's afterContentPadding;
                // without this trim, a post whose bottom edge has scrolled into
                // the NavBar's reserved slot would still satisfy strict
                // containment (its bottom is `<= viewportEndOffset`, which
                // doesn't subtract afterContentPadding) even though that slice
                // of the post is visually hidden behind the bar.
                val vStart = info.viewportStartOffset + info.beforeContentPadding
                val vEnd = info.viewportEndOffset - info.afterContentPadding
                val scrolling = listState.isScrollInProgress
                val keys = info.visibleItemsInfo.mapNotNull { item ->
                    val fraction = visibleFraction(
                        itemStart = item.offset,
                        itemEnd = item.offset + item.size,
                        vStart = vStart,
                        vEnd = vEnd,
                    )
                    if (fraction >= READ_DWELL_VISIBLE_FRACTION) item.key else null
                }
                // resumed is part of the emitted tuple so toggling foreground
                // re-arms the dwell: a post skipped while backgrounded gets a
                // fresh ack window when the user returns and it's still visible.
                val resumed = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
                Triple(keys, scrolling, resumed)
            }
                .distinctUntilChanged()
                .collectLatest { (keys, scrolling, resumed) ->
                    // Skip while motion is in progress; the next emission once
                    // the list settles will re-arm the dwell with the
                    // post-settle fully-visible set. Skip while not resumed so a
                    // backgrounded/locked screen never auto-reads the visible row.
                    if (keys.isEmpty() || scrolling || !resumed) return@collectLatest
                    delay(dwellMs)
                    // The user can lock or background the app during the dwell
                    // window — re-check before committing the ack.
                    if (!lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)) {
                        return@collectLatest
                    }
                    val snapshot = itemsState.value
                    if (snapshot.isEmpty()) return@collectLatest
                    val keySet = keys.toHashSet()
                    val visible = snapshot
                        .filter { it.key in keySet }
                        .flatMap { it.posts() }
                    val fresh = visible.filter { (it.chatId to it.id) !in ackedRead }
                    if (fresh.isEmpty()) return@collectLatest
                    fresh.forEach { ackedRead.add(it.chatId to it.id) }
                    scope.launch { markAsRead(fresh) }
                }
        }
    }
    return ackedRead
}

/**
 * Warms the comments-thread cache for the top-N viewport-stable posts. Skips
 * silently while [startupPhase] is `Booting` so the cold-start RPC budget stays
 * reserved for TDLib's own initial sync.
 *
 * @param listState Drives the viewport snapshotFlow.
 * @param displayedItems Current LazyColumn data source; read through
 *   [rememberUpdatedState] so layoutInfo indices always pair against the latest
 *   rendered list.
 * @param startupPhase Process-wide cold-start gate; null = unguarded (guest mode).
 * @param prefetchThread Per-post prefetch dispatcher (typically
 *   `commentsRepo.prefetchThread`). Receives (chatId, candidateMessageIds).
 * @param debounceMs Viewport-stable debounce; matches `1200L` in both screens.
 * @param maxConcurrent Cap on prefetches per stable window; matches the
 *   `COMMENTS_PREFETCH_LIMIT` (1) used in both screens by default.
 */
@Composable
fun rememberCommentsPrefetch(
    listState: LazyListState,
    displayedItems: List<FeedItem>,
    startupPhase: StateFlow<StartupCoordinator.Phase>?,
    prefetchThread: suspend (chatId: Long, candidateMessageIds: List<Long>) -> Unit,
    debounceMs: Long = 1200L,
    maxConcurrent: Int = 1,
) {
    val itemsState = rememberUpdatedState(displayedItems)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .debounce(debounceMs)
            .collect { indices ->
                if (startupPhase?.value == StartupCoordinator.Phase.Booting) {
                    return@collect
                }
                val snapshot = itemsState.value
                snapshot.let {
                    indices.flatMap { idx -> snapshot.getOrNull(idx)?.posts().orEmpty() }
                        .asSequence()
                        .filter { (it.commentCount ?: 0) > 0 }
                        .take(maxConcurrent)
                        .forEach { post ->
                            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
                            prefetchThread(post.chatId, ids)
                        }
                }
            }
    }
}
