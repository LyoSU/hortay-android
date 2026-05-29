package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Renderer for [FormattedText].
 *
 * Inline styles and the *static* block elements — plain block quotes and code blocks —
 * live in [RenderableText.blockDecorations] and are painted as a tinted box + accent bar
 * behind the single backing [Text] by [LinkAwareText]. Keeping the body in one [Text]
 * means the `maxLines` clamp and the "Показати більше" toggle keep working, and every
 * surface (feed / channel / comments / detail) renders those blocks identically.
 *
 * The ONE exception is the **expandable** block quote
 * (`TextEntityTypeExpandableBlockQuote`): it needs its own collapsed/expanded state and a
 * per-quote line clamp, which a single shared [Text] cannot express. So whenever an
 * expandable quote is present — on EVERY surface, feed included — the body is split into
 * segments: ordinary runs render through [renderer] (→ [LinkAwareText], static blocks still
 * drawn inline) keeping their own [maxLines] clamp + "Показати більше", and each expandable
 * quote renders as a collapsible [ExpandableQuote]. The quote starts collapsed and toggles
 * both ways; because each segment keeps its own clamp, the post-level truncation still
 * works around it.
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (RenderableText, TextStyle, Int) -> Unit),
) {
    val expandableRanges = remember(formatted) { formatted.expandableQuoteRanges() }
    if (expandableRanges.isEmpty()) {
        renderer(rememberRenderableText(formatted), style, maxLines)
        return
    }

    val segments = remember(formatted, expandableRanges) { buildSegments(formatted, expandableRanges) }
    Column {
        segments.forEachIndexed { idx, segment ->
            if (idx > 0) {
                // Insert a manual 8 dp Spacer ONLY when the segment boundary doesn't
                // already carry a natural paragraph break (the source / walker injects
                // `\n` around block elements). Adding a Spacer on top of an existing
                // blank line stacks two visible gaps. Preserves source whitespace without
                // doubling it.
                val prevText = segments[idx - 1].text.text
                val curText = segment.text.text
                val naturalBreak = prevText.endsWith('\n') || curText.startsWith('\n')
                if (!naturalBreak) Spacer(Modifier.height(8.dp))
            }
            if (segment.isExpandableQuote) {
                ExpandableQuote(segment.text, style)
            } else {
                renderer(rememberRenderableText(segment.text), style, maxLines)
            }
        }
    }
}

/** Lines shown before an expandable quote collapses behind its "expand" affordance. */
private const val COLLAPSED_QUOTE_LINES = 3

/**
 * A collapsible block quote (TDLib `TextEntityTypeExpandableBlockQuote`). Renders the
 * Telegram-style tinted box + accent bar (same palette as the static quote box drawn by
 * [LinkAwareText]) around a [LinkAwareText] clamped to [COLLAPSED_QUOTE_LINES] until the
 * user expands it.
 *
 * Starts collapsed and toggles both ways (chevron down = expand, up = collapse), matching
 * Telegram. The chevron + tap target appear only once the quote actually overflows the
 * collapsed budget. The whole box is the tap target with a state-dependent click label.
 */
