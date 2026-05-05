package dev.lyo.hortay.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [WebTextRenderer] — drives the DOM walker directly. AnnotatedString
 * doesn't need any Android runtime so these run as pure JVM unit tests.
 *
 * Compose `Text` renders a placeholder character (￼, OBJECT REPLACEMENT
 * CHARACTER) at every `appendInlineContent` site; that's the published contract
 * of [androidx.compose.ui.text.AnnotatedString.Companion.appendInlineContent] and
 * we assert against it here to verify our placeholder positions.
 */
class WebTextRendererTest {

    private val INLINE_TAG = "androidx.compose.foundation.text.inlineContent"

    /**
     * Compose's `appendInlineContent(id, alternateText)` writes [alternateText] as
     * literal text in the AnnotatedString AND attaches a string-annotation tagged
     * [INLINE_TAG] over that range. When the host Composable supplies an
     * `inlineContent` map entry for the same id, the Composable's children replace
     * the alternateText in the rendered output. We assert against the annotation
     * because that's the structural property that guarantees inline rendering.
     */
    private fun inlineCount(text: androidx.compose.ui.text.AnnotatedString): Int =
        text.getStringAnnotations(INLINE_TAG, 0, text.length).size

    @Test
    fun `bold and italic spans are preserved as styled segments`() {
        val content = WebTextRenderer.render("<b>bold</b> plain <i>italic</i>")
        assertEquals("bold plain italic", content.text.toString())
        // Spans recorded for the styled ranges: bold over 0..4, italic over 11..17.
        val spans = content.text.spanStyles
        assertTrue(spans.any { it.start == 0 && it.end == 4 }, "bold span missing: $spans")
        assertTrue(spans.any { it.start == 11 && it.end == 17 }, "italic span missing: $spans")
    }

    @Test
    fun `br produces newline characters`() {
        val content = WebTextRenderer.render("first<br>second<br>third")
        assertEquals("first\nsecond\nthird", content.text.toString())
    }

    @Test
    fun `paragraph divs become newline-separated lines without leading blank`() {
        val content = WebTextRenderer.render(
            "<div>line one</div><div>line two</div><div>line three</div>",
        )
        assertEquals("line one\nline two\nline three", content.text.toString())
    }

    @Test
    fun `links carry LinkAnnotation Url over the link text`() {
        val content = WebTextRenderer.render("""before <a href="https://example.com">click me</a> after""")
        assertEquals("before click me after", content.text.toString())
        // LinkAnnotation lives on getLinkAnnotations() in Compose 1.7+. Use the
        // explicit accessor which returns the spans for url / clickable links.
        val links = content.text.getLinkAnnotations(0, content.text.length)
        assertTrue(links.isNotEmpty(), "no LinkAnnotation found")
        val urls = links.mapNotNull { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url }
        assertTrue(
            urls.contains("https://example.com"),
            "expected example.com link, got $urls",
        )
    }

    @Test
    fun `tg-emoji becomes inline placeholder annotation and emoji-id is recorded`() {
        val input = """Hello <tg-emoji emoji-id="123"><i class="emoji"><b>🔥</b></i></tg-emoji> world"""
        val content = WebTextRenderer.render(input)

        assertTrue(content.emojiIds.contains("123"))
        assertEquals("🔥", content.emojiFallbacks["123"])
        // The unicode fallback shows as plain text when no inline content map binds
        // the placeholder — that's the alternateText path.
        assertEquals("Hello 🔥 world", content.text.toString())
        // The structural guarantee for inline rendering is the annotation; visible
        // text alone doesn't tell the renderer that this region is replaceable.
        assertEquals(1, inlineCount(content.text))
    }

    @Test
    fun `tg-emoji without unicode fallback still produces a placeholder annotation`() {
        // Premium-only animated emoji case.
        val input = """text <tg-emoji emoji-id="456"></tg-emoji> more"""
        val content = WebTextRenderer.render(input)

        assertTrue(content.emojiIds.contains("456"))
        assertEquals("", content.emojiFallbacks["456"])
        // Falls back to OBJECT REPLACEMENT CHARACTER as the alternateText when there's
        // no unicode hint to render in its place.
        assertEquals("text ￼ more", content.text.toString())
        assertEquals(1, inlineCount(content.text))
    }

    @Test
    fun `tg-emoji with non-numeric id falls through to inner content`() {
        val input = """text <tg-emoji emoji-id="abc">😀</tg-emoji> more"""
        val content = WebTextRenderer.render(input)
        assertFalse(content.emojiIds.contains("abc"))
        assertTrue(content.emojiFallbacks.isEmpty())
        // Inner glyph passes through.
        assertEquals("text 😀 more", content.text.toString())
    }

    @Test
    fun `standalone i-emoji wrapper renders just the inner glyph`() {
        val content = WebTextRenderer.render(
            """<i class="emoji" style="background-image:url('//x.png')"><b>👍</b></i>""",
        )
        assertEquals("👍", content.text.toString())
        assertTrue(content.emojiIds.isEmpty())
    }

    @Test
    fun `tg-spoiler is rendered as underlined visible text`() {
        val content = WebTextRenderer.render("before <tg-spoiler>hidden</tg-spoiler> after")
        assertEquals("before hidden after", content.text.toString())
        // Underline span over the spoiler range (7..13 = "hidden").
        val underlined = content.text.spanStyles.any {
            it.start == 7 && it.end == 13 &&
                it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline
        }
        assertTrue(underlined, "expected underline span over hidden text")
    }

    @Test
    fun `blank input returns empty content`() {
        val content = WebTextRenderer.render("")
        assertTrue(content.isEmpty)
        assertTrue(content.emojiIds.isEmpty())
    }

    @Test
    fun `multiple custom emoji in one post all become placeholder annotations`() {
        val input = """<tg-emoji emoji-id="1"><b>🔥</b></tg-emoji> and <tg-emoji emoji-id="2"><b>❤</b></tg-emoji>"""
        val content = WebTextRenderer.render(input)
        assertEquals("🔥 and ❤", content.text.toString())
        assertEquals(2, inlineCount(content.text))
        assertEquals(setOf("1", "2"), content.emojiIds)
        assertEquals("🔥", content.emojiFallbacks["1"])
        assertEquals("❤", content.emojiFallbacks["2"])
    }

    @Test
    fun `real durov post with tg-emoji yields placeholders and fallback glyphs`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("tme-samples/durov-real.html")
            ?: error("durov-real.html missing")
        val page = TmePageParser.parse(
            fixture.bufferedReader().use { it.readText() },
            "durov",
        )
        val postWithEmoji = page?.posts?.firstOrNull { it.textHtml.contains("<tg-emoji") }
            ?: error("no post with tg-emoji in durov fixture")

        val content = WebTextRenderer.render(postWithEmoji.textHtml)

        assertTrue(content.emojiIds.isNotEmpty(), "expected at least one emoji-id recorded")
        // Every recorded id has a non-blank unicode fallback in durov posts.
        content.emojiIds.forEach { id ->
            val glyph = content.emojiFallbacks[id]
            assertNotNull(glyph, "no fallback for id $id")
            assertTrue(glyph!!.isNotBlank(), "blank fallback for id $id")
        }
        // Each id corresponds to exactly one inline placeholder annotation. The
        // alternateText (the unicode fallback) shows in the rendered text — that's
        // the desirable fallback when the inlineContent map can't supply a sticker.
        assertEquals(
            content.emojiIds.size,
            inlineCount(content.text),
            "placeholder annotation count mismatch",
        )
    }
}
