package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.compositionLocalOf
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
 * (see [topAlignDelta]): a positive [LazyListState.scrollBy] decreases item offsets in BOTH
 * layout directions, so restoring the pre-expand offset is just `scrollBy(after - before)`.
 * Forward layout keeps the top anchored on its own — the offset doesn't change, the wait
 * times out, and nothing scrolls.
 */
internal class ExpandScrollRetainer(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    /** Pin the item with [itemKey]'s top across the expansion it's about to undergo. */
    fun retainTop(itemKey: Any) {
        val before = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == itemKey }?.offset ?: return
        scope.launch {
            val after = withTimeoutOrNull(RETAIN_TIMEOUT_MS) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }?.offset
                }.filterNotNull().first { it != before }
            } ?: return@launch
            val delta = (after - before).toFloat()
            if (delta != 0f) listState.scrollBy(delta)
        }
    }
}

private const val RETAIN_TIMEOUT_MS = 400L

/**
 * Per-item callback that pins the current post's top right before it expands inline
 * ("Показати більше"). Supplied by the feed / channel LazyColumn (which owns the
 * [LazyListState]); `null` everywhere else, so [ExpandableText] just no-ops.
 */
internal val LocalExpandScrollKeeper = compositionLocalOf<(() -> Unit)?> { null }
