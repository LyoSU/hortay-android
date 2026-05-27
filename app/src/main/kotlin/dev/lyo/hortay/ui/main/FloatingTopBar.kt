package dev.lyo.hortay.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Twitter / Instagram floating-bar pattern, extracted from the two screens
 * (Timeline + Comments) that previously kept identical 30-LOC copies of the
 * same [NestedScrollConnection] body.
 *
 * Behaviour, as documented at the original call site in TimelineScreen:
 * every pixel of upward content scroll moves the bar one pixel further out
 * of view (down to `-topBarFullHeightPx`); every pixel of downward scroll at
 * the top of the list moves it back. The bar's measured height is shrunk in
 * lockstep via [Modifier.layout] in the caller — same scroll delta drives
 * both the visual offset AND Scaffold's body padding, so they never desync
 * (which is what made the canonical M3 [exitUntilCollapsedScrollBehavior]
 * jolt during transitions).
 *
 * The `enabled` lambda is read live via [rememberUpdatedState] so a caller
 * that toggles it (Timeline does — the bar stays pinned during channel
 * filter / search-inside-filter tool stages) doesn't have to re-allocate
 * the connection.
 */
class FloatingTopBarBehavior(
    val fullHeightPx: MutableFloatState,
    val offsetPx: MutableFloatState,
    val nestedScroll: NestedScrollConnection,
)

@Composable
fun rememberFloatingTopBarBehavior(
    enabled: () -> Boolean = { true },
): FloatingTopBarBehavior {
    val fullHeightPx = remember { mutableFloatStateOf(0f) }
    val offsetPx = remember { mutableFloatStateOf(0f) }
    val enabledState = rememberUpdatedState(enabled)
    val nestedScroll = remember(fullHeightPx, offsetPx) {
        object : NestedScrollConnection {
            // Pure gesture-delta hide-on-scroll, identical in both feed orders.
            // `available.y` is a raw screen-space pointer delta — `reverseLayout`
            // does NOT transform it. Progressing through the feed (reading) is a
            // finger-up gesture (`available.y < 0`) regardless of order: in Newest
            // you scroll down into older posts; in OldestUnreadFirst the newest sit
            // at the visual bottom, so advancing toward them also brings lower
            // content up — finger up. So hide on negative delta and reveal on
            // positive in BOTH cases; no direction-dependent fold (an earlier
            // reverse-aware `dir` factor inverted this and was reverted).
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabledState.value()) return Offset.Zero
                if (available.y >= 0f) return Offset.Zero
                val limit = -fullHeightPx.floatValue
                if (limit == 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                val next = (previous + available.y).coerceIn(limit, 0f)
                if (next == previous) return Offset.Zero
                offsetPx.floatValue = next
                return Offset(0f, next - previous)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!enabledState.value()) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                if (previous == 0f) return Offset.Zero
                val next = (previous + available.y).coerceIn(-fullHeightPx.floatValue, 0f)
                if (next == previous) return Offset.Zero
                offsetPx.floatValue = next
                return Offset(0f, next - previous)
            }
        }
    }
    return remember(fullHeightPx, offsetPx, nestedScroll) {
        FloatingTopBarBehavior(fullHeightPx, offsetPx, nestedScroll)
    }
}
