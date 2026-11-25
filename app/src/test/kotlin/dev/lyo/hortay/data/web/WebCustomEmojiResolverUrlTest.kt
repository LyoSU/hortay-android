package dev.lyo.hortay.data.web

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebCustomEmojiResolverUrlTest {

    private fun clientReturning(json: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `https emoji and thumb urls pass through`(): Unit = runBlocking {
        val resolver = WebCustomEmojiResolver(
            clientReturning("""{"type":"webp","emoji":"https://cdn.t.me/e.webp","thumb":"https://cdn.t.me/t.png"}"""),
        )
        val resolved = resolver.resolve("1")
        assertEquals("https://cdn.t.me/e.webp", resolved?.url)
        assertEquals("https://cdn.t.me/t.png", resolved?.thumbUrl)
    }

    @Test
    fun `non-https emoji url makes the response unusable`(): Unit = runBlocking {
        for (bad in listOf("file:///data/data/x", "content://x/y", "data:image/png;base64,AA", "http://lan/e.webp")) {
            val resolver = WebCustomEmojiResolver(clientReturning("""{"type":"webp","emoji":"$bad"}"""))
            assertNull(resolver.resolve("2"), bad)
        }
    }

    @Test
    fun `non-https thumb url is dropped but the emoji stays`(): Unit = runBlocking {
        val resolver = WebCustomEmojiResolver(
            clientReturning("""{"type":"webp","emoji":"https://cdn.t.me/e.webp","thumb":"file:///sdcard/x.png"}"""),
        )
        val resolved = resolver.resolve("3")
        assertEquals("https://cdn.t.me/e.webp", resolved?.url)
        assertNull(resolved?.thumbUrl)
    }

    @Test
    fun `protocol-relative url is read as https`() {
        val resolver = WebCustomEmojiResolver(OkHttpClient())
        assertEquals("https://cdn.t.me/e.webp", resolver.safeAssetUrl("//cdn.t.me/e.webp"))
        assertNull(resolver.safeAssetUrl("https://user@cdn.t.me/e.webp"))
    }

    @Test
    fun `unsafe cached row is ignored and re-resolved over the network`(): Unit = runBlocking {
        val cache = object : WebEmojiCache {
            override suspend fun cachedEmoji(emojiId: String, freshAfterMs: Long) =
                CachedEmoji(emojiId, "webp", "file:///data/data/x", null, null)

            override suspend fun cacheEmoji(
                emojiId: String,
                type: String,
                url: String,
                thumbUrl: String?,
                sizePx: Int?,
                resolvedAtMs: Long,
            ) = Unit
        }
        val resolver = WebCustomEmojiResolver(
            clientReturning("""{"type":"webp","emoji":"https://cdn.t.me/fresh.webp"}"""),
            cache,
        )
        assertEquals("https://cdn.t.me/fresh.webp", resolver.resolve("4")?.url)
    }
}
