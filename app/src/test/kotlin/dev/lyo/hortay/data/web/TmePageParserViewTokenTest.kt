package dev.lyo.hortay.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The `data-view` tokens of a t.me/s/ page: the channel id read from the first decodable
 * one ([WebChannelPage.channelId]) and the per-post map handed to [WebViewReporter]
 * ([WebChannelPage.viewTokens]).
 */
class TmePageParserViewTokenTest {

    /** Unpadded, as t.me serves them. */
    private fun token(json: String): String =
        Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun post(seq: Int, view: String?) = """
        <div class="tgme_widget_message_wrap">
          <div class="tgme_widget_message" data-post="Test/$seq"${view?.let { " data-view=\"$it\"" }.orEmpty()}>
            <div class="tgme_widget_message_text">post $seq</div>
            <a class="tgme_widget_message_date" href=""><time datetime="2026-01-01T00:00:00+00:00"></time></a>
          </div>
        </div>"""

    private fun page(vararg posts: String) = """
        <html><body>
          <div class="tgme_channel_info">
            <div class="tgme_channel_info_header">
              <div class="tgme_channel_info_header_title"><span>Test</span></div>
              <div class="tgme_channel_info_header_username"><a href="">@test</a></div>
            </div>
          </div>
          <section class="tgme_channel_history">${posts.joinToString("")}</section>
        </body></html>
    """.trimIndent()

    @Test
    fun `album token with a string post id still yields the channel id`() {
        val album = "eyJjIjotMTEyNDAzODkwMiwicCI6IjcwNTk2ZyIsInQiOjE3Nzc5OTk2NzQsImgiOiJiNmE4ZjRkMTg0OTI1YTNiZjYifQ"
        assertEquals(1124038902L, TmePageParser.channelIdFromViewToken(album))
    }

    @Test
    fun `unpadded and url-safe tokens decode`() {
        val json = """{"c":-1006503122,"p":574,"t":1795736825,"h":"251068e411e13cc675"}"""
        val unpadded = token(json)
        assertTrue(Base64.getEncoder().encodeToString(json.toByteArray()).endsWith("="), "fixture must need padding")
        assertEquals(1006503122L, TmePageParser.channelIdFromViewToken(unpadded))
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertEquals(1006503122L, TmePageParser.channelIdFromViewToken(urlSafe))
    }

    @Test
    fun `malformed or non-negative ids are rejected`() {
        assertNull(TmePageParser.channelIdFromViewToken("not base64!"))
        assertNull(TmePageParser.channelIdFromViewToken(token("""{"p":1}""")))
        assertNull(TmePageParser.channelIdFromViewToken(token("""{"c":123}""")))
        assertNull(TmePageParser.channelIdFromViewToken(token("""{"c":"-123"}""")))
        assertNull(TmePageParser.channelIdFromViewToken(token("""[1,2]""")))
    }

    @Test
    fun `channel id comes from the first valid token and the map covers every post`() {
        val first = token("""{"c":-111,"p":1,"t":5,"h":"a"}""")
        val second = token("""{"c":-222,"p":2,"t":5,"h":"b"}""")
        val parsed = TmePageParser.parse(
            page(post(1, "garbage"), post(2, first), post(3, second), post(4, null)),
            "test",
        )!!
        assertEquals(111L, parsed.channelId)
        // Ids lowercased like WebPost.id; a post without a token is simply absent.
        assertEquals(
            mapOf("test/1" to "garbage", "test/2" to first, "test/3" to second),
            parsed.viewTokens,
        )
        assertEquals(parsed.posts.map { it.id }.toSet() - "test/4", parsed.viewTokens.keys)
    }

    @Test
    fun `a page without tokens has no channel id and no tokens`() {
        val parsed = TmePageParser.parse(page(post(1, null), post(2, null)), "test")!!
        assertNull(parsed.channelId)
        assertTrue(parsed.viewTokens.isEmpty())
    }

    @Test
    fun `real snapshots carry a token per post and the right channel id`() {
        val html = javaClass.classLoader!!.getResourceAsStream("tme-samples/varlamov_news-real.html")!!
            .bufferedReader().use { it.readText() }
        val parsed = TmePageParser.parse(html, "varlamov_news")!!
        assertEquals(1124038902L, parsed.channelId)
        assertTrue(parsed.posts.all { it.id in parsed.viewTokens }, "a parsed post has no token")
        val durov = javaClass.classLoader!!.getResourceAsStream("tme-samples/durov-real.html")!!
            .bufferedReader().use { it.readText() }
        assertEquals(1006503122L, TmePageParser.parse(durov, "durov")!!.channelId)
    }
}
