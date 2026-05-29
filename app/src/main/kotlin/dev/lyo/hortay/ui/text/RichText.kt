package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Renderer for [FormattedText].
 *
 * Inline content (styles, links, mentions, spoilers, custom emoji) renders through
 * [renderer] (→ [LinkAwareText]) as a single backing [Text], so the `maxLines` clamp and
 * "Показати більше" toggle work.
 *
 * Paragraph-level blocks — block quotes and code blocks — are NOT inline-able with proper
 * side padding inside a shared [Text] (a [Text] wraps every paragraph at one width). So
 * whenever a block is present the body is split into segments: ordinary runs go through
 * [renderer], and each top-level block renders as a padded [BlockBox]. This is the single
 * place blocks are produced, so every surface (feed / channel / comments / full post) gets
 * the same boxes. Nested blocks (e.g. code inside a quote) are rare and fall back to inline
 * styling within their parent box.
 *
 * Posts WITHOUT any block skip segmentation entirely — the common path is unchanged.
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (RenderableText, TextStyle, Int) -> Unit),
) {
    val blocks = remember(formatted) { formatted.blockRanges() }
    if (blocks.isEmpty()) {
        renderer(rememberRenderableText(formatted), style, maxLines)
        return
    }

    val segments = remember(formatted, blocks) { buildSegments(formatted, blocks) }
    Column {
        segments.forEachIndexed { idx, segment ->
            if (idx > 0) {
                // Insert an 8 dp Spacer ONLY when the boundary doesn't already carry a
                // natural paragraph break (the source injects `\n` around blocks);
                // otherwise the gap would double.
                val prevText = segments[idx - 1].text.text
                val curText = segment.text.text
                val naturalBreak = prevText.endsWith('\n') || curText.startsWith('\n')
                if (!naturalBreak) Spacer(Modifier.height(8.dp))
            }
            val block = segment.block
            if (block != null) {
                BlockBox(segment.text, style, block, maxLines)
            } else {
                renderer(rememberRenderableText(segment.text), style, maxLines)
            }
        }
    }
}

/** Lines a block shows before collapsing: a fixed few for expandable quotes, otherwise the
 *  caller's clamp (so a block on the feed stays bounded, but a full post shows it whole). */
private const val COLLAPSED_QUOTE_LINES = 3

/**
 * A padded block quote or code block.
 *
 *  * **Quote** — accent bar + `primary @ 10%` tint, body at full readability.
 *  * **Code** — `surfaceContainerHigh` box, monospace body, optional language header.
 *
 * Collapsing: an expandable quote starts at [COLLAPSED_QUOTE_LINES]; any other block clamps
 * to [maxLines] (so it stays bounded on the feed, full on detail where `maxLines` is
 * `Int.MAX_VALUE`). The chevron + tap target appear only when the body actually overflows,
 * and toggle both ways.
 */
@Composable
private fun BlockBox(
    text: FormattedText,
    style: TextStyle,
    blockStyle: FormattedText.Style,
    maxLines: Int,
) {
    val isCode = blockStyle is FormattedText.Style.Pre
    val expandable = (blockStyle as? FormattedText.Style.BlockQuote)?.expandable == true
    val language = (blockStyle as? FormattedText.Style.Pre)?.language

    var expanded by remember(text) { mutableStateOf(false) }
    var canExpand by remember(text) { mutableStateOf(false) }
    val collapsedBudget = if (expandable) COLLAPSED_QUOTE_LINES else maxLines
    val effectiveMax = if (expanded) Int.MAX_VALUE else collapsedBudget
    val expandLabel = stringResource(if (expanded) R.string.post_show_less else R.string.post_show_more)

    val accent = MaterialTheme.colorScheme.primary
    val boxBg = if (isCode) MaterialTheme.colorScheme.surfaceContainerHigh else accent.copy(alpha = 0.10f)
    val contentStyle = if (isCode) style.copy(fontFamily = FontFamily.Monospace) else style
    val rt = rememberRenderableText(text)
    val endPad = if (canExpand) 30.dp else 14.dp

    val body: @Composable () -> Unit = {
        LinkAwareText(
            renderable = rt,
            style = contentStyle,
            maxLines = effectiveMax,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded && layout.hasVisualOverflow) canExpand = true },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(boxBg)
            .then(
                if (canExpand) {
                    Modifier.clickable(role = Role.Button, onClickLabel = expandLabel) { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        if (isCode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = endPad, top = 8.dp, bottom = 8.dp),
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                body()
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
                )
                Box(modifier = Modifier.padding(start = 10.dp, end = endPad, top = 8.dp, bottom = 8.dp)) {
                    body()
                }
            }
        }
        if (canExpand) {
            // Chevron: down ("›" rotated 90°) when collapsed = "expand", up (270°) when
            // expanded = "collapse". Reuses the bundled `chevron_right` drawable.
            Symbol(
                name = "chevron_right",
                tint = (if (isCode) MaterialTheme.colorScheme.onSurfaceVariant else accent).copy(alpha = 0.7f),
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 8.dp)
                    .rotate(if (expanded) 270f else 90f),
            )
        }
    }
}

