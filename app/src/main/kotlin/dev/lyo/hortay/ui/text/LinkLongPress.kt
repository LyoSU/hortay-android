package dev.lyo.hortay.ui.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drop-in modifier that overlays a [Text] composable to drive BOTH tap and long-press
 * on inline links — the two gestures we own ourselves once the touch lands over a
 * character that's part of [linkRanges].
 *
 * Why this needs to be a custom modifier instead of just
 * [androidx.compose.ui.text.LinkAnnotation.Url]:
 *
 *   - Compose's built-in `LinkAnnotation` handler only models tap. Long-press has no
 *     hook, and adding one upstream would be a Compose-foundation change.
 *
 *   - A naïve `Modifier.pointerInput { detectTapGestures(onLongPress = ...) }` would
 *     race the PostCard's parent `combinedClickable.onLongClick` (post action sheet) —
 *     both timers fire at ~500ms and we'd see both sheets stack on link long-press.
 *
 *   - Our solution: consume the `down` on the Initial pass the moment we hit-test it
 *     against a link character. That kills BOTH the parent combinedClickable
 *     (`awaitFirstDown(requireUnconsumed = true)`) AND the Text's internal
 *     `LinkAnnotation` clickable, which leaves us with full ownership of the gesture
 *     stream. We then implement tap (release within slop and under the long-press
 *     timeout) and long-press (release after timeout) ourselves.
 *
 * If the touch lands OUTSIDE any link range, the modifier doesn't consume — the parent
 * combinedClickable sees the gesture normally and the post action sheet flow keeps
 * working unchanged. This is the same gating discipline the project already uses for
 * text selection ("gated on expanded surfaces") and gives us a no-conflict ladder:
 *
 *   tap on link        → onTap          (this modifier)
 *   long-press on link → onLongPress    (this modifier)
 *   tap elsewhere      → PostCard onTap (combinedClickable)
 *   long-press elsewhere → PostCard onLongClick (combinedClickable)
 */
@Composable
fun Modifier.linkLongPress(
    linkRanges: List<LinkRange>,
    layoutResult: TextLayoutResult?,
    onTap: (LinkRange) -> Unit,
    onLongPress: (LinkRange) -> Unit,
): Modifier {
    if (linkRanges.isEmpty()) return this
    val cfg = LocalViewConfiguration.current
    return this.pointerInput(linkRanges, layoutResult, cfg) {
        val layout = layoutResult ?: return@pointerInput
        val longPressTimeout = cfg.longPressTimeoutMillis
        val touchSlopSq = cfg.touchSlop * cfg.touchSlop
        handleLinkGestures(layout, linkRanges, longPressTimeout, touchSlopSq, onTap, onLongPress)
    }
}

private suspend fun PointerInputScope.handleLinkGestures(
    layout: TextLayoutResult,
    linkRanges: List<LinkRange>,
    longPressTimeoutMs: Long,
    touchSlopSq: Float,
    onTap: (LinkRange) -> Unit,
    onLongPress: (LinkRange) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // getOffsetForPosition throws on out-of-bounds positions in some Compose builds
        // (touching just outside the laid-out glyph rectangle) — defend with runCatching.
        val offset = runCatching { layout.getOffsetForPosition(down.position) }.getOrNull()
            ?: return@awaitEachGesture
        val range = linkRanges.firstOrNull { offset in it.start until it.end }
            ?: return@awaitEachGesture

        // Hit-test passed: we own this gesture. Consuming on Initial pass propagates the
        // consumption flag downstream before LinkAnnotation's Main-pass clickable AND
        // the ancestor combinedClickable see the down event.
        down.consume()

        val outcome = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                change.consume()
                if (!change.pressed) {
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    return@withTimeoutOrNull if (dx * dx + dy * dy > touchSlopSq) {
                        PressOutcome.Cancelled
                    } else {
                        PressOutcome.Tap
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") PressOutcome.Cancelled
        } ?: PressOutcome.LongPress

        when (outcome) {
            PressOutcome.Tap -> onTap(range)
            PressOutcome.LongPress -> onLongPress(range)
            PressOutcome.Cancelled -> Unit
        }
    }
}

private enum class PressOutcome { Tap, LongPress, Cancelled }
