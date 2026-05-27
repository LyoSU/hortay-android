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
    reverseLayout: () -> Boolean = { false },
): FloatingTopBarBehavior {
    val fullHeightPx = remember { mutableFloatStateOf(0f) }
    val offsetPx = remember { mutableFloatStateOf(0f) }
    val enabledState = rememberUpdatedState(enabled)
    val reverseState = rememberUpdatedState(reverseLayout)
    val nestedScroll = remember(fullHeightPx, offsetPx) {
        object : NestedScrollConnection {
            // In a reverse / chat layout the gesture↔reading-direction relationship
            // is flipped: reading forward (toward newer) is a downward drag, so the
            // bar must hide on the OPPOSITE delta sign from the top-down case. `dir`
            // folds that inversion into one factor — the hide/show math and the amount
            // of scroll the bar consumes stay identical, only the trigger direction
            // flips. Read live via [rememberUpdatedState] so toggling feed order
            // doesn't re-allocate the connection.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabledState.value()) return Offset.Zero
                val dir = if (reverseState.value()) -1f else 1f
                val dy = available.y * dir
                if (dy >= 0f) return Offset.Zero
                val limit = -fullHeightPx.floatValue
                if (limit == 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                val next = (previous + dy).coerceIn(limit, 0f)
                if (next == previous) return Offset.Zero
                offsetPx.floatValue = next
                return Offset(0f, (next - previous) * dir)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!enabledState.value()) return Offset.Zero
                val dir = if (reverseState.value()) -1f else 1f
                val dy = available.y * dir
                if (dy <= 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                if (previous == 0f) return Offset.Zero
                val next = (previous + dy).coerceIn(-fullHeightPx.floatValue, 0f)
                if (next == previous) return Offset.Zero
                offsetPx.floatValue = next
                return Offset(0f, (next - previous) * dir)
            }
        }
    }
    return remember(fullHeightPx, offsetPx, nestedScroll) {
        FloatingTopBarBehavior(fullHeightPx, offsetPx, nestedScroll)
    }
}
