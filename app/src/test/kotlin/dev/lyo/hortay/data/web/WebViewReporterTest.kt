package dev.lyo.hortay.data.web

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WebViewReporter] with a fake transport on virtual time: dedupe, token age, per-channel
 * batching, the 50-token and 10-second flush triggers, the background flush, the rate-limit
 * gate and the setting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewReporterTest {

    private val sent = mutableListOf<Pair<String, List<String>>>()
    private val enabled = MutableStateFlow(true)
    private var gated = false

    private fun TestScope.reporter() = WebViewReporter(
        scope = backgroundScope,
        transport = { channel, tokens -> sent += channel to tokens },
        enabled = enabled,
        isGated = { gated },
        nowMs = { testScheduler.currentTime },
    )

    private fun page(channel: String, seqs: IntRange) = WebChannelPage(
        channel = WebChannelInfo(channel, channel, "", null, null, false),
        posts = persistentListOf(),
        olderCursor = null,
        viewTokens = seqs.associate { "$channel/$it" to "tok$channel$it" }.toPersistentMap(),
    )

    private fun ids(channel: String, seqs: IntRange) = seqs.map { "$channel/$it" }

    @Test
    fun `reads wait for the interval and go out once per channel`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..3))
        r.recordPage(page("b", 1..2))
        r.onPostsRead(ids("a", 1..2) + ids("b", 1..1))
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS - 1)
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(
            setOf("a" to listOf("toka1", "toka2"), "b" to listOf("tokb1")),
            sent.toSet(),
        )
    }

    @Test
    fun `a post is reported once and a post without a token not at all`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..2))
        r.onPostsRead(ids("a", 1..1) + "a/99")
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        // A later page brings a fresh token for the same post: still not sent again.
        r.recordPage(page("a", 1..2))
        r.onPostsRead(ids("a", 1..1))
        advanceTimeBy(3 * WebViewReporter.FLUSH_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("a" to listOf("toka1")), sent)
    }

    @Test
    fun `fifty queued views go out without waiting`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..60))
        r.onPostsRead(ids("a", 1..WebViewReporter.MAX_BATCH))
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(WebViewReporter.MAX_BATCH, sent.single().second.size)
    }

    @Test
    fun `the next batch of a channel waits for the interval since its last send`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..60))
        r.onPostsRead(ids("a", 1..WebViewReporter.MAX_BATCH))
        runCurrent()
        r.onPostsRead(ids("a", 51..51))
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("toka51"), sent.last().second)
    }

    @Test
    fun `tokens older than the max age are dropped`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..2))
        advanceTimeBy(WebViewReporter.TOKEN_MAX_AGE_MS + 1)
        r.onPostsRead(ids("a", 1..2))
        advanceTimeBy(2 * WebViewReporter.FLUSH_INTERVAL_MS)
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a token that ages out while queued is not sent`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..1))
        advanceTimeBy(WebViewReporter.TOKEN_MAX_AGE_MS - 1)
        r.onPostsRead(ids("a", 1..1))
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `going to the background flushes everything at once`() = runTest {
        val foreground = MutableStateFlow(true)
        val r = reporter().also { it.bind(foreground) }
        r.recordPage(page("a", 1..1))
        r.onPostsRead(ids("a", 1..1))
        runCurrent()
        assertTrue(sent.isEmpty())
        foreground.value = false
        runCurrent()
        assertEquals(listOf("a" to listOf("toka1")), sent)
    }

    @Test
    fun `nothing goes out while the rate-limit gate holds`() = runTest {
        val r = reporter()
        r.recordPage(page("a", 1..1))
        r.onPostsRead(ids("a", 1..1))
        gated = true
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        assertTrue(sent.isEmpty())
        gated = false
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(listOf("a" to listOf("toka1")), sent)
    }

    @Test
    fun `with the setting off nothing is recorded or sent, and turning it off drops the queue`() = runTest {
        val r = reporter().also { it.bind(MutableStateFlow(true)) }
        r.recordPage(page("a", 1..2))
        r.onPostsRead(ids("a", 1..1))
        enabled.value = false
        runCurrent()
        r.onPostsRead(ids("a", 2..2))
        advanceTimeBy(2 * WebViewReporter.FLUSH_INTERVAL_MS)
        runCurrent()
        assertTrue(sent.isEmpty())
        // Back on: the earlier page's tokens are gone, a new page's are used.
        enabled.value = true
        r.onPostsRead(ids("a", 2..2))
        r.recordPage(page("a", 2..2))
        r.onPostsRead(ids("a", 2..2))
        advanceTimeBy(WebViewReporter.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(listOf("a" to listOf("toka2")), sent)
    }
}
