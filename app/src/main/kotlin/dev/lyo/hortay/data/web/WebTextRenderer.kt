package dev.lyo.hortay.data.web

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts Telegram channel-preview post HTML (the inner HTML of
 * `.tgme_widget_message_text`) into a Compose [WebTextContent] — an [AnnotatedString]
 * paired with the metadata UI needs to render inline custom emoji as animated
 * Lottie / WebM stickers (resolved via [WebCustomEmojiResolver]) rather than as
 * static unicode fallbacks.
 *
 * Why a hand-rolled DOM walker instead of [androidx.compose.ui.text.AnnotatedString.Companion.fromHtml]:
 *   1. fromHtml mishandles Private Use Area code points we tried using as markers
 *      for `<tg-emoji>` placeholders — they get filtered or repositioned during the
 *      HTML→AnnotatedString conversion, breaking any post-processing splice.
 *   2. `<tg-emoji emoji-id="X">` needs to become an `appendInlineContent` placeholder
 *      directly during construction so the `inlineContent` map binds correctly. There
 *      is no fromHtml hook for that.
 *   3. Telegram-specific tags `<tg-spoiler>` and the styled `<i class="emoji">`
 *      wrapper aren't in fromHtml's whitelist anyway.
 *   4. The DOM walker is strictly testable on the JVM without Compose runtime — the
 *      previous design needed Android instrumentation for any end-to-end test.
 *
 * Selector evidence: tags surveyed across the 8 real fixtures under
 * `app/src/test/resources/tme-samples/`. Frequencies (descending): `<br>` 731,
 * `<b>` 330, `<a>` 314, `<div>` 141, `<i>` 101, `<tg-emoji>` 67, `<blockquote>` 5,
 * `<u>` 2.
 */
object WebTextRenderer {

    /** Inline content key prefix consumed by the UI's `inlineContent` map. */
    const val INLINE_EMOJI_KEY_PREFIX = "emoji_"

    /**
     * Convert raw post HTML to a [WebTextContent] suitable for binding to a Compose
     * `Text` composable with an `inlineContent` map. Returns empty content for blank
     * input.
     */
    fun render(html: String, linkStyles: TextLinkStyles? = null): WebTextContent {
        if (html.isBlank()) return WebTextContent.Empty

        val doc = Jsoup.parseBodyFragment(html)
        val emojiFallbacks = mutableMapOf<String, String>()
        val emojiIds = mutableSetOf<String>()
        val styles = linkStyles ?: DefaultLinkStyles

        val text = buildAnnotatedString {
            doc.body().childNodes().forEach { node ->
                renderNode(node, styles, emojiFallbacks, emojiIds)
            }
        }
        return WebTextContent(
            text = text,
            emojiFallbacks = emojiFallbacks,
            emojiIds = emojiIds,
        )
    }

    private fun AnnotatedString.Builder.renderNode(
        node: Node,
        linkStyles: TextLinkStyles,
        emojiFallbacks: MutableMap<String, String>,
        emojiIds: MutableSet<String>,
    ) {
        when (node) {
            is TextNode -> append(node.text())
            is Element -> renderElement(node, linkStyles, emojiFallbacks, emojiIds)
            else -> Unit // comments, doctype, etc.
        }
    }

    private fun AnnotatedString.Builder.renderElement(
        el: Element,
        linkStyles: TextLinkStyles,
        emojiFallbacks: MutableMap<String, String>,
        emojiIds: MutableSet<String>,
    ) {
        when (el.normalName()) {
            "br" -> append('\n')

            "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
            }

            "i", "em" -> {
                // <i class="emoji"> is a Telegram-styled unicode wrapper — keep just
                // the inner glyph (the bg-image styling is unstylable here).
                if (el.normalName() == "i" && el.hasClass("emoji")) {
                    val glyph = el.selectFirst("b")?.text() ?: el.ownText()
                    if (glyph.isNotBlank()) append(glyph)
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
                    }
                }
            }

