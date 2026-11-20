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

                // Every parsed media item must have a non-empty url, with one
                // intentional exception: Kind.Video items emitted as the
                // "Media is too big" sentinel use url = "" to flag "no
                // playable stream, render the poster + open-in-Telegram CTA"
                // (see TmePageParser:200 and AlbumItem.isUnplayableVideo).
                // Those still carry a thumbnailUrl so the poster renders.
                page.posts.flatMap { it.media }.forEach { media ->
                    if (media.kind == WebMedia.Kind.Video && media.url.isEmpty()) {
                        assertTrue(
                            !media.thumbnailUrl.isNullOrBlank(),
                            "unplayable-video sentinel must carry a thumbnailUrl",
                        )
                    } else {
                        assertTrue(media.url.isNotBlank(), "blank media url found")
                    }
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
    fun `document posts keep the file with its title`() {
        val page = TmePageParser.parse(loadFixture("meduzalive-real.html"), "meduzalive")!!
        val documents = page.posts.flatMap { it.media }.filter { it.kind == WebMedia.Kind.Document }
        assertTrue(documents.isNotEmpty(), "expected a document in the meduzalive sample")
        documents.forEach { doc ->
            assertTrue(doc.url.startsWith("https://t.me/"), "document link not a t.me URL: ${doc.url}")
            assertFalse(doc.title.isNullOrBlank(), "document without a title")
        }
    }

    @Test
    fun `service messages are not posts`() {
        val html = loadFixture("disgustingmen-real.html")
        assertTrue("service_message" in html, "fixture no longer carries a service message")
        val page = TmePageParser.parse(html, "disgustingmen")!!
        assertFalse(page.posts.any { it.seq == 15142L }, "the pin service message parsed as a post")
    }

    @Test
    fun `standalone photo proportions and unplayable video duration survive`() {
        val html = pageWith(
            """
            <div class="tgme_widget_message_wrap">
              <div class="tgme_widget_message" data-post="test/7">
                <a class="tgme_widget_message_photo_wrap" style="width:800px;background-image:url('https://cdn.example/p.jpg')">
                  <div class="tgme_widget_message_photo" style="padding-top:62.5%"></div>
                </a>
                <a class="tgme_widget_message_video_player not_supported" href="">
                  <i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn.example/t.jpg')"></i>
                  <div class="tgme_widget_message_video_wrap" style="width:1080px;padding-top:100%"></div>
                  <time class="message_video_duration">1:15</time>
                </a>
                <a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a>
              </div>
            </div>
            """,
        )
        val (photo, video) = TmePageParser.parse(html, "test")!!.posts.single().media
        assertEquals(1.6f, photo.aspectRatio)
        assertEquals("", video.url)
        assertEquals(75, video.durationSec)
        assertEquals(1f, video.aspectRatio)
    }

    @Test
    fun `a page with only a service row is an empty channel, not a parse failure`() {
        val html = pageWith(
            """
            <div class="tgme_widget_message_wrap">
              <div class="tgme_widget_message service_message" data-post="test/1">
                <div class="tgme_widget_message_text">Channel created</div>
              </div>
            </div>
            """,
        )
        val page = TmePageParser.parse(html, "test")
        assertNotNull(page)
        assertTrue(page!!.posts.isEmpty())
    }

    @Test
    fun `a forward from a hidden source is still a forward`() {
        val html = pageWith(
            """
            <div class="tgme_widget_message_wrap">
              <div class="tgme_widget_message" data-post="test/7">
                <div class="tgme_widget_message_forwarded_from accent_color">Forwarded from&nbsp;<span class="tgme_widget_message_forwarded_from_name"><span dir="auto"></span></span>&nbsp;(<span class="tgme_widget_message_forwarded_from_author" dir="auto">Jane Doe</span>)</div>
                <div class="tgme_widget_message_text js-message_text">Hello</div>
                <a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a>
              </div>
            </div>
            """,
        )
        val forward = TmePageParser.parse(html, "test")!!.posts.single().forwardedFrom
        assertEquals(WebForwardSource(channelName = "", channelLink = "", author = "Jane Doe"), forward)
    }

    @Test
    fun `signature, edited mark and reply header are read`() {
        val html = pageWith(
            """
            <div class="tgme_widget_message_wrap">
              <div class="tgme_widget_message" data-post="test/7">
                <a class="tgme_widget_message_reply" href="https://t.me/other/41">
                  <i class="tgme_widget_message_reply_thumb" style="background-image:url('https://cdn.example/r.jpg')"></i>
                  <div class="tgme_widget_message_author"><span class="tgme_widget_message_author_name">Other</span></div>
                  <div class="tgme_widget_message_text js-message_reply_text">Parent text…</div>
                </a>
                <div class="tgme_widget_message_text js-message_text">Body</div>
                <span class="tgme_widget_message_meta"><span class="tgme_widget_message_from_author">Jane</span>,&nbsp;edited &nbsp;<a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a></span>
              </div>
            </div>
            <div class="tgme_widget_message_wrap">
              <div class="tgme_widget_message" data-post="test/8">
                <div class="tgme_widget_message_text js-message_text">Plain</div>
                <span class="tgme_widget_message_meta"><span class="tgme_widget_message_from_author">edited</span>,&nbsp;<a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a></span>
              </div>
            </div>
            """,
        )
        val (first, second) = TmePageParser.parse(html, "test")!!.posts
        assertEquals("Body", first.textHtml)
        assertEquals(
            WebPostExtras(
                authorSignature = "Jane",
                isEdited = true,
                reply = WebReply("https://t.me/other/41", "Other", "Parent text…", "https://cdn.example/r.jpg"),
            ),
            first.extras,
        )
        // A signature that reads "edited" is not the edited mark.
        assertEquals(WebPostExtras(authorSignature = "edited"), second.extras)
    }

    @Test
    fun `link message id is read from post links`() {
        assertEquals(214L, WebPostAdapter.linkMessageId("https://t.me/petyaikomp/214"))
        assertEquals(116548L, WebPostAdapter.linkMessageId("https://t.me/nexta_live/116548?single"))
        assertNull(WebPostAdapter.linkMessageId("https://t.me/petyaikomp"))
    }

    private fun pageWith(history: String): String = """
        <html><body>
          <div class="tgme_channel_info">
            <div class="tgme_channel_info_header">
              <div class="tgme_channel_info_header_title"><span>Test</span></div>
              <div class="tgme_channel_info_header_username"><a href="">@test</a></div>
            </div>
          </div>
          <section class="tgme_channel_history">$history</section>
        </body></html>
    """.trimIndent()

    @Test
    fun `mixed albums keep photos and videos in page order`() {
        fun photo(n: Int) =
            """<a class="tgme_widget_message_photo_wrap" style="width:100px;background-image:url('https://cdn.example/p$n.jpg');padding-top:50%"></a>"""
        fun video(n: Int) = """
            <a class="tgme_widget_message_video_player" href="">
              <i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn.example/t$n.jpg')"></i>
              <div class="tgme_widget_message_video_wrap" style="width:100px;padding-top:50%"><video src="https://cdn.example/v$n.mp4"></video></div>
              <time class="message_video_duration">0:0$n</time>
            </a>"""
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
                  <div class="tgme_widget_message" data-post="test/7">
                    <div class="tgme_widget_message_bubble">
                      <div class="tgme_widget_message_grouped_layer">
                        ${photo(1)}${video(2)}${photo(3)}${video(4)}
                      </div>
                      <a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a>
                    </div>
                  </div>
                </div>
              </section>
            </body></html>
        """.trimIndent()
        val media = TmePageParser.parse(html, "test")!!.posts.single().media
        assertEquals(
            listOf("p1.jpg", "v2.mp4", "p3.jpg", "v4.mp4"),
            media.map { it.url.substringAfterLast('/') },
        )
        assertEquals(listOf(null, 2, null, 4), media.map { it.durationSec })
    }

    @Test
    fun `forward source handle is the channel, not the message id`() {
        assertEquals("meduza_news", WebPostAdapter.forwardSourceHandle("https://t.me/meduza_news/89905"))
        assertEquals("meduza_news", WebPostAdapter.forwardSourceHandle("https://t.me/s/meduza_news/89905"))
        assertEquals("meduza_news", WebPostAdapter.forwardSourceHandle("https://t.me/meduza_news"))
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

    @Test
    fun `mixed-case handle and data-post are normalised to lowercase`() {
        // The channel row's PK and the post -> channel FK are case-sensitive and keyed
        // on the lowercased subscription; a display-cased "@Durov" header must not
        // create a second channel row.
        val html = """
            <html><body>
              <div class="tgme_channel_info">
                <div class="tgme_channel_info_header">
                  <div class="tgme_channel_info_header_title"><span>Durov</span></div>
                  <div class="tgme_channel_info_header_username"><a href="">@Durov</a></div>
                </div>
              </div>
              <section class="tgme_channel_history">
                <div class="tgme_widget_message_wrap">
                  <div class="tgme_widget_message" data-post="Durov/7">
                    <div class="tgme_widget_message_bubble">
                      <div class="tgme_widget_message_text">Hello</div>
                      <a class="tgme_widget_message_date" href=""><time datetime="2025-01-01T00:00:00+00:00"></time></a>
                    </div>
                  </div>
                </div>
              </section>
            </body></html>
        """.trimIndent()
        val page = TmePageParser.parse(html, "durov")
        assertNotNull(page)
        assertEquals("durov", page!!.channel.username)
        assertEquals("durov/7", page.posts.single().id)
    }

    @Test
    fun `post wrappers present but no post parses is a parse failure`() {
        // Simulates Telegram renaming the post id attribute: every wrapper is still
        // there, but no `.tgme_widget_message[data-post]` matches any more. Must not
        // degrade into an "ok" page with zero posts.
        val broken = loadFixture("durov-real.html").replace("data-post=", "data-msg-id=")
        assertNull(TmePageParser.parse(broken, "durov"))
    }

    @Test
    fun `post markup present under renamed wrappers is a parse failure`() {
        // Wrapper class renamed: the wrapper selector finds nothing, yet the page
        // still carries `data-post` ids — a selector regression, not an empty channel.
        val broken = loadFixture("durov-real.html")
            .replace("tgme_widget_message_wrap", "tgme_widget_msg_container")
        assertNull(TmePageParser.parse(broken, "durov"))
    }

    @Test
    fun `channel page without any post markup still parses as empty`() {
        val html = """
            <html><body>
              <div class="tgme_channel_info">
                <div class="tgme_channel_info_header">
                  <div class="tgme_channel_info_header_title"><span>Quiet</span></div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val page = TmePageParser.parse(html, "quiet")
        assertNotNull(page)
        assertTrue(page!!.posts.isEmpty())
    }

    @Test
    fun `broken date selector yields empty publishedAt rather than failing the page`() {
        // The repository then keeps the stored timestamp of already-known posts
        // (see WebRepositoryMaintenanceTest) instead of re-dating them to fetch time.
        val broken = loadFixture("durov-real.html")
            .replace("tgme_widget_message_date", "tgme_widget_message_when")
        val page = TmePageParser.parse(broken, "durov")
        assertNotNull(page)
        assertTrue(page!!.posts.isNotEmpty())
        assertTrue(page.posts.all { it.publishedAt.isEmpty() })
    }

    private fun loadFixture(name: String): String {
        val resource = javaClass.classLoader!!.getResourceAsStream("tme-samples/$name")
            ?: error("Missing fixture: tme-samples/$name")
        return resource.bufferedReader().use { it.readText() }
    }

    private data class Fixture(val username: String, val file: String)

    private companion object {
        // Captured 2026-05 — refresh by re-running the curl commands documented in
        // ARCHITECTURE.md "Setup-delta" once Phase 2 stabilizes.
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
        // Telegram now allows 2-char Fragment-auctioned / Premium-short
        // handles, so the regex floor is 2 chars. Single-char is still
        // rejected as nonsensical.
        assertNull(parseUsernameFromInput("a"))
        assertNull(parseUsernameFromInput("@x"))
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
