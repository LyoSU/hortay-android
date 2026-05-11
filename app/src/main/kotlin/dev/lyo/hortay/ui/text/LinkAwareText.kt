@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.text

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Drop-in [Text] replacement that wires three otherwise-tangled link-tap concerns into
 * one composable:
 *
 *   1. Renders the [RenderableText]'s AnnotatedString + inline custom-emoji content
 *      verbatim, just like a bare [Text].
 *
 *   2. Captures [TextLayoutResult] and feeds it into [linkLongPress] so a finger-hold
 *      over a link character hit-tests correctly. The detector is a no-op when
 *      [RenderableText.linkRanges] is empty (plain prose without any URLs), so this
 *      composable is safe to use as a universal `Text` substitute even on bodies that
 *      have nothing to long-press.
 *
 *   3. Hosts the resulting [LinkActionsSheet] (Open / Copy / Share) locally so the
 *      caller doesn't have to thread a sheet-state holder up through their composable
 *      tree. State is keyed on [renderable] so post edits / scroll-and-return reset
 *      the sheet to closed — same lifecycle as expand-collapse.
 *
 * Callers that need an expand-toggle (feed `PostCard` with [maxLines] = 18) wrap this
 * with their own state-machine; callers that don't (detail surfaces, comments anchor)
 * pass `maxLines = Int.MAX_VALUE` for an uncapped render.
 */
@Composable
fun LinkAwareText(
    renderable: RenderableText,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    var layoutResult by remember(renderable) { mutableStateOf<TextLayoutResult?>(null) }
    var pressedLink by remember(renderable) { mutableStateOf<String?>(null) }

    val linkMod = if (renderable.linkRanges.isNotEmpty()) {
        Modifier.linkLongPress(
            linkRanges = renderable.linkRanges,
            layoutResult = layoutResult,
            onLongPress = { range -> pressedLink = range.url },
        )
    } else Modifier

    Text(
        text = renderable.text,
        inlineContent = renderable.inlineContent,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout ->
            layoutResult = layout
            onTextLayout(layout)
        },
        modifier = modifier.then(linkMod),
    )

    pressedLink?.let { url ->
        LinkActionsSheet(url = url, onDismiss = { pressedLink = null })
    }
}