            "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
            }

            "s", "del", "strike" -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ) {
                renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
            }

            "a" -> {
                val href = el.attr("href").trim()
                if (href.isBlank()) {
                    renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
                } else {
                    withLink(LinkAnnotation.Url(href, linkStyles)) {
                        if (el.childNodeSize() == 0) append(href)
                        else renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
                    }
                }
            }

            "div", "p" -> {
                // Telegram uses bare <div> wrappers as paragraph breaks. Insert a
                // newline before each subsequent block (the very first one carries
                // no leading break — would otherwise add a blank line at the top).
                if (length > 0) append('\n')
                renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
            }

            "tg-emoji" -> {
                val emojiId = el.attr("emoji-id").trim()
                if (emojiId.isEmpty() || !emojiId.all(Char::isDigit)) {
                    // Malformed: pass through any inner content so we don't lose text.
                    renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
                    return
                }
                val fallbackGlyph = el.selectFirst("b")?.text()
                    ?: el.selectFirst("i.emoji")?.text()
                    ?: ""
                emojiFallbacks[emojiId] = fallbackGlyph
                emojiIds.add(emojiId)
                appendInlineContent(
                    id = INLINE_EMOJI_KEY_PREFIX + emojiId,
                    alternateText = fallbackGlyph.ifEmpty { "￼" },
                )
            }

            "tg-spoiler" -> withStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
            ) {
                renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
            }

            "blockquote" -> {
                if (length > 0) append('\n')
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
                }
            }

            else -> renderChildren(el, linkStyles, emojiFallbacks, emojiIds)
        }
    }

    private fun AnnotatedString.Builder.renderChildren(
        el: Element,
        linkStyles: TextLinkStyles,
        emojiFallbacks: MutableMap<String, String>,
        emojiIds: MutableSet<String>,
    ) {
        el.childNodes().forEach { child ->
            renderNode(child, linkStyles, emojiFallbacks, emojiIds)
        }
    }

    /**
     * Default link styling: italic + slight emphasis. Callers can pass their own
     * theme-coloured [TextLinkStyles] when they have access to MaterialTheme.
     */
    val DefaultLinkStyles: TextLinkStyles = TextLinkStyles(
        style = SpanStyle(fontStyle = FontStyle.Italic),
    )

    /**
     * Strip HTML tags to plain text suitable for FTS5 indexing and "first 280 chars"
     * preview snippets. Pure JVM (no Compose dependency) so it can run inside
     * [dev.lyo.hortay.data.web.WebRepository] before the post ever reaches a Composable.
     *
     * Implementation detail: Jsoup's `.text()` collapses runs of whitespace and
     * inserts a single space at block boundaries — exactly what we want for search
     * (matches don't depend on whether the source had `\n\n` or `<br><br>`).
     * `<tg-emoji>` is preserved as its inner unicode glyph (the `<b>` fallback)
     * so a search for "🔥" matches posts that used the custom-emoji wrapper too.
     */
    fun toPlainText(html: String): String {
        if (html.isBlank()) return ""
        return Jsoup.parseBodyFragment(html).body().text()
    }
}

/**
 * Output of [WebTextRenderer.render] — everything a Compose `Text` composable needs
 * to display a Telegram-style post with proper formatting and inline custom emoji.
 *
 * Bind via `Text(text = content.text, inlineContent = …)` where the inline-content
 * map is built by the UI layer — typically one entry per [emojiIds] member with the
 * key `${WebTextRenderer.INLINE_EMOJI_KEY_PREFIX}$emojiId`.
 */
@Immutable
data class WebTextContent(
    val text: AnnotatedString,
    /**
     * emoji-id → unicode fallback glyph extracted from `<tg-emoji>`'s `<b>` child,
     * or empty string when Telegram embedded only an id (Premium-only animated emoji
     * with no unicode hint).
     */
    val emojiFallbacks: Map<String, String>,
    /**
     * All emoji-ids that need [WebCustomEmojiResolver] hits before the post can be
     * rendered with full fidelity.
     */
    val emojiIds: Set<String>,
) {
    val isNotEmpty: Boolean get() = text.isNotEmpty()
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val Empty = WebTextContent(AnnotatedString(""), emptyMap(), emptySet())
    }
}
