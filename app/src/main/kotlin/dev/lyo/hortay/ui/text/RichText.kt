package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
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
 * Per-post callback that pins the current post's top right before any in-place expansion
 * grows it ("Показати більше" on a clamped text segment, or a quote/code block's expand
 * chevron). Supplied by the feed / channel LazyColumn (which owns the LazyListState) so a
 * `reverseLayout` post reveals downward instead of dumping the reader at its end; `null`
 * everywhere else (full post / comments), where the surrounding scroll container needs no
 * nudge. Lives here in `ui.text` so both [BlockBox] and the timeline's `ExpandableText` can
 * read it without `ui.text` depending on `ui.timeline`.
 */
internal val LocalExpandScrollKeeper = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Renderer for [FormattedText]. Block quotes / code blocks render the SAME way on every
 * surface — feed, channel, comments, full post — as padded [BlockBox] composables. A post
 * carrying a block is split into alternating plain-text / block segments stacked in a Column.
 *
 * Clamping is PER ELEMENT, not post-wide: a plain-text segment flows through [renderer] (the
 * caller's [maxLines] line clamp + "Показати більше"), and each quote/code box collapses on
 * its own via its chevron. A single post-level clamp across these mixed composables can only
 * cut by pixel height — slicing a quote mid-line — or reactively count lines, which flickers
 * on first layout; per-element clamping instead keeps every cut on a whole-line boundary and
 * leaves the quote chevrons with no outer clamp to fight. This is the Telegram-reader model:
 * one "Показати більше" on the body text, a chevron on each long quote.
 *
 * Posts without any block skip segmentation and render as one [renderer] call. Top-level
 * blocks only — a nested block (rare) still renders inline within its parent box via
 * [LinkAwareText]'s draw-behind path.
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (RenderableText, TextStyle, Int) -> Unit),
) {
    // Trim blank edges of the whole body first (stray leading / trailing newlines and
    // spaces TDLib or the web parser leave behind), so no surface renders a post with
    // empty lines hanging off the top or bottom.
    val src = remember(formatted) { formatted.trimmedBlankEdges() }
    val blocks = remember(src) { src.blockRanges() }
    // No top-level block → one [Text] with the caller's clamp + "Показати більше".
    if (blocks.isEmpty()) {
        renderer(rememberRenderableText(src), style, maxLines)
        return
    }

    // Block present → padded composable blocks, each clamped on its own by [maxLines]
    // (text via [renderer], quote/code via [BlockBox]'s chevron). Whole-line cuts, no
    // outer clamp to fight the per-quote collapse.
    val segments = remember(src, blocks) { buildSegments(src, blocks) }
    Column {
        segments.forEachIndexed { idx, segment ->
            // Segments are individually edge-trimmed, so spacing is a single consistent
            // gap rather than whatever stray newlines the source happened to carry.
            if (idx > 0) Spacer(Modifier.height(8.dp))
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
 * A padded block quote or code block. Renders identically on every surface (feed, channel,
 * comments, full post).
 *
 *  * **Quote** — accent bar + `primary @ 10%` tint, a quote-mark glyph in the top-right
 *    corner that marks it as a quote, body at full readability.
 *  * **Code** — `surfaceContainerHigh` box, monospace body, optional language header.
 *
 * The box hugs its content width rather than filling the row, so a short quote reads as a
 * pulled-in block instead of a full-width band. Collapsing: an expandable quote starts at
 * [COLLAPSED_QUOTE_LINES]; any other block clamps to [maxLines]. The chevron + tap target
 * appear only when the body actually overflows, and toggle both ways. Expanding pins the
 * post's top via [LocalExpandScrollKeeper] so a `reverseLayout` feed reveals the new lines
 * downward instead of dumping the reader at the post's end.
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
    // Right gutter clears the corner affordances: a quote always carries the top-right
    // quote glyph; the expand chevron shares that strip on the bottom-right. Code has no
    // quote glyph, so it only reserves the gutter when the chevron is present.
    val endPad = when {
        !isCode -> 26.dp
        canExpand -> 26.dp
        else -> 12.dp
    }

    val body: @Composable () -> Unit = {
        LinkAwareText(
            renderable = rt,
            style = contentStyle,
            maxLines = effectiveMax,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded && layout.hasVisualOverflow) canExpand = true },
        )
    }

    // The accent bar is painted with drawBehind (full box height) rather than a
    // fillMaxHeight child under IntrinsicSize.Min: with the box now sized to its content,
    // pairing IntrinsicSize.Min height with content-driven width forced a double intrinsic
    // measure that flickered the box on every collapse/expand toggle. drawBehind sizes off
    // the laid-out height directly, so there's nothing to re-measure.
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(boxBg)
            .then(
                if (isCode) {
                    Modifier
                } else {
                    Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                },
            )
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
                modifier = Modifier.padding(start = 12.dp, end = endPad, top = 8.dp, bottom = 8.dp),
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
            // Content inset past the drawn 3.dp bar (start = 13) so text never touches it.
            Box(modifier = Modifier.padding(start = 13.dp, end = endPad, top = 8.dp, bottom = 8.dp)) {
                body()
            }
            // Quote marker — a faint quote-mark glyph in the top-right corner so the block
            // reads as a quote even before the reader notices the accent bar.
            Symbol(
                name = "format_quote",
                tint = accent.copy(alpha = 0.55f),
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp),
            )
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
 * [FormattedText] whose spans are re-anchored to the slice, with its blank edges trimmed
 * — so a block box carries no empty leading / trailing line inside, and plain runs between
 * blocks shed the `\n\n` the source injects around them. Empty pieces are dropped.
 */
private fun buildSegments(source: FormattedText, blocks: List<BlockRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    fun addPlain(start: Int, end: Int) {
        if (start >= end) return
        val seg = source.slice(start, end).trimmedBlankEdges()
        if (seg.text.isNotEmpty()) out += Segment(seg, block = null)
    }
    for (b in blocks) {
        addPlain(cursor, b.start)
        val blockSeg = source.slice(b.start, b.end).trimmedBlankEdges()
        if (blockSeg.text.isNotEmpty()) out += Segment(blockSeg, block = b.style)
        cursor = b.end
    }
    addPlain(cursor, source.text.length)
    return out
}

/** Drop leading / trailing whitespace (spaces, tabs, newlines) and re-anchor every span. */
private fun FormattedText.trimmedBlankEdges(): FormattedText {
    if (text.isEmpty()) return this
    var s = 0
    var e = text.length
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (s == 0 && e == text.length) return this
    if (s >= e) return FormattedText.Empty
    val sub = text.substring(s, e)
    val newSpans = spans.mapNotNull { sp ->
        val ns = (sp.start - s).coerceAtLeast(0)
        val ne = (sp.end - s).coerceAtMost(e - s)
        if (ne <= ns) null else FormattedText.Span(ns, ne, sp.style)
    }
    return FormattedText(sub, newSpans)
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
