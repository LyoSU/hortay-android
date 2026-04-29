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
 * The function falls through to [TextRenderer] when the text contains no BlockQuote spans
 * — that path keeps the cheap maxLines + expand-toggle behaviour. The segmented path
 * never collapses (quotes are rare and usually short, so expand-on-overflow there would
 * be more friction than value).
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (AnnotatedString, TextStyle, Int) -> Unit),
) {
    val quoteRanges = remember(formatted) { formatted.blockQuoteRanges() }
    if (quoteRanges.isEmpty()) {
        renderer(rememberAnnotatedString(formatted), style, maxLines)
        return
    }

    // Build the alternating text / quote segments once; each segment is its own slice of
    // the original FormattedText (start, end), so AnnotatedString styling carries over.
    val segments = remember(formatted, quoteRanges) {
        buildSegments(formatted, quoteRanges)
    }

    Column {
        segments.forEachIndexed { idx, segment ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            val annotated = rememberAnnotatedString(segment.text)
            if (segment.isQuote) QuoteRow(annotated, style)
            else renderer(annotated, style, Int.MAX_VALUE)
        }
    }
}

@Composable
private fun QuoteRow(text: AnnotatedString, style: TextStyle) {
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
 */
private fun buildSegments(source: FormattedText, quoteRanges: List<IntRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    for (range in quoteRanges) {
        if (cursor < range.first) {
            out += Segment(source.slice(cursor, range.first), isQuote = false)
        }
        out += Segment(source.slice(range.first, range.last), isQuote = true)
        cursor = range.last
    }
    if (cursor < source.text.length) {
        out += Segment(source.slice(cursor, source.text.length), isQuote = false)
    }
    return out.filter { it.text.text.isNotEmpty() }
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
