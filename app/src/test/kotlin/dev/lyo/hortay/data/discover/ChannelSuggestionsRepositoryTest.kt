package dev.lyo.hortay.data.discover

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * An offline first open must not pin [ChannelSuggestionsRepository.FALLBACK] for the
 * process: once the network comes back, the next open has to fetch the real catalog.
 */
class ChannelSuggestionsRepositoryTest {

    @TempDir
    lateinit var dir: File

    private val remoteJson = """
        {"version":1,"defaultLocale":"en",
         "categories":[{"id":"news","order":0,"title":{"en":"News"}}],
         "channels":[{"username":"remoteonly","category":"news","locales":["*"]}]}
    """.trimIndent()

    @Test
    fun `fallback after an offline open is not memoised`() = runTest {
        val calls = AtomicInteger(0)
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (calls.getAndIncrement() == 0) throw IOException("offline")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(remoteJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val repo = ChannelSuggestionsRepository(
            http = http,
            cacheFile = File(dir, "suggestions.json"),
            appVersionCode = 1,
            catalogUrl = "https://example.invalid/suggestions.json",
        )

        val offline = repo.groups("en").flatMap { it.channels }.map { it.username }
        assertEquals(listOf("LyBlog"), offline)

        val online = repo.groups("en").flatMap { it.channels }.map { it.username }
        assertEquals(listOf("remoteonly"), online)

        // Remote result IS memoised — a third open doesn't hit the network again.
        repo.groups("en")
        assertEquals(2, calls.get())
    }
}
