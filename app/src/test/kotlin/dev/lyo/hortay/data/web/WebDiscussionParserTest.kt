package dev.lyo.hortay.data.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshot tests for [WebDiscussionParser] against real discussion-widget pages saved on
 * 2026-11-27 under `app/src/test/resources/tme-discussion/`. The `loadComments` replies are
 * hand-built: the endpoint is never called from development machines, so the JSON shape is
 * taken from the widget's own script (`discussion-widget.js`) and the HTML inside it from
 * the saved pages, which use the same markup.
 */
class WebDiscussionParserTest {

    @Test
    fun `lyblog thread page yields keys, endpoint, count and markers`() {
        val page = WebDiscussionParser.parsePage(load("lyblog_8192.html")) as DiscussionPage.Thread
        assertEquals("c1273270182_-3536418233664018070", page.keys.peer)
        assertEquals(1773545L, page.keys.topMsgId)
        assertEquals("a27555e767d19654b2", page.keys.discussionHash)
        assertEquals("https://t.me/api/method?api_hash=1f4736830bf40aa915", page.apiUrl)
        assertEquals(86, page.totalCount)
        assertEquals(listOf(1774847L, 1774848L, 1774849L, 1774850L, 1774851L), page.fragment.comments.map { it.id })
        assertEquals(1774847L, page.fragment.beforeCursor)
        assertEquals(1774851L, page.fragment.afterCursor)
    }

    @Test
    fun `comment authors, avatars, dates, replies and reactions`() {
        val page = WebDiscussionParser.parsePage(load("lyblog_8192.html")) as DiscussionPage.Thread
        val first = page.fragment.comments.first()
        assertEquals("Yurika", first.authorName)
        assertTrue(first.avatarUrl!!.startsWith("https://cdn"))
        assertEquals("2026-11-23T18:27:07+00:00", first.publishedAt)
        assertEquals(1774846L, first.replyToId)
        assertEquals("БК УКРСТАНДАРТ", first.replyAuthor)
        assertEquals("Screenshot_20261123-201951.png", first.replyExcerpt)
        assertNotNull(first.replyThumbUrl)
        assertEquals(1, first.reactions.size)
        assertEquals("🤣", first.reactions[0].glyph)
        assertEquals("1", first.reactions[0].count)
        // Own text, not the reply template's flattened copy.
        assertTrue(first.textHtml.contains("<br>"), first.textHtml)
        assertFalse(first.isEdited)

        val second = page.fragment.comments[1]
        assertEquals("БК УКРСТАНДАРТ", second.authorName)
        assertEquals(1774847L, second.replyToId)
        assertTrue(second.reactions.isEmpty())
        // The reply header's text must not leak into the comment body.
        assertFalse(second.textHtml.contains("мазохізму"))
    }

    @Test
    fun `tginfo thread has an album comment, a nested reply, an edit and letter avatars`() {
        val page = WebDiscussionParser.parsePage(load("tginfo_4462.html")) as DiscussionPage.Thread
        assertEquals(34, page.totalCount)
        assertEquals(1840097L, page.keys.topMsgId)
        val byId = page.fragment.comments.associateBy { it.id }
        assertEquals(setOf(1840264L, 1840305L, 1840307L, 1840352L), byId.keys)

        val edited = byId.getValue(1840264L)
        assertTrue(edited.isEdited)
        assertNull(edited.replyToId)

        val album = byId.getValue(1840305L)
        assertEquals(2, album.media.size)
        assertTrue(album.media.all { it.kind == WebMedia.Kind.Photo })
        assertTrue(album.textHtml.contains("Обычная показуха"))

        val reply = byId.getValue(1840307L)
        assertEquals(1840305L, reply.replyToId)
        // The quoted comment's thumbnail is the reply header's, not the reply's own media.
        assertTrue(reply.media.isEmpty())

        // A generated letter tile (`data:` SVG) is not a loadable avatar.
        assertNull(byId.getValue(1840352L).avatarUrl)
        assertEquals("High Tech", byId.getValue(1840352L).authorName)
    }

    @Test
    fun `second lyblog fixture parses`() {
        val page = WebDiscussionParser.parsePage(load("lyblog_8194.html")) as DiscussionPage.Thread
        assertTrue(page.fragment.comments.isNotEmpty())
        assertTrue(page.fragment.comments.all { it.authorName.isNotBlank() && it.publishedAt.isNotBlank() })
    }

    @Test
    fun `not available page`() {
        assertEquals(DiscussionPage.Unavailable, WebDiscussionParser.parsePage(load("durov_573.html")))
    }

    @Test
    fun `unrelated markup is a parse failure, not an empty thread`() {
        assertNull(WebDiscussionParser.parsePage("<html><body><p>Hello</p></body></html>"))
        // Keys present, endpoint missing.
        val noEndpoint = load("lyblog_8192.html").replace("TWidgetAuth.init(", "Other.init(")
        assertNull(WebDiscussionParser.parsePage(noEndpoint))
    }

