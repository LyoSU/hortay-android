package dev.lyo.hortay.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Snapshot tests for [TmePageParser].
 *
 * Fixtures under `app/src/test/resources/tme-samples/` (files named `<channel>-real.html`)
 * are byte-for-byte captures of `https://t.me/s/<channel>` made with curl + a stable
 * mobile-Chrome User-Agent.
 * (or by hand via curl). Every assertion in this suite holds across all of them — when
 * Telegram changes their preview HTML, this suite fails before a user does on device.
 *
 * Two assertion strategies coexist here:
 *   1. **Per-channel structural invariants** (TestFactory): every fixture must produce
 *      a non-null page with a non-empty title, parseable posts, and chronologically
 *      ordered seq ids. Channel-specific facts (title text, subscriber string) drift
 *      with edits and view counts, so we don't pin those — we pin shape, not content.
 *   2. **Targeted assertions on known-good fixtures**: certain channels reliably have
 *      reactions / forwards / videos / verified marks. We pick the smallest fixture
 *      that demonstrates each feature and exercise it directly.
 */
class TmePageParserTest {

    @TestFactory
    fun `every snapshot parses with valid invariants`(): List<DynamicTest> =
        FIXTURES.map { fixture ->
            DynamicTest.dynamicTest("invariants for ${fixture.username}") {
                val page = TmePageParser.parse(loadFixture(fixture.file), fixture.username)
                assertNotNull(page, "parser returned null for ${fixture.username}")
                page!!

                assertTrue(page.channel.title.isNotBlank(), "title missing for ${fixture.username}")
                assertEquals(
                    fixture.username,
                    page.channel.username,
                    "username mismatch for ${fixture.username}",
                )

                assertTrue(
                    page.posts.isNotEmpty(),
                    "no posts parsed for ${fixture.username}",
                )

                // Every post id must follow the "<channel>/<seq>" shape.
                page.posts.forEach { post ->
                    assertTrue(
                        post.id.startsWith("${fixture.username}/"),
                        "post id ${post.id} not scoped to ${fixture.username}",
                    )
                    assertTrue(post.seq > 0, "non-positive seq in ${post.id}")
                    assertTrue(
                        post.publishedAt.isNotBlank(),
                        "missing publishedAt in ${post.id}",
                    )
                }

                // Telegram serves posts oldest-first within the rendered page; older
                // cursor must equal the smallest seq we saw.
                val expectedCursor = page.posts.minOf { it.seq }.toString()
                assertEquals(expectedCursor, page.olderCursor)

                // Every parsed media item must have a non-empty url. Empty strings here
                // would crash Coil downstream; better to drop.
                page.posts.flatMap { it.media }.forEach { media ->
                    assertTrue(media.url.isNotBlank(), "blank media url found")
                }

                // Reactions never carry a blank count when present (it's the trailing
                // text node of the span — empty would mean a parser regression).
                page.posts.flatMap { it.reactions }.forEach { reaction ->
                    assertTrue(
                        reaction.count.isNotBlank(),
                        "blank reaction count for ${reaction.glyph}/${reaction.emojiId}",
                    )
                }
            }
        }

    @Test
    fun `durov channel is verified and exposes locale-aware subscriber count`() {
        val page = TmePageParser.parse(loadFixture("durov-real.html"), "durov")!!
        assertTrue(page.channel.isVerified, "expected durov channel verified")
        assertNotNull(page.channel.subscribers)
        assertTrue(
            page.channel.subscribers!!.isNotBlank(),
            "subscribers string should be present",
        )
    }

    @Test
    fun `unicode reactions surface their visible glyph`() {
        // disgustingmen and meduzalive have stock unicode reactions (❤ 🔥 👍 etc).
        // Across both samples we expect at least a handful of reactions where the
        // glyph is a non-empty unicode codepoint, NOT the paid-star fallback.
        val unicodeReactions = listOf("disgustingmen-real.html", "meduzalive-real.html")
            .flatMap { fixture ->
                val username = fixture.removeSuffix("-real.html")
                TmePageParser.parse(loadFixture(fixture), username)?.posts.orEmpty()
            }
            .flatMap { it.reactions }
            .filterNot { it.isPaid }
            .filter { it.emojiId == null } // exclude custom-emoji reactions

        assertTrue(
            unicodeReactions.isNotEmpty(),
            "expected at least one unicode reaction in disgustingmen + meduzalive",
        )
        unicodeReactions.forEach { reaction ->
            assertTrue(
                reaction.glyph.isNotBlank(),
                "unicode reaction without glyph (count=${reaction.count})",
            )
            // The PAID_REACTION_GLYPH constant is a sentinel for the paid path —
            // unicode reactions should never resolve to it.
            assertFalse(
                reaction.glyph == "⭐" && !reaction.isPaid,
                "unicode reaction unexpectedly resolved to paid sentinel",
            )
        }
        // Sanity: across two large-fixture channels we should observe at least 3
        // distinct glyphs (otherwise something is collapsing them all).
        val distinct = unicodeReactions.map { it.glyph }.toSet()
        assertTrue(
            distinct.size >= 3,
            "expected ≥3 distinct unicode glyphs, got $distinct",
        )
    }

