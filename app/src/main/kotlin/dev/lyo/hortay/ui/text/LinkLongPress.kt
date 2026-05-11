package dev.lyo.hortay.ui.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-press hit-tester for inline links inside a [androidx.compose.material3.Text].
 * Tap stays with the built-in `LinkAnnotation.Url` listener (preserves ripple +
 * `LocalUriHandler` routing).
 *
 * Implementation notes — this is the second iteration; the failed previous attempts
 * are recorded here because the trade-offs aren't obvious:
 *
 *  - **Don't use `detectTapGestures`** — that helper calls `down.consume()` the
 *    moment a gesture starts, regardless of whether any callback fires. That
 *    consumption is observed by the PostCard parent's
 *    `combinedClickable.onLongClick` (it uses `awaitFirstDown(requireUnconsumed = true)`)
 *    AND by the Text's internal LinkAnnotation tap detector. The net effect was that
 *    enabling this modifier killed BOTH parent long-press (post action sheet) AND
 *    child tap (link open). Hand-rolled gesture detection lets us stay non-consuming
 *    on every path except the long-press fire.
 *
 *  - **`requireUnconsumed = false`** on the initial down so siblings that consume the
 *    same down event (the PostCard's combinedClickable does, after dispatching its
 *    indication ripple) don't lock us out. We're a parallel observer until our timer
 *    elapses.
 *
 *  - On move past slop or release before the timeout, exit the gesture without
 *    consuming — Compose's scroll detector and LinkAnnotation tap detector each
 *    continue running with the same events.
 *
 *  - Only when the timer expires with the finger still held inside the link's
 *    character range do we consume the current event. That race-wins against
 *    `combinedClickable.onLongClick` which is firing the same instant — the inner
 *    handler's consumption marks the gesture used before the outer one commits.
 */
@Composable
fun Modifier.linkLongPress(
    linkRanges: List<LinkRange>,
    layoutResult: TextLayoutResult?,
    onLongPress: (LinkRange) -> Unit,
): Modifier {
    if (linkRanges.isEmpty()) return this
    val cfg = LocalViewConfiguration.current
    return this.pointerInput(linkRanges, layoutResult, cfg) {
        val layout = layoutResult ?: return@pointerInput
        watchLinkLongPress(layout, linkRanges, cfg.longPressTimeoutMillis, cfg.touchSlop * cfg.touchSlop, onLongPress)
    }
}

private suspend fun PointerInputScope.watchLinkLongPress(
    layout: TextLayoutResult,
    linkRanges: List<LinkRange>,
    longPressTimeoutMs: Long,
    touchSlopSq: Float,
    onLongPress: (LinkRange) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val offset = runCatching { layout.getOffsetForPosition(down.position) }.getOrNull()
            ?: return@awaitEachGesture
        val range = linkRanges.firstOrNull { offset in it.start until it.end }
            ?: return@awaitEachGesture

        val pointerId = down.id
        val released: Boolean? = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                if (!change.pressed) return@withTimeoutOrNull true
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (dx * dx + dy * dy > touchSlopSq) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (released == null) {
            currentEvent.changes.forEach { it.consume() }
            onLongPress(range)
            // Drain the rest of the gesture — consume every change until the finger
            // lifts. Without this, the user's eventual release reaches LinkAnnotation's
            // tap detector and fires onClick, opening the link a beat after our menu
            // already showed ("і посилання саме одразу за контекстним меню").
            // Consuming through to up marks the whole gesture as ours.
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}
