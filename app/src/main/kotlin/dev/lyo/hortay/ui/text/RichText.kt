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
 * Renderer for [FormattedText]. Routes by surface, because block quotes / code blocks have
 * two conflicting needs that no single mechanism satisfies:
 *
 *  * **Clamped surfaces** (feed / channel / captions — finite [maxLines]): render as ONE
 *    backing [Text] via [renderer]. Blocks paint inline through [LinkAwareText]'s
 *    draw-behind boxes (accent bar + tint), so the post-level `maxLines` clamp and
 *    "Показати більше" count EVERY line — text and quote/code alike. This is what the feed
 *    needs: one unified "show more" over the whole post.
 *  * **Full-reading surfaces** (comments / full post — `maxLines == Int.MAX_VALUE`): split
 *    into segments and render each block as a padded [BlockBox] composable — proper side
 *    padding and a collapse toggle for expandable quotes, where the reader has committed to
 *    the post and there's no competing post-level clamp.
 *
 * One decision point, driven by [maxLines]; both paths are produced from the same core
 * ([rememberRenderableText]), so every surface stays consistent. Posts without any block
 * skip segmentation entirely.
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
    // No blocks, OR a clamped surface → single [Text]: blocks (if any) render via
    // LinkAwareText's draw-behind boxes and the unified clamp counts their lines too.
    if (blocks.isEmpty() || maxLines != Int.MAX_VALUE) {
        renderer(rememberRenderableText(src), style, maxLines)
        return
    }

    // Full-reading surface: padded composable blocks with per-quote collapse.
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
