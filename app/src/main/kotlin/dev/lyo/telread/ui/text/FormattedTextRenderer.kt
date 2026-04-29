package dev.lyo.telread.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
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
 * `LocalUriHandler` automatically — no custom click detection needed.
 *
 * Spoiler spans are encoded as [LinkAnnotation.Clickable] with a per-span listener that
 * flips the span's index into a [Set<Int>] of revealed indices. The set is keyed on
 * [formatted] so navigating away and back resets the cover (same behaviour as Telegram).
 */
@Composable
fun rememberAnnotatedString(formatted: FormattedText): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val mute = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val uriHandler = LocalUriHandler.current

    // Reveal state lives in a State so flipping it from the link listener triggers a
    // recomposition of any Text consuming the AnnotatedString.
    var revealed by remember(formatted) { mutableStateOf(emptySet<Int>()) }

    return remember(formatted, accent, codeBg, mute, onSurface, revealed) {
        buildFromFormatted(
            formatted = formatted,
            accent = accent,
            codeBg = codeBg,
            mute = mute,
            onSurface = onSurface,
            uriHandler = uriHandler,
            revealedSpoilers = revealed,
            onSpoilerTap = { idx -> revealed = revealed + idx },
        )
    }
}

private fun buildFromFormatted(
    formatted: FormattedText,
    accent: Color,
    codeBg: Color,
    mute: Color,
    onSurface: Color,
    uriHandler: UriHandler,
    revealedSpoilers: Set<Int>,
    onSpoilerTap: (Int) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(formatted.text)
    val length = formatted.text.length
    val linkStyle = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    val mentionStyle = TextLinkStyles(SpanStyle(color = accent))
    // tg://… URIs throw ActivityNotFoundException when no Telegram client is installed.
    // openUri propagates that synchronously from inside the gesture handler — we'd crash.
    // Wrap in runCatching so the tap is a no-op instead.
    val safeOpen = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
    }

    formatted.spans.forEachIndexed { idx, span ->
        val start = span.start.coerceIn(0, length)
        val end = span.end.coerceIn(start, length)
        if (end == start) return@forEachIndexed
        when (val s = span.style) {
            is FormattedText.Style.TextUrl -> addLink(LinkAnnotation.Url(s.url, linkStyle, safeOpen), start, end)
            FormattedText.Style.Url -> {
                // Inline URL — the URL itself is the substring being styled.
                val url = formatted.text.substring(start, end)
                addLink(LinkAnnotation.Url(normalizeUrl(url), linkStyle, safeOpen), start, end)
            }
            FormattedText.Style.Mention -> {
                // @username — resolve via tg:// so the official client opens the profile.
                val handle = formatted.text.substring(start, end).trimStart('@')
                if (handle.isNotEmpty()) {
                    addLink(LinkAnnotation.Url("tg://resolve?domain=$handle", mentionStyle, safeOpen), start, end)
                }
            }
            FormattedText.Style.Hashtag -> {
                // tg://search opens Telegram's global search with the tag preselected.
                val tag = formatted.text.substring(start, end)
                addLink(LinkAnnotation.Url("tg://search?query=$tag", mentionStyle, safeOpen), start, end)
            }
            FormattedText.Style.Spoiler -> {
                if (idx in revealedSpoilers) {
                    // Once tapped, render the text in normal colors with no further click.
                    addStyle(SpanStyle(color = onSurface), start, end)
                } else {
                    // Mask the glyphs by painting them the same colour as the cover; tap
                    // to flip the index into the revealed set.
                    val cover = SpanStyle(background = mute, color = mute)
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "spoiler-$idx",
                            styles = TextLinkStyles(cover),
                            linkInteractionListener = LinkInteractionListener { onSpoilerTap(idx) },
                        ),
                        start,
                        end,
                    )
                }
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
    is FormattedText.Style.MentionName -> SpanStyle(color = accent)
    FormattedText.Style.BotCommand -> SpanStyle(color = accent)
    is FormattedText.Style.CustomEmoji -> null
    FormattedText.Style.BlockQuote -> SpanStyle(color = mute)
    // Url / TextUrl / Mention / Hashtag / Spoiler handled via addLink in the caller.
    FormattedText.Style.Url,
    is FormattedText.Style.TextUrl,
    FormattedText.Style.Mention,
    FormattedText.Style.Hashtag,
    FormattedText.Style.Spoiler -> null
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
