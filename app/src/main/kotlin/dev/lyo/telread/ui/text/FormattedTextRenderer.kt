package dev.lyo.telread.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.lyo.telread.data.FormattedText

/**
 * Convert a [FormattedText] into a Compose [AnnotatedString] using current theme colors.
 *
 * URL spans are encoded as [LinkAnnotation.Url] so `Text` / `BasicText` route taps through
 * `LocalUriHandler` automatically — no custom click detection needed. The renderer keeps
 * pure styling work (bold/italic/code/spoiler/...) on `addStyle`; only the link variants
 * pay the link-annotation overhead.
 */
@Composable
fun rememberAnnotatedString(formatted: FormattedText): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val mute = MaterialTheme.colorScheme.onSurfaceVariant

    return remember(formatted, accent, codeBg, mute) {
        buildFromFormatted(formatted, accent, codeBg, mute)
    }
}

private fun buildFromFormatted(
    formatted: FormattedText,
    accent: Color,
    codeBg: Color,
    mute: Color,
): AnnotatedString = buildAnnotatedString {
    append(formatted.text)
    val length = formatted.text.length
    val linkStyle = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))

    formatted.spans.forEach { span ->
        val start = span.start.coerceIn(0, length)
        val end = span.end.coerceIn(start, length)
        if (end == start) return@forEach
        when (val s = span.style) {
            is FormattedText.Style.TextUrl -> addLink(LinkAnnotation.Url(s.url, linkStyle), start, end)
            FormattedText.Style.Url -> {
                // Inline URL — the URL itself is the substring being styled.
                val url = formatted.text.substring(start, end)
                addLink(LinkAnnotation.Url(normalizeUrl(url), linkStyle), start, end)
            }
            else -> span.style.toSpanStyle(accent, codeBg, mute)
                ?.let { style -> addStyle(style, start, end) }
        }
    }
}

private fun FormattedText.Style.toSpanStyle(
    accent: Color,
    codeBg: Color,
    mute: Color,
): SpanStyle? = when (this) {
    FormattedText.Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    FormattedText.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    FormattedText.Style.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    FormattedText.Style.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    FormattedText.Style.Code -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBg,
    )
    is FormattedText.Style.Pre -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBg,
    )
    FormattedText.Style.Mention,
    is FormattedText.Style.MentionName -> SpanStyle(color = accent)
    FormattedText.Style.Hashtag,
    FormattedText.Style.BotCommand -> SpanStyle(color = accent)
    FormattedText.Style.Spoiler -> SpanStyle(background = mute, color = mute)
    is FormattedText.Style.CustomEmoji -> null
    FormattedText.Style.BlockQuote -> SpanStyle(color = mute)
    // Url / TextUrl handled via addLink in the caller.
    FormattedText.Style.Url, is FormattedText.Style.TextUrl -> null
}

/**
 * TDLib reports inline URLs verbatim — `t.me/foo` without scheme, `example.com` etc.
 * Compose's UriHandler hands the string straight to ACTION_VIEW, which fails for
 * scheme-less inputs. Prepend `https://` so the OS gets a parseable Uri.
 */
private fun normalizeUrl(raw: String): String {
    if (raw.contains("://")) return raw
    if (raw.startsWith("mailto:") || raw.startsWith("tel:")) return raw
    return "https://$raw"
}
