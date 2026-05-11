package dev.lyo.hortay.ui.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Overlay modifier that listens for **long-press only** on inline links inside a
 * [Text]. Tap is intentionally NOT handled here — it falls through to the built-in
 * `LinkAnnotation.Url` listener, which preserves the visual ripple, hover state and
 * `LocalUriHandler` plumbing Compose already gives us for free.
 *
 * Why long-press-only:
 *
 *   - Owning *both* tap and long-press required consuming the `down` event on the
 *     Initial pass, which killed [androidx.compose.foundation.gestures] drag detectors
 *     up the tree. The LazyColumn lost the ability to scroll when a finger started on
 *     a link character — visibly broken UX on a tap-and-drag.
 *
 *   - Owning *only* long-press means we observe the down without consuming, start our
 *     own 500ms timer in parallel, and only consume once we know the user has held
 *     past the long-press threshold. Tap path stays untouched (LinkAnnotation fires
 *     normally). Drag is fine (Compose's pointer system propagates the move to the
 *     scroll detector because we don't consume).
 *
 *   - The remaining race is with the PostCard's parent `combinedClickable.onLongClick`
 *     (post action sheet) which also fires at ~500ms. By consuming the pointer changes
 *     at the moment our timer expires, we mark the gesture as ours and combinedClickable
 *     bails out of its onLongClick dispatch — no double-sheet.
 *
 * Touch outside any link range: we observe the down, fail the hit-test, exit the
 * gesture loop without consuming anything. Everything upstream (scroll, post action
 * sheet, post tap) behaves exactly as before.
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
        val longPressTimeoutMs = cfg.longPressTimeoutMillis
        val touchSlopSq = cfg.touchSlop * cfg.touchSlop
        watchLinkLongPress(layout, linkRanges, longPressTimeoutMs, touchSlopSq, onTap, onLongPress)
    }
}

@Suppress("UNUSED_PARAMETER")
private suspend fun PointerInputScope.watchLinkLongPress(
    layout: TextLayoutResult,
    linkRanges: List<LinkRange>,
    longPressTimeoutMs: Long,
    touchSlopSq: Float,
    onTap: (LinkRange) -> Unit,
    onLongPress: (LinkRange) -> Unit,
) {
    awaitEachGesture {
        // Pass = Main: we want descendants (LinkAnnotation's own clickable) to see the
        // event first — they normally consume the tap. We piggyback on the same Main
        // pass without claiming the gesture, so taps that release within tap-timeout
        // fall through cleanly. If we used Initial here we'd race the LinkAnnotation
        // and steal its tap.
        val down = awaitFirstDown(requireUnconsumed = false)
        val offset = runCatching { layout.getOffsetForPosition(down.position) }.getOrNull()
            ?: return@awaitEachGesture
        val range = linkRanges.firstOrNull { offset in it.start until it.end }
            ?: return@awaitEachGesture

        // Run a long-press timer in parallel with whatever LinkAnnotation /
        // combinedClickable upstream are doing. Cancellation reasons:
        //   - pointer released → it was a tap; bail and let LinkAnnotation handle.
        //   - pointer moved past slop → drag (scroll); bail without consuming.
        //   - timer expires → we win; consume + fire onLongPress.
        val outcome = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                if (!change.pressed) return@withTimeoutOrNull TapOrCancel
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (dx * dx + dy * dy > touchSlopSq) return@withTimeoutOrNull TapOrCancel
            }
            @Suppress("UNREACHABLE_CODE") TapOrCancel
        }
        if (outcome == null) {
            // Long-press timer expired with the finger still held within slop. Claim
            // the gesture for the menu — consuming the most recent event marks the
            // gesture consumed so PostCard's combinedClickable.onLongClick (which is
            // about to fire at the same timestamp) sees a consumed pointer and bails.
            currentEvent.changes.forEach { it.consume() }
            onLongPress(range)
        }
    }
}

private const val TapOrCancel = "tap-or-cancel"
