package dev.lyo.hortay.ui.main

import androidx.compose.animation.core.animate
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberFloatingTopBarBehavior(
    enabled: () -> Boolean = { true },
): FloatingTopBarBehavior {
    val fullHeightPx = remember { mutableFloatStateOf(0f) }
    val offsetPx = remember { mutableFloatStateOf(0f) }
    val enabledState = rememberUpdatedState(enabled)
    // Settle animation: at the end of a gesture the bar snaps to fully open or fully closed so it
    // never rests half-hidden. Runs on the composition scope (not the nested-scroll dispatch) so a
    // new grab can cancel it; rides MotionScheme so the snap matches the app's motion language.
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val nestedScroll = remember(fullHeightPx, offsetPx, scope, settleSpec) {
        object : NestedScrollConnection {
            // The in-flight settle animation, cancelled the moment the user grabs the scroll again
            // so the finger always wins over the snap.
            var settleJob: Job? = null

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
                settleJob?.cancel()
                val limit = -fullHeightPx.floatValue
                if (limit == 0f) return Offset.Zero
                val previous = offsetPx.floatValue
                val next = (previous + available.y).coerceIn(limit, 0f)
                if (next == previous) return Offset.Zero
                offsetPx.floatValue = next
                return Offset(0f, next - previous)
            }

            // Gesture end (drag release always flings, even at ~0 velocity): snap the bar to the
            // nearer edge so it never lingers partly hidden. Past the half-way point → close, else
            // → open. A no-op when already settled (target == current), so it costs nothing for the
            // common mid-list fling where the bar is already fully open or fully closed.
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val full = fullHeightPx.floatValue
                if (full > 0f) {
                    val current = offsetPx.floatValue
                    val target = if (current <= -full / 2f) -full else 0f
                    if (current != target) {
                        settleJob?.cancel()
                        settleJob = scope.launch {
                            animate(current, target, animationSpec = settleSpec) { value, _ ->
                                offsetPx.floatValue = value
                            }
                        }
                    }
                }
                return Velocity.Zero
            }
        }
    }
    return remember(fullHeightPx, offsetPx, nestedScroll) {
        FloatingTopBarBehavior(fullHeightPx, offsetPx, nestedScroll)
    }
}
