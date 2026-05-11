package dev.lyo.hortay.ui.text

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult

/**
 * Modifier that adds **only** a long-press hit-tester for inline links inside a
 * [androidx.compose.material3.Text]. Tap is intentionally untouched — the
 * `LinkAnnotation.Url` annotations baked into the AnnotatedString keep handling
 * single-tap (with ripple feedback + `LocalUriHandler` routing), so we don't have to
 * re-implement that flow.
 *
 * Uses Compose's own [detectTapGestures] with [onLongPress] only; that detector is
 * well-tested for parallel coexistence with parent / child clickable detectors. The
 * `onPress = {}` block accepts the down but does not consume, so the underlying
 * LinkAnnotation tap detector continues to receive events normally — when the user
 * releases inside the tap timeout, LinkAnnotation fires, no race.
 *
 * Long-press timing: `detectTapGestures` uses the platform `longPressTimeoutMillis`
 * (~500ms by default). When the timer fires with the finger still down inside a link
 * range, [onLongPress] is invoked; the same detector consumes the event internally so
 * the post action sheet (PostCard's `combinedClickable.onLongClick`) doesn't also
 * fire at the same instant.
 */
@Composable
fun Modifier.linkLongPress(
    linkRanges: List<LinkRange>,
    layoutResult: TextLayoutResult?,
    onLongPress: (LinkRange) -> Unit,
): Modifier {
    if (linkRanges.isEmpty()) return this
    return this.pointerInput(linkRanges, layoutResult) {
        val layout = layoutResult ?: return@pointerInput
        detectTapGestures(
            onLongPress = { offset ->
                val charOffset = runCatching { layout.getOffsetForPosition(offset) }
                    .getOrNull() ?: return@detectTapGestures
                val range = linkRanges.firstOrNull { charOffset in it.start until it.end }
                    ?: return@detectTapGestures
                onLongPress(range)
            },
            // No onTap: LinkAnnotation's built-in tap handler owns single-press. If we
            // added one here it would race + double-fire.
        )
    }
}
