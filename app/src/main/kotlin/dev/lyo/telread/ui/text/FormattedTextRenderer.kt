package dev.lyo.telread.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.lyo.telread.data.FormattedText

/** Convert a [FormattedText] into a Compose [AnnotatedString] using current theme colors. */
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
    formatted.spans.forEach { span ->
        val style = span.toSpanStyle(accent, codeBg, mute) ?: return@forEach
        addStyle(style, span.start.coerceIn(0, formatted.text.length), span.end.coerceIn(0, formatted.text.length))
    }
}

private fun FormattedText.Span.toSpanStyle(
    accent: Color,
    codeBg: Color,
    mute: Color,
): SpanStyle? = when (val s = style) {
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
    FormattedText.Style.Url,
    is FormattedText.Style.TextUrl -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
    FormattedText.Style.Mention,
    is FormattedText.Style.MentionName -> SpanStyle(color = accent)
    FormattedText.Style.Hashtag,
    FormattedText.Style.BotCommand -> SpanStyle(color = accent)
    FormattedText.Style.Spoiler -> SpanStyle(background = mute, color = mute)
    is FormattedText.Style.CustomEmoji -> null
    FormattedText.Style.BlockQuote -> SpanStyle(color = mute)
}