/** A piece of the body: plain text ([block] == null) or a [block] quote / code run. */
private data class Segment(val text: FormattedText, val block: FormattedText.Style?)

private data class BlockRange(val start: Int, val end: Int, val style: FormattedText.Style)

/**
 * Top-level block ranges (quotes / code), de-overlapped greedily outer-first. A block
 * nested inside another (rare) is dropped here and renders inline within its parent box.
 */
private fun FormattedText.blockRanges(): List<BlockRange> {
    val raw = spans
        .mapNotNull { sp ->
            val isBlock = sp.style is FormattedText.Style.BlockQuote || sp.style is FormattedText.Style.Pre
            if (!isBlock) return@mapNotNull null
            val st = sp.start.coerceIn(0, text.length)
            val en = sp.end.coerceIn(st, text.length)
            if (st >= en) null else BlockRange(st, en, sp.style)
        }
        .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
    if (raw.isEmpty()) return emptyList()

    val out = mutableListOf<BlockRange>()
    var lastEnd = -1
    for (b in raw) {
        if (b.start >= lastEnd) {
            out += b
            lastEnd = b.end
        }
    }
    return out.sortedBy { it.start }
}

/**
 * Slice [source] into alternating plain-text / block pieces. Each piece is a
 * [FormattedText] whose spans are re-anchored to the slice. Boundary trim keeps AT MOST
 * one `\n` of the paragraph-break run the source injects around a block (Compose renders
 * `text\n\n` three lines tall, where one blank line is intended).
 */
private fun buildSegments(source: FormattedText, blocks: List<BlockRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    for (b in blocks) {
        if (cursor < b.start) {
            val end = trimToSingleTrailingNewline(source.text, cursor, b.start)
            if (end > cursor) out += Segment(source.slice(cursor, end), block = null)
        }
        out += Segment(source.slice(b.start, b.end), block = b.style)
        cursor = b.end
    }
    if (cursor < source.text.length) {
        val start = trimToSingleLeadingNewline(source.text, cursor, source.text.length)
        if (start < source.text.length) out += Segment(source.slice(start, source.text.length), block = null)
    }
    return out.filter { it.text.text.isNotEmpty() }
}

/** Walk back from [end] over a trailing newline run, keeping at most one `\n`. */
private fun trimToSingleTrailingNewline(text: String, start: Int, end: Int): Int {
    var i = end
    var firstNewlinePos = -1
    while (i > start) {
        val c = text[i - 1]
        if (c == '\n') firstNewlinePos = i - 1
        else if (c != ' ' && c != '\t') break
        i--
    }
    return if (firstNewlinePos >= 0) firstNewlinePos + 1 else end
}

/** Mirror of [trimToSingleTrailingNewline] — lands the slice start AT the LAST newline. */
private fun trimToSingleLeadingNewline(text: String, start: Int, end: Int): Int {
    var i = start
    var lastNewlinePos = -1
    while (i < end) {
        val c = text[i]
        if (c == '\n') lastNewlinePos = i
        else if (c != ' ' && c != '\t') break
        i++
    }
    return if (lastNewlinePos >= 0) lastNewlinePos else start
}

/**
 * Substring preserving overlapping spans (clipped + re-anchored). The block-type wrapper
 * span covering the WHOLE slice is dropped — [BlockBox] provides that styling — while
 * inner spans (including a nested block, which then renders inline) are kept.
 */
private fun FormattedText.slice(start: Int, end: Int): FormattedText {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    if (s == e) return FormattedText.Empty
    val slicedText = text.substring(s, e)
    val sliceLen = e - s
    val slicedSpans = spans.mapNotNull { span ->
        val newStart = (span.start - s).coerceAtLeast(0)
        val newEnd = (span.end - s).coerceAtMost(sliceLen)
        if (newEnd <= newStart) return@mapNotNull null
        val isBlock = span.style is FormattedText.Style.BlockQuote || span.style is FormattedText.Style.Pre
        if (isBlock && newStart == 0 && newEnd == sliceLen) return@mapNotNull null
        FormattedText.Span(newStart, newEnd, span.style)
    }
    return FormattedText(slicedText, slicedSpans)
}
