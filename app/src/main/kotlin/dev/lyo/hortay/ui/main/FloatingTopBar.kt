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
 * of view (down to `-topBarFullHeightPx`); every pixel of downward scroll
 * moves it back (enter-always — it reappears the instant you reverse, not only
 * at the top of the list). The bar's measured height is shrunk in
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
            // enter-always hide/reveal driven entirely in onPreScroll, symmetric in both
            // directions: scroll the content up (finger up, `available.y < 0`) and the bar slides
            // out one pixel per pixel; scroll down (finger down, `available.y > 0`) and it slides
            // straight back — so the brand bar reappears the instant you reverse, instead of only
            // at the very top of the list. (The previous split — hide in onPreScroll, reveal only
            // in onPostScroll — meant the bar revealed solely when the list could no longer consume
            // the downward scroll, i.e. at the top edge, so mid-list it stayed hidden and read as
            // "appears at unpredictable times".)
            //
            // `available.y` is a raw screen-space pointer delta — `reverseLayout` does NOT transform
            // it, so the same sign rule holds in both feed orders (an earlier reverse-aware `dir`
            // factor inverted this and was reverted). The bar only consumes scroll while it is
            // actually moving (the `next == previous` guard returns Zero once fully open/closed), so
            // it never steals scroll from a list that is already at rest against the bar.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabledState.value()) return Offset.Zero
                val limit = -fullHeightPx.floatValue
                if (limit == 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                val next = (previous + available.y).coerceIn(limit, 0f)
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
