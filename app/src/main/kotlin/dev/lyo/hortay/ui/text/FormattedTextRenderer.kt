package dev.lyo.hortay.ui.text

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalCustomEmoji

/**
 * Renderable view of a [FormattedText]: the [AnnotatedString] to hand to a `Text` plus
 * the [InlineTextContent] map that supplies composables for any custom-emoji
 * placeholders embedded in the string, plus the dst-coordinate URL ranges so a long-
 * press handler can hit-test the user's touch position against link characters and
 * surface a Copy / Open menu (Telegram-style). Always pass both together.
 */
@Immutable
data class RenderableText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val linkRanges: List<LinkRange> = emptyList(),
) {
    companion object {
        val Empty = RenderableText(AnnotatedString(""), emptyMap())
    }
}

/**
 * Dst-coordinate URL range for hit-testing long-press gestures. [start] and [end] are
 * character offsets into [RenderableText.text] (post-rebuild, after inline-content
 * placeholders collapsed the source range). [url] is the resolved URL (`tg://` or
 * `https://t.me/...` for mentions/hashtags, the raw href for explicit / inline URLs).
 */
@Immutable
data class LinkRange(val start: Int, val end: Int, val url: String)

/**
 * Compose [FormattedText] into a renderable view. Returns the legacy [AnnotatedString]-only
 * shape for compatibility — call sites that don't carry inline custom emoji can keep using
 * it, while richer renderers should prefer [rememberRenderableText].
 */
@Composable
fun rememberAnnotatedString(formatted: FormattedText): AnnotatedString =
    rememberRenderableText(formatted).text

/**
 * Convert a [FormattedText] into a Compose [AnnotatedString] using current theme colors,
 * plus an inline-content map for any custom-emoji spans.
 *
 * URL spans are encoded as [LinkAnnotation.Url] so `Text` / `BasicText` route taps through
 * `LocalUriHandler` automatically — no custom click detection needed.
 *
 * Spoiler spans are encoded as [LinkAnnotation.Clickable] with a per-span listener that
 * flips the span's index into a [Set<Int>] of revealed indices. The set is keyed on
 * [formatted] so navigating away and back resets the cover (same behaviour as Telegram).
 *
 * Custom-emoji spans (`Style.CustomEmoji`) become Compose `appendInlineContent` markers
 * with a 1.2em-square placeholder; the matching [InlineTextContent] in the returned map
 * draws the resolved sticker via [CustomEmojiInlineView]. Placeholder size is fixed (in
 * EM units) so the line layout doesn't shift when the sticker resolves asynchronously.
 */