@Composable
private fun ExpandableQuote(text: FormattedText, style: TextStyle) {
    val accent = MaterialTheme.colorScheme.primary
    var expanded by remember(text) { mutableStateOf(false) }
    var canExpand by remember(text) { mutableStateOf(false) }
    val rt = rememberRenderableText(text)
    val clickLabel = stringResource(if (expanded) R.string.post_show_less else R.string.post_show_more)

    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.10f))
            .then(
                if (canExpand) {
                    Modifier.clickable(role = Role.Button, onClickLabel = clickLabel) { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            LinkAwareText(
                renderable = rt,
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_QUOTE_LINES,
                overflow = TextOverflow.Ellipsis,
                // Detect overflow only while collapsed; once expandable it stays toggleable.
                onTextLayout = { layout -> if (!expanded && layout.hasVisualOverflow) canExpand = true },
                // Trailing padding reserves room for the chevron so a line never crashes into it.
                modifier = Modifier.padding(start = 12.dp, end = 30.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        if (canExpand) {
            // Chevron: down ("›" rotated 90°) when collapsed = "expand", up (270°) when
            // expanded = "collapse". Reuses the bundled `chevron_right` drawable.
            Symbol(
                name = "chevron_right",
                tint = accent.copy(alpha = 0.7f),
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 8.dp)
                    .rotate(if (expanded) 270f else 90f),
            )
        }
    }
}

private data class Segment(val text: FormattedText, val isExpandableQuote: Boolean)

/**
 * Collapse and de-overlap the expandable-quote ranges; the result is a sorted list of
 * non-overlapping `[start, end)` pairs marking the expandable-quote regions of [text].
 */
private fun FormattedText.expandableQuoteRanges(): List<IntRange> {
    val raw = spans
        .filter { (it.style as? FormattedText.Style.BlockQuote)?.expandable == true }
        .map { it.start.coerceIn(0, text.length)..it.end.coerceIn(0, text.length) }
        .filter { it.first < it.last }
        .sortedBy { it.first }
    if (raw.size <= 1) return raw

    val merged = mutableListOf<IntRange>()
    var current = raw.first()
    for (next in raw.drop(1)) {
        current = if (next.first <= current.last) {
            current.first..maxOf(current.last, next.last)
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

/**
 * Slice [source] into alternating ordinary / expandable-quote pieces. Each piece is a
 * [FormattedText] whose own spans are re-anchored relative to the slice start.
 *
 * Boundary trim: at a quote↔non-quote junction we keep AT MOST ONE `\n` of the
 * paragraph-break run the source injects around the block element — Compose renders
 * `text\n\n` as three lines tall (two blank rows), where one is intended. We cut the
 * slice off after the first newline of the trailing run (mirror on the leading side).
 */
private fun buildSegments(source: FormattedText, quoteRanges: List<IntRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    for (range in quoteRanges) {
        if (cursor < range.first) {
            val end = trimToSingleTrailingNewline(source.text, cursor, range.first)
            if (end > cursor) {
                out += Segment(source.slice(cursor, end), isExpandableQuote = false)
            }
        }
        out += Segment(source.slice(range.first, range.last), isExpandableQuote = true)
        cursor = range.last
    }
    if (cursor < source.text.length) {
        val start = trimToSingleLeadingNewline(source.text, cursor, source.text.length)
        if (start < source.text.length) {
            out += Segment(source.slice(start, source.text.length), isExpandableQuote = false)
        }
    }
    return out.filter { it.text.text.isNotEmpty() }
}

/**
 * Walk back from [end] over the trailing newline / inline-whitespace run inside
 * `[start, end)`, landing the slice's effective end right after the FIRST newline (so
 * `text\n\n` → `text\n`, `text` → `text`).
 */
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
 * Substring of a [FormattedText] preserving overlapping spans (clipped to the slice and
 * re-anchored). The expandable-BlockQuote wrapper spans are stripped — [ExpandableQuote]
 * provides that styling — while static quotes, code blocks and inline spans are kept so
 * they still render inside the segment.
 */
private fun FormattedText.slice(start: Int, end: Int): FormattedText {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    if (s == e) return FormattedText.Empty
    val slicedText = text.substring(s, e)
    val slicedSpans = spans.mapNotNull { span ->
        if ((span.style as? FormattedText.Style.BlockQuote)?.expandable == true) return@mapNotNull null
        val newStart = (span.start - s).coerceAtLeast(0)
        val newEnd = (span.end - s).coerceAtMost(e - s)
        if (newEnd <= newStart) null
        else FormattedText.Span(newStart, newEnd, span.style)
    }
    return FormattedText(slicedText, slicedSpans)
}
