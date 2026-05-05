package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.FormattedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshot tests guarding the [WebPostAdapter.htmlToFormatted] offset contract.
 *
 * The historical regression these tests protect against: a `text.toString().trim()`
 * at the end of the walker stripped leading/trailing whitespace from the assembled
 * string while leaving span offsets indexed against the *un-trimmed* version, which
 * shifted every span by 1-2 characters whenever the source HTML began with whitespace
 * or a `<br>` tag. Cross-checking each span's substring against the rebuilt text
 * here would catch any future regression.
 */
class WebPostAdapterFormattingTest {

    @Test
    fun `bold span survives leading whitespace`() {
        val html = "  <b>Hello</b> world"
        val (text, span) = parseSingleSpan(html, FormattedText.Style.Bold::class.java)
        assertEquals("Hello world", text)
        assertEquals("Hello", text.substring(span.start, span.end))
    }

    @Test
    fun `link span maps to underlying URL text`() {
        val html = "Visit <a href=\"https://example.com\">example</a>!"
        val (text, span) = parseSingleSpan(html, FormattedText.Style.TextUrl::class.java)
        assertEquals("Visit example!", text)
        assertEquals("example", text.substring(span.start, span.end))
        assertEquals("https://example.com", (span.style as FormattedText.Style.TextUrl).url)
    }

    @Test
    fun `tg-emoji wraps fallback glyph at correct offset`() {
        val html = """Status: <tg-emoji emoji-id="5409797232497680010"><i class="emoji"><b>🔥</b></i></tg-emoji> done"""
        val text = parseAll(html).text
        // The fallback fire emoji is exactly 2 UTF-16 code units (surrogate pair).
        // Span end-start must equal those 2 units, never 1 or 3.
        val customEmojiSpan = parseAll(html).spans
            .single { it.style is FormattedText.Style.CustomEmoji }
        assertEquals("🔥", text.substring(customEmojiSpan.start, customEmojiSpan.end))
        assertTrue(text.startsWith("Status: "))
        assertTrue(text.endsWith(" done"))
    }

    @Test
    fun `blockquote span doesn't include leading separator`() {
        val html = "Above<blockquote>quoted body</blockquote>"
        val (text, span) = parseSingleSpan(html, FormattedText.Style.BlockQuote::class.java)
        // <blockquote> is a block-level element — paragraph break before AND
        // after, same contract as <div>/<p>. The span itself starts on the
        // first character of the quoted body, so the separator is outside.
        assertEquals("Above\n\nquoted body", text)
        assertEquals("quoted body", text.substring(span.start, span.end))
    }

    @Test
    fun `nested spans align`() {
        val html = "<b>BOLD <i>italic</i> rest</b>"
        val ft = parseAll(html)
        assertEquals("BOLD italic rest", ft.text)
        val bold = ft.spans.single { it.style is FormattedText.Style.Bold }
        val italic = ft.spans.single { it.style is FormattedText.Style.Italic }
        assertEquals("BOLD italic rest", ft.text.substring(bold.start, bold.end))
        assertEquals("italic", ft.text.substring(italic.start, italic.end))
    }

    @Test
    fun `paragraphs do not begin with phantom spaces from HTML indentation`() {
        // Real Telegram preview: text + newline-with-indent + block element.
        val html = "first line\n  <div>second line</div>\n  third"
        val ft = parseAll(html)
        // No paragraph should begin with whitespace.
        val lines = ft.text.split("\n")
        for (line in lines) {
            assertEquals(line.trimStart(), line, "line '$line' has phantom leading space")
        }
    }

    @Test
    fun `block element separates from inline neighbours with paragraph break`() {
        // <div> in HTML is a block-level paragraph wrapper — visually it
        // produces a blank line above and below, same as <br><br>. Our walker
        // emits leading + trailing '\n' and the normaliser caps at one blank
        // line, so siblings always sit one paragraph apart from a block.
        val html = "before<div>middle</div>after"
        val ft = parseAll(html)
        assertEquals("before\n\nmiddle\n\nafter", ft.text)
    }

    @Test
    fun `consecutive block elements separate with paragraph break`() {
        val html = "<div>A</div><div>B</div><div>C</div>"
        val ft = parseAll(html)
        assertEquals("A\n\nB\n\nC", ft.text)
    }

    @Test
    fun `consecutive newlines cap at two blank lines max`() {
        val html = "A<br><br><br><br>B"
        val ft = parseAll(html)
        // Four <br>s produce raw 4 newlines, normaliser caps at 2 (one blank
        // line maximum). This matches the visual contract Telegram itself uses.
        assertEquals("A\n\nB", ft.text)
    }

    @Test
    fun `inline whitespace adjacent to newline collapses`() {
        // Raw walker would produce `"first \n second"` from this; the
        // normaliser must strip both the trailing space before `\n` and the
        // leading space after.
        val html = "first <br> second"
        val ft = parseAll(html)
        assertEquals("first\nsecond", ft.text)
    }

    @Test
    fun `runs of inline whitespace collapse to single space`() {
        val html = "word1   word2     word3"
        assertEquals("word1 word2 word3", parseAll(html).text)
    }

    @Test
    fun `idempotent on already-normalised input`() {
        val html = "Hello world\nNext line"
        val once = parseAll(html)
        val twice = parseAll(once.text)
        assertEquals(once.text, twice.text)
    }

    @Test
    fun `every span resolves to a substring of the rebuilt text`() {
        // Mixed shape: trailing whitespace + bold + emoji + link.
        val html = """  <b>Title</b><br><tg-emoji emoji-id="42"><b>⭐</b></tg-emoji> <a href="https://t.me/foo">@foo</a>  """
        val ft = parseAll(html)
        for (sp in ft.spans) {
            assertTrue(sp.start in 0..ft.text.length, "span start ${sp.start} out of [0, ${ft.text.length}]")
            assertTrue(sp.end in sp.start..ft.text.length, "span end ${sp.end} out of [${sp.start}, ${ft.text.length}]")
        }
    }

    private fun parseAll(html: String): FormattedText =
        WebPostAdapterFormattingHook.htmlToFormatted(html)

    private fun parseSingleSpan(html: String, styleType: Class<*>): Pair<String, FormattedText.Span> {
        val ft = parseAll(html)
        val span = ft.spans.first { styleType.isInstance(it.style) }
        return ft.text to span
    }
}