    @Test
    fun `endpoint off t me is refused`() {
        val moved = load("lyblog_8192.html").replace("https:\\/\\/t.me\\/api\\/method", "https:\\/\\/evil.example\\/api\\/method")
        assertNull(WebDiscussionParser.parsePage(moved))
        assertTrue(WebDiscussionParser.isTmeApiUrl("https://t.me/api/method?api_hash=x"))
        assertFalse(WebDiscussionParser.isTmeApiUrl("http://t.me/api/method"))
        assertFalse(WebDiscussionParser.isTmeApiUrl("https://t.me.evil.example/api/method"))
    }

    @Test
    fun `loadComments ok reply`() {
        val body = buildJsonObject {
            put("comments_html", JsonPrimitive(OLDER_FRAGMENT))
            put("header_html", JsonPrimitive("86 comments"))
            put("comments_cnt", JsonPrimitive(86))
            put("slowmode_wait", JsonPrimitive(0))
        }.toString()
        val reply = WebDiscussionParser.parseLoadCommentsReply(body) as LoadCommentsReply.Ok
        assertEquals(86, reply.totalCount)
        assertEquals(listOf(1774700L, 1774701L), reply.fragment.comments.map { it.id })
        assertEquals(1774700L, reply.fragment.beforeCursor)
        assertNull(reply.fragment.afterCursor)
        assertEquals("Ann", reply.fragment.comments[0].authorName)
    }

    @Test
    fun `loadComments reply without more marker means the start of the thread`() {
        val html = OLDER_FRAGMENT.substringAfter("</div>")
        val body = Json.encodeToString(mapOf("comments_html" to html))
        val reply = WebDiscussionParser.parseLoadCommentsReply(body) as LoadCommentsReply.Ok
        assertNull(reply.fragment.beforeCursor)
        assertNull(reply.totalCount)
    }

    @Test
    fun `loadComments errors`() {
        assertEquals(
            LoadCommentsReply.FloodWait(7),
            WebDiscussionParser.parseLoadCommentsReply("""{"error":"FLOOD_WAIT","flood_wait":7}"""),
        )
        assertEquals(LoadCommentsReply.Failed, WebDiscussionParser.parseLoadCommentsReply("""{"error":"PEER_INVALID"}"""))
        assertEquals(LoadCommentsReply.Failed, WebDiscussionParser.parseLoadCommentsReply("<html>nope</html>"))
        assertEquals(LoadCommentsReply.Failed, WebDiscussionParser.parseLoadCommentsReply("""{"ok":true}"""))
    }

    private fun load(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("tme-discussion/$name")!!.bufferedReader().use { it.readText() }

    companion object {
        /** A `comments_html` fragment in the saved pages' markup: an older-page marker + two comments. */
        internal val OLDER_FRAGMENT = """
            <div class="tme_messages_more accent_bghover js-messages_more" data-before="1774700">Show 30 more comments</div>
            <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post-id="1774700">
              <div class="tgme_widget_message_user"><a href="https://t.me/ann"><i class="tgme_widget_message_user_photo" data-content="A"><img src="https://cdn4.telesco.pe/file/a.jpg"></i></a></div>
              <div class="tgme_widget_message_bubble">
                <div class="tgme_widget_message_author accent_color"><a class="tgme_widget_message_author_name" href="https://t.me/ann"><span dir="auto" class="name">Ann</span></a></div>
                <div class="tgme_widget_message_text js-message_text" dir="auto">First!</div>
                <div class="tgme_widget_message_footer js-message_footer"><a class="tgme_widget_message_date" href="https://t.me/lyblog/8192?comment=1774700"><time datetime="2026-11-23T10:00:00+00:00">Nov 23</time></a></div>
              </div>
            </div></div>
            <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post-id="1774701">
              <div class="tgme_widget_message_user"><i class="tgme_widget_message_user_photo" data-content="B"><img src="data:image/svg+xml;base64,AA"></i></div>
              <div class="tgme_widget_message_bubble">
                <div class="tgme_widget_message_author accent_color"><span class="tgme_widget_message_author_name"><span dir="auto" class="name">Bob</span></span></div>
                <div class="tgme_widget_message_reply js-reply_to" data-reply-to="1774700">
                  <div class="tgme_widget_message_author accent_color"><span class="tgme_widget_message_author_name" dir="auto">Ann</span></div>
                  <div class="tgme_widget_message_text js-message_reply_text" dir="auto">First!</div>
                </div>
                <div class="tgme_widget_message_text js-message_text" dir="auto">Second</div>
                <div class="tgme_widget_message_footer js-message_footer"><a class="tgme_widget_message_date" href="https://t.me/lyblog/8192?comment=1774701"><time datetime="2026-11-23T10:01:00+00:00">Nov 23</time></a></div>
              </div>
            </div></div>
        """.trimIndent()
    }
}