    @Test
    fun `reactions parse with paid star marker and emoji ids`() {
        // durov reliably has both paid (⭐) and custom-emoji reactions across snapshots.
        val page = TmePageParser.parse(loadFixture("durov-real.html"), "durov")!!
        val reactions = page.posts.flatMap { it.reactions }
        assertTrue(reactions.isNotEmpty(), "expected durov sample to contain reactions")

        val paid = reactions.filter { it.isPaid }
        assertTrue(paid.isNotEmpty(), "expected at least one paid-star reaction")
        paid.forEach { assertTrue(it.glyph.isNotBlank()) }

        val custom = reactions.filterNot { it.isPaid }
        assertTrue(custom.isNotEmpty(), "expected at least one custom-emoji reaction")
        // Custom reactions on t.me/s/ always carry an emoji-id even when the glyph is
        // blank (Telegram paints those client-side from the id alone).
        custom.forEach { reaction ->
            assertNotNull(
                reaction.emojiId,
                "custom reaction without emoji-id (glyph=${reaction.glyph})",
            )
        }
    }

    @Test
    fun `forwarded posts surface the source channel link`() {
        // meduzalive reliably forwards from meduza_news. nexta/varlamov/tginfouk are
        // similar but meduzalive has the most consistent structure across captures.
        val page = TmePageParser.parse(loadFixture("meduzalive-real.html"), "meduzalive")!!
        val forwards = page.posts.mapNotNull { it.forwardedFrom }
        assertTrue(forwards.isNotEmpty(), "expected forwarded posts in meduzalive sample")
        forwards.forEach { fwd ->
            assertTrue(fwd.channelName.isNotBlank(), "blank forwarded channel name")
            assertTrue(
                fwd.channelLink.startsWith("https://t.me/"),
                "forward link not a t.me URL: ${fwd.channelLink}",
            )
        }
    }

    @Test
    fun `videos extract source url and a thumbnail when t_dot_me embeds them`() {
        val page = TmePageParser.parse(loadFixture("durov-real.html"), "durov")!!
        val videos = page.posts.flatMap { it.media }.filter { it.kind == WebMedia.Kind.Video }
        assertTrue(videos.isNotEmpty(), "expected at least one video in durov snapshot")
        videos.forEach { video ->
            assertTrue(video.url.startsWith("https://"), "video src not absolute: ${video.url}")
            assertNotNull(video.thumbnailUrl, "video without thumbnail")
        }
    }

    @Test
    fun `photo posts extract cdn urls from inline background-image`() {
        val page = TmePageParser.parse(loadFixture("durov-real.html"), "durov")!!
        val photos = page.posts.flatMap { it.media }.filter { it.kind == WebMedia.Kind.Photo }
        assertTrue(photos.isNotEmpty(), "expected photos in durov snapshot")
        photos.forEach { photo ->
            assertTrue(photo.url.startsWith("https://"), "photo url not absolute: ${photo.url}")
        }
    }

    @Test
    fun `web previews carry a url and at least one descriptive field`() {
        // telegram channel posts links regularly enough to make this a reliable assertion.
        val candidates = FIXTURES.flatMap { fixture ->
            TmePageParser.parse(loadFixture(fixture.file), fixture.username)?.posts.orEmpty()
        }
        val previews = candidates.mapNotNull { it.webPreview }
        assertTrue(previews.isNotEmpty(), "no web previews across all fixtures — selector regression?")
        previews.forEach { preview ->
            assertTrue(preview.url.isNotBlank())
            // At least one of {siteName, title, description, imageUrl} must be present —
            // a preview with none of those is just a bare link, which we shouldn't
            // surface as a preview at all.
            val hasContent = listOf(preview.siteName, preview.title, preview.description, preview.imageUrl)
                .any { !it.isNullOrBlank() }
            assertTrue(hasContent, "empty web preview for ${preview.url}")
        }
    }

