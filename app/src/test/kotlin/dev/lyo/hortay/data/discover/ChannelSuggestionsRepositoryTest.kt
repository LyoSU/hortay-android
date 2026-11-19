package dev.lyo.hortay.data.discover

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
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
 * Catalog resolution: an offline first open must not pin
 * [ChannelSuggestionsRepository.FALLBACK] for the process, a disk cache is served at
 * once (refreshed in the background), and a failed fetch is not retried on every open
 * inside [ChannelSuggestionsRepository.FAILURE_BACKOFF_MS].
 */
class ChannelSuggestionsRepositoryTest {

    @TempDir
    lateinit var dir: File

    private val remoteJson = """
        {"version":1,"defaultLocale":"en",
         "categories":[{"id":"news","order":0,"title":{"en":"News"}}],
         "channels":[{"username":"remoteonly","category":"news","locales":["*"]}]}
    """.trimIndent()

    private val cachedJson = """
        {"version":1,"defaultLocale":"en",
         "categories":[{"id":"news","order":0,"title":{"en":"News"}}],
         "channels":[{"username":"cachedonly","category":"news","locales":["*"]}]}
    """.trimIndent()

    private fun ok(chain: Interceptor.Chain): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(remoteJson.toResponseBody("application/json".toMediaType()))
        .build()

    private fun TestScope.repo(
        online: (call: Int) -> Boolean,
        calls: AtomicInteger,
        clock: () -> Long,
    ): ChannelSuggestionsRepository {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (!online(calls.getAndIncrement())) throw IOException("offline")
                ok(chain)
            }
            .build()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ChannelSuggestionsRepository(
            http = http,
            cacheFile = File(dir, "suggestions.json"),
            appVersionCode = 1,
            catalogUrl = "https://example.invalid/suggestions.json",
            ioDispatcher = dispatcher,
            refreshScope = backgroundScope,
            nowMs = clock,
        )
    }

    private suspend fun ChannelSuggestionsRepository.usernames() =
        groups("en").flatMap { it.channels }.map { it.username }

    @Test
    fun `fallback after an offline open is not memoised`() = runTest {
        val calls = AtomicInteger(0)
        var now = 1_000_000L
        val repo = repo(online = { it > 0 }, calls = calls, clock = { now })

        assertEquals(listOf("LyBlog"), repo.usernames())

        // Inside the failure backoff the network is not retried at all.
        assertEquals(listOf("LyBlog"), repo.usernames())
        assertEquals(1, calls.get())

        // Past the backoff, the now-online network is fetched and memoised.
        now += ChannelSuggestionsRepository.FAILURE_BACKOFF_MS
        assertEquals(listOf("remoteonly"), repo.usernames())
        repo.usernames()
        assertEquals(2, calls.get())
    }

    @Test
    fun `disk cache is served immediately and refreshed in the background`() = runTest {
        File(dir, "suggestions.json").writeText(cachedJson)
        val calls = AtomicInteger(0)
        val repo = repo(online = { true }, calls = calls, clock = { 1_000_000L })

        assertEquals(listOf("cachedonly"), repo.usernames())
        runCurrent()
        assertEquals(1, calls.get())

        assertEquals(listOf("remoteonly"), repo.usernames())
        assertEquals(1, calls.get())
    }

    @Test
    fun `failed background refresh is not retried inside the backoff`() = runTest {
        File(dir, "suggestions.json").writeText(cachedJson)
        val calls = AtomicInteger(0)
        var now = 1_000_000L
        val repo = repo(online = { false }, calls = calls, clock = { now })

        assertEquals(listOf("cachedonly"), repo.usernames())
        runCurrent()
        assertEquals(listOf("cachedonly"), repo.usernames())
        runCurrent()
        assertEquals(1, calls.get())

        now += ChannelSuggestionsRepository.FAILURE_BACKOFF_MS
        assertEquals(listOf("cachedonly"), repo.usernames())
        runCurrent()
        assertEquals(2, calls.get())
    }
}
