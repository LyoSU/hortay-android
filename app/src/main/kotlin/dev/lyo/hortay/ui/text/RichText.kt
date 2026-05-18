package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.FormattedText

/**
 * Renderer for [FormattedText] that handles inline styles via AnnotatedString AND lifts
 * BlockQuote ranges into separate quoted rows with a Telegram-style 2dp left bar.
 *
 * Rationale for the split: BlockQuote is a *paragraph-level* affordance — a left bar plus
 * indentation. AnnotatedString can colour text but cannot draw a bar that wraps across
 * lines, so quoted ranges have to leave the inline flow and become their own composables.
 *
 * The segmented path runs ONLY on detail surfaces ([maxLines] == [Int.MAX_VALUE]). On feed
 * surfaces (any finite [maxLines]) we fall through to the inline path even when quote
 * spans exist — otherwise a long post / caption with even one quote span would bypass
 * the [maxLines] clamp and the "Показати більше" toggle. Inline quote ranges still read
 * as quotes through the muted-colour SpanStyle from FormattedTextRenderer; the 2dp bar
 * is reserved for detail surfaces where the user has committed to reading the full post.
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (dev.lyo.hortay.ui.text.RenderableText, TextStyle, Int) -> Unit),
) {
    val quoteRanges = remember(formatted) { formatted.blockQuoteRanges() }
    if (quoteRanges.isEmpty() || maxLines != Int.MAX_VALUE) {
        renderer(rememberRenderableText(formatted), style, maxLines)
        return
    }

    // Build the alternating text / quote segments once; each segment is its own slice of
    // the original FormattedText (start, end), so AnnotatedString styling carries over.
    val segments = remember(formatted, quoteRanges) {
        buildSegments(formatted, quoteRanges)
    }

    Column {
        segments.forEachIndexed { idx, segment ->
            if (idx > 0) {
                // Insert a manual 8 dp Spacer ONLY when the segment boundary
                // doesn't already carry a natural paragraph break in the
                // source text. If the previous segment ends with `\n` or this
                // one starts with `\n` (the walker injects `\n\n` around block
                // elements and the source's own `<br><br>` survives the
                // normaliser), the rendered Text already produces a blank
                // line on that side — adding another Spacer on top stacks two
                // visible gaps and reads as "double newline before quote".
                // The conditional preserves source-faithful whitespace
                // ("як в оригіналі") without doubling.
                val prevText = segments[idx - 1].text.text
                val curText = segment.text.text
                val naturalBreak = prevText.endsWith('\n') || curText.startsWith('\n')
                if (!naturalBreak) Spacer(Modifier.height(8.dp))
            }
            val rt = rememberRenderableText(segment.text)
            if (segment.isQuote) QuoteRow(rt.text, rt.inlineContent, style)
            else renderer(rt, style, Int.MAX_VALUE)
        }
    }
}

@Composable
private fun QuoteRow(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(1.dp),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            inlineContent = inlineContent,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

private data class Segment(val text: FormattedText, val isQuote: Boolean)

/**
 * Collapse and de-overlap blockquote ranges; the result is a sorted list of
 * non-overlapping `[start, end)` pairs that mark the quoted regions of [text].
 */
private fun FormattedText.blockQuoteRanges(): List<IntRange> {
    val raw = spans
        .filter { it.style is FormattedText.Style.BlockQuote }
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
 * Slice [source] into alternating non-quote / quote pieces. Each piece is a
 * [FormattedText] whose own spans are re-anchored relative to the slice start.
 *
 * Boundary trim: at quote↔non-quote junctions we keep AT MOST ONE `\n` of the
 * paragraph-break run that the walker injected around the block element. The
 * normaliser caps consecutive newlines at 2, but Compose's Text composable
 * renders `text\n\n` as THREE lines tall (the second `\n` reserves an empty
 * line) — visually two blank rows above the quote where HTML browsers render
 * one. Browsers collapse trailing block-boundary whitespace; we replicate that
 * by cutting the slice off after the first newline of the trailing run (and,
 * mirror, before the last newline of the leading run on the post-quote side).
 *
 * What we DON'T do: drop the newline entirely. That would make text run flush
 * against the left bar — the previous regression "тепер при квотах
 * пропадають преноси". One `\n` keeps the natural HTML paragraph-break visual,
 * matching the source intent without doubling it.
 */
private fun buildSegments(source: FormattedText, quoteRanges: List<IntRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    for (range in quoteRanges) {
        if (cursor < range.first) {
            val end = trimToSingleTrailingNewline(source.text, cursor, range.first)
            if (end > cursor) {
                out += Segment(source.slice(cursor, end), isQuote = false)
            }
        }
        out += Segment(source.slice(range.first, range.last), isQuote = true)
        cursor = range.last
    }
    if (cursor < source.text.length) {
        val start = trimToSingleLeadingNewline(source.text, cursor, source.text.length)
        if (start < source.text.length) {
            out += Segment(source.slice(start, source.text.length), isQuote = false)
        }
    }
    return out.filter { it.text.text.isNotEmpty() }
}

/**
 * Walks back from [end] over trailing newlines and inline whitespace inside
 * `[start, end)`. Keeps at most one `\n` — the slice's effective end is the
 * position right after the FIRST newline encountered in the run (counting
 * from [end] backwards), so a `text\n\n` source ends up as `text\n` slice,
 * a `text\n` source stays `text\n`, and a `text` source stays `text`.
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

/**
 * Mirror of [trimToSingleTrailingNewline] — walks forward from [start] over
 * the leading whitespace run and lands the slice's effective start AT the
 * LAST newline in the run, so `\n\nfollowing` slices as `\nfollowing`.
 */
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
 * Substring of a [FormattedText] preserving overlapping spans (clipped to the slice
 * boundaries and re-anchored). Spans that fall entirely outside `[start, end)` are
 * dropped; BlockQuote spans inside a quoted segment are stripped because the QuoteRow
 * already conveys that styling visually.
 */
private fun FormattedText.slice(start: Int, end: Int): FormattedText {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    if (s == e) return FormattedText.Empty
    val slicedText = text.substring(s, e)
    val slicedSpans = spans.mapNotNull { span ->
        if (span.style is FormattedText.Style.BlockQuote) return@mapNotNull null
        val newStart = (span.start - s).coerceAtLeast(0)
        val newEnd = (span.end - s).coerceAtMost(e - s)
        if (newEnd <= newStart) null
        else FormattedText.Span(newStart, newEnd, span.style)
    }
    return FormattedText(slicedText, slicedSpans)
}