    @Test
    fun `non-channel HTML returns null`() {
        val notAChannel = "<html><body><h1>Page not found</h1></body></html>"
        assertNull(TmePageParser.parse(notAChannel, "ghost"))
    }

    @Test
    fun `malformed posts are skipped, not fatal`() {
        // Build a synthetic header + one valid post + one broken post that lacks
        // data-post. Parser should keep the valid one and drop the broken one.
        val html = """
            <html><body>
              <div class="tgme_channel_info">
                <div class="tgme_channel_info_header">
                  <div class="tgme_channel_info_header_title"><span>Test</span></div>
                  <div class="tgme_channel_info_header_username"><a href="">@test</a></div>
                </div>
              </div>
              <section class="tgme_channel_history">
                <div class="tgme_widget_message_wrap">
                  <div class="tgme_widget_message" data-post="test/42">
                    <div class="tgme_widget_message_bubble">
                      <div class="tgme_widget_message_text">Hello</div>
                      <a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a>
                    </div>
                  </div>
                </div>
                <div class="tgme_widget_message_wrap">
                  <div class="tgme_widget_message">
                    <div class="tgme_widget_message_bubble">no data-post</div>
                  </div>
                </div>
              </section>
            </body></html>
        """.trimIndent()
        val page = TmePageParser.parse(html, "test")
        assertNotNull(page)
        assertEquals(1, page!!.posts.size)
        assertEquals(42L, page.posts.first().seq)
    }

    private fun loadFixture(name: String): String {
        val resource = javaClass.classLoader!!.getResourceAsStream("tme-samples/$name")
            ?: error("Missing fixture: tme-samples/$name")
        return resource.bufferedReader().use { it.readText() }
    }

    private data class Fixture(val username: String, val file: String)

    private companion object {
        // Captured 2026-05 — refresh by re-running the curl commands documented in
        // CLAUDE.md "Setup-delta" once Phase 2 stabilizes.
        val FIXTURES = listOf(
            Fixture("durov", "durov-real.html"),
            Fixture("telegram", "telegram-real.html"),
            Fixture("nexta_live", "nexta-real.html"),
            Fixture("disgustingmen", "disgustingmen-real.html"),
            Fixture("meduzalive", "meduzalive-real.html"),
            Fixture("varlamov_news", "varlamov_news-real.html"),
            Fixture("bbbreaking", "bbbreaking-real.html"),
            Fixture("tginfouk", "tginfouk-real.html"),
        )
    }
}

class ParseUsernameFromInputTest {

    @Test
    fun `accepts bare username`() {
        assertEquals("durov", parseUsernameFromInput("durov"))
    }

    @Test
    fun `accepts at-prefixed username`() {
        assertEquals("durov", parseUsernameFromInput("@durov"))
    }

    @Test
    fun `accepts t_dot_me url`() {
        assertEquals("durov", parseUsernameFromInput("https://t.me/durov"))
        assertEquals("durov", parseUsernameFromInput("t.me/durov"))
        assertEquals("durov", parseUsernameFromInput("http://t.me/durov/"))
    }

    @Test
    fun `accepts post link by stripping message id`() {
        assertEquals("durov", parseUsernameFromInput("https://t.me/durov/123"))
        assertEquals("durov", parseUsernameFromInput("t.me/durov/45"))
    }

    @Test
    fun `accepts s-prefixed preview link`() {
        assertEquals("durov", parseUsernameFromInput("https://t.me/s/durov"))
    }

    @Test
    fun `accepts tg-resolve scheme`() {
        assertEquals("durov", parseUsernameFromInput("tg://resolve?domain=durov"))
        assertEquals("durov", parseUsernameFromInput("tg://resolve?foo=bar&domain=durov&baz=qux"))
    }

    @Test
    fun `rejects too-short usernames`() {
        // Telegram requires 5+ chars
        assertNull(parseUsernameFromInput("abc"))
        assertNull(parseUsernameFromInput("@abcd"))
    }

    @Test
    fun `rejects non-letter starts`() {
        assertNull(parseUsernameFromInput("123abc"))
        assertNull(parseUsernameFromInput("@_invalid"))
    }

    @Test
    fun `rejects empty and whitespace`() {
        assertNull(parseUsernameFromInput(""))
        assertNull(parseUsernameFromInput("   "))
    }

    @Test
    fun `rejects non-telegram URLs`() {
        assertNull(parseUsernameFromInput("https://example.com/durov"))
        assertNull(parseUsernameFromInput("https://twitter.com/durov"))
    }
}