@Composable
fun rememberRenderableText(formatted: FormattedText): RenderableText {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val mute = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val uriHandler = LocalUriHandler.current
    val customEmoji = LocalCustomEmoji.current

    // Reveal state lives in a State so flipping it from the link listener triggers a
    // recomposition of any Text consuming the AnnotatedString.
    var revealed by remember(formatted) { mutableStateOf(emptySet<Int>()) }

    val customEmojiIds = remember(formatted) {
        formatted.spans.asSequence()
            .mapNotNull { (it.style as? FormattedText.Style.CustomEmoji)?.emojiId }
            .filter { it != 0L }
            .toSet()
    }

    // Hint the resolver early so the batch fetch starts before any inline-content slot
    // is composed — that way the sticker is ready by the time Compose lays the line out.
    LaunchedEffect(customEmojiIds) {
        if (customEmojiIds.isNotEmpty()) customEmoji.request(customEmojiIds)
    }

    val built = remember(formatted, accent, codeBg, mute, onSurface, revealed) {
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
    val annotated = built.text
    val linkRanges = built.linkRanges

    val inlineContent = remember(customEmojiIds, onSurface) {
        if (customEmojiIds.isEmpty()) emptyMap()
        else customEmojiIds.associate { id ->
            customEmojiTag(id) to InlineTextContent(
                placeholder = Placeholder(
                    width = 1.2.em,
                    height = 1.2.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) { _ ->
                CustomEmojiInlineView(
                    customEmojiId = id,
                    modifier = Modifier.fillMaxSize(),
                    tintColor = onSurface,
                    contentDescription = null,
                )
            }
        }
    }

    return RenderableText(annotated, inlineContent, linkRanges)
}

/** Internal carrier so [buildFromFormatted] can return both the string AND the
 *  link-range index in one allocation. */
private data class BuiltAnnotated(
    val text: AnnotatedString,
    val linkRanges: List<LinkRange>,
)

private fun customEmojiTag(id: Long): String = "ce-$id"

private data class CustomEmojiRange(val start: Int, val end: Int, val emojiId: Long)

private fun buildFromFormatted(
    formatted: FormattedText,
    accent: Color,
    codeBg: Color,
    mute: Color,
    onSurface: Color,
    uriHandler: UriHandler,
    revealedSpoilers: Set<Int>,
    onSpoilerTap: (Int) -> Unit,
): BuiltAnnotated {
    val linkRanges = mutableListOf<LinkRange>()
    val annotated = buildAnnotatedString {
    val srcText = formatted.text
    val srcLen = srcText.length

    // Sort custom-emoji ranges so we can stream the source text and substitute each range
    // with a single inline-content placeholder. TDLib guarantees these spans don't overlap
    // (one entity per emoji glyph), so a simple linear pass is sufficient.
    val customEmojiRanges = formatted.spans
        .mapNotNull { sp ->
            (sp.style as? FormattedText.Style.CustomEmoji)?.let { ce ->
                CustomEmojiRange(
                    start = sp.start.coerceIn(0, srcLen),
                    end = sp.end.coerceIn(0, srcLen),
                    emojiId = ce.emojiId,
                )
            }
        }
        .filter { it.start < it.end }
        .sortedBy { it.start }

    // positionMap[srcIdx] = corresponding dst index in the AnnotatedString we're building.
    // Used to re-anchor all OTHER spans (bold, URL, mention…) onto the rebuilt string —
    // collapsing each multi-codepoint emoji range into one placeholder character shifts
    // every downstream offset, and a naïve `addStyle(start, end)` would land on the wrong
    // glyph. The map is filled while we walk the source.
    val positionMap = IntArray(srcLen + 1)
    var dst = 0
    var src = 0
    var rangeIdx = 0
    while (src < srcLen) {
        if (rangeIdx < customEmojiRanges.size && src == customEmojiRanges[rangeIdx].start) {
            val r = customEmojiRanges[rangeIdx]
            // All source positions inside [start, end) collapse to the placeholder dst.
            for (i in r.start until r.end) positionMap[i] = dst
            // U+FFFC is the official Object Replacement Character — a stable, invisible
            // 1-char anchor that paints nothing if the inline content fails to resolve.
            appendInlineContent(customEmojiTag(r.emojiId), "￼")
            dst += 1
            src = r.end
            rangeIdx++
        } else {
            positionMap[src] = dst
            append(srcText[src])
            src++; dst++
        }
    }
    positionMap[srcLen] = dst

    val linkStyle = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    val mentionStyle = TextLinkStyles(SpanStyle(color = accent))
    // tg://… URIs throw ActivityNotFoundException when no Telegram client is installed.
    // openUri propagates that synchronously from inside the gesture handler — we'd crash.
    // Wrap in runCatching so the tap is a no-op instead.
    val safeOpen = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
    }

    formatted.spans.forEachIndexed { idx, span ->
        // CustomEmoji ranges have already been collapsed into inline-content placeholders
        // above — applying their span here would produce a no-op style on a 1-char anchor.
        if (span.style is FormattedText.Style.CustomEmoji) return@forEachIndexed

        // Re-anchor onto the rebuilt string. The positionMap is dense over the source so
        // a span that ENDED at the inclusive last index (end == srcLen) maps cleanly to
        // dst's tail.
        val srcStart = span.start.coerceIn(0, srcLen)
        val srcEnd = span.end.coerceIn(srcStart, srcLen)
        val start = positionMap[srcStart]
        val end = positionMap[srcEnd]
        if (end == start) return@forEachIndexed
        when (val s = span.style) {
            is FormattedText.Style.TextUrl -> {
                addLink(LinkAnnotation.Url(s.url, linkStyle, safeOpen), start, end)
                linkRanges += LinkRange(start, end, s.url)
            }
            FormattedText.Style.Url -> {
                // Inline URL — read the URL from the SOURCE text (placeholders aren't part
                // of the URL even when the span happens to wrap one).
                val url = normalizeUrl(srcText.substring(srcStart, srcEnd))
                addLink(LinkAnnotation.Url(url, linkStyle, safeOpen), start, end)
                linkRanges += LinkRange(start, end, url)
            }
            FormattedText.Style.Mention -> {
                // @username — resolve via tg:// so the official client opens the profile.
                val handle = srcText.substring(srcStart, srcEnd).trimStart('@')
                if (handle.isNotEmpty()) {
                    val url = "tg://resolve?domain=$handle"
                    addLink(LinkAnnotation.Url(url, mentionStyle, safeOpen), start, end)
                    linkRanges += LinkRange(start, end, url)
                }
            }
            FormattedText.Style.Hashtag -> {
                // tg://search opens Telegram's global search with the tag preselected.
                val tag = srcText.substring(srcStart, srcEnd)
                val url = "tg://search?query=$tag"
                addLink(LinkAnnotation.Url(url, mentionStyle, safeOpen), start, end)
                linkRanges += LinkRange(start, end, url)
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
    return BuiltAnnotated(annotated, linkRanges.toList())
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
