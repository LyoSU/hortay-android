package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a feed / channel post's TOP anchored when it grows from a "Показати більше"
 * expansion.
 *
 * In `reverseLayout` (Newest-at-bottom, the chat idiom) the LazyColumn anchors the BOTTOM,
 * so an expanding post grows UPWARD — its top scrolls off and the reader is dumped at the
 * post's end. This captures the post's laid-out top offset before the expand and, once the
 * relayout settles, nudges the scroll by the delta so the top stays put and the new lines
 * reveal downward.
 *
 * Same "measure reality, nudge by the difference" approach the rest of the feed scroll uses
 * (see [topAlignDelta]), but it must track the item's VISUAL TOP edge, not its raw
 * [androidx.compose.foundation.lazy.LazyListItemInfo.offset]. Under `reverseLayout` the
 * coordinate is flipped — `offset` is the item's visual BOTTOM edge, and `offset + size` is
 * its visual TOP (see the coordinate model in [topAlignDelta]). When the post grows, the
 * `reverseLayout` LazyColumn keeps the BOTTOM anchored, so `offset` doesn't move at all —
 * only the top (`offset + size`) climbs by the grown height. The earlier version watched
 * `offset`, which is invariant here, so the `first { it != before }` wait never fired, timed
 * out, and nothing scrolled — the post still grew upward and dumped the reader at its end.
 * Watching `offset + size` captures the real upward growth; a positive [LazyListState.scrollBy]
 * (which decreases offsets in both modes) then pushes the post back down by the delta so its
 * top stays pinned and the new lines reveal downward.
 *
 * Forward layout anchors the top on its own, so this is a reverse-only mechanism — we
 * early-return otherwise instead of launching a coroutine that would only time out.
 */
internal class ExpandScrollRetainer(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    /** Pin the item with [itemKey]'s top across the expansion it's about to undergo. */
    fun retainTop(itemKey: Any) {
        if (!listState.layoutInfo.reverseLayout) return
        val before = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == itemKey }
            ?.let { it.offset + it.size } ?: return
        scope.launch {
            val after = withTimeoutOrNull(RETAIN_TIMEOUT_MS) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == itemKey }
                        ?.let { it.offset + it.size }
                }.filterNotNull().first { it != before }
            } ?: return@launch
            val delta = (after - before).toFloat()
            if (delta != 0f) listState.scrollBy(delta)
        }
    }
}

private const val RETAIN_TIMEOUT_MS = 400L
