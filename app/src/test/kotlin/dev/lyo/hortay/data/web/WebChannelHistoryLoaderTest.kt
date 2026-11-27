package dev.lyo.hortay.data.web

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WebChannelHistoryLoader] over a real in-memory web.db and a scripted `?before=` fetch:
 * the four outcomes, the persisted start of history, the bookmark-aware cursor and the
 * per-channel dedupe.
 */
class WebChannelHistoryLoaderTest {

    private val channel = "chan"

    private fun newRepo(): WebRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        return WebRepository(WebDatabase(driver), FakeStrings, ioDispatcher = Dispatchers.Unconfined)
    }

    private fun post(seq: Long) = WebPost(
        id = "$channel/$seq",
        seq = seq,
        publishedAt = "2026-01-01T00:00:${(seq % 60).toString().padStart(2, '0')}+00:00",
        textHtml = "post $seq",
        media = persistentListOf(),
        webPreview = null,
        forwardedFrom = null,
        views = "1",
        reactions = persistentListOf(),
    )

    private fun page(seqs: LongRange, listed: List<Long> = seqs.toList()) = WebChannelPage(
        channel = WebChannelInfo(
            username = channel, title = channel, description = "",
            avatarUrl = null, subscribers = null, isVerified = false,
        ),
        posts = seqs.map(::post).toPersistentList(),
        olderCursor = null,
        listedSeqs = listed.toPersistentList(),
    )

    /** Records every `before` asked for and answers from [answer]. */
    private class FakeFetch(var answer: suspend (Long) -> FetchResult) {
        val asked = mutableListOf<Long>()
        suspend fun fetch(@Suppress("UNUSED_PARAMETER") username: String, before: Long): FetchResult {
            asked += before
            return answer(before)
        }
    }

    private fun loader(
        repo: WebRepository,
        scope: CoroutineScope,
        fetch: FakeFetch,
        gated: () -> Boolean = { false },
        windowLimit: Long = WebRepository.DEFAULT_FEED_LIMIT,
    ): WebChannelHistoryLoader {
        val lock = Mutex()
        return WebChannelHistoryLoader(
            repository = repo,
            scope = scope,
            isGated = gated,
            lockFor = { lock },
            fetchBefore = fetch::fetch,
            nowMs = { 1_000L },
            windowLimit = windowLimit,
        )
    }

    private suspend fun seed(repo: WebRepository, seqs: LongRange) {
        repo.subscribe(channel)
        repo.ingestPage(page(seqs), etag = null, lastModified = null, fetchedAtMs = 1_000L)
    }

    @Test
    fun `older page below the oldest stored post is ingested`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val fetch = FakeFetch { FetchResult.Page(page(80L..99L), null, null) }

        val result = loader(repo, this, fetch).loadOlder(channel)

        assertEquals(OlderPageResult.Loaded(20), result)
        assertEquals(listOf(100L), fetch.asked)
        assertEquals(40L, repo.olderWalk.state(channel).storedPosts)
        assertEquals(80L, repo.olderWalk.state(channel).cursorSeq)
    }

    @Test
    fun `a page with nothing older marks the start and is not asked again`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..20L)
        // t.me answers a `before` past the first post with posts at or above it (or none).
        val fetch = FakeFetch { FetchResult.Page(page(1L..20L), null, null) }
        val history = loader(repo, this, fetch)

        assertEquals(OlderPageResult.End, history.loadOlder(channel))
        assertEquals(1L, repo.olderWalk.state(channel).historyStartSeq)
        assertEquals(OlderPageResult.End, history.loadOlder(channel))
        assertEquals(listOf(1L), fetch.asked)
    }

    @Test
    fun `an empty page marks the start too`() = runTest {
        val repo = newRepo()
        seed(repo, 5L..24L)
        val fetch = FakeFetch { FetchResult.Page(page(LongRange.EMPTY), null, null) }

        assertEquals(OlderPageResult.End, loader(repo, this, fetch).loadOlder(channel))
        assertEquals(5L, repo.olderWalk.state(channel).historyStartSeq)
    }

    @Test
    fun `the start mark yields once retention lifted the oldest post above it`() = runTest {
        val repo = newRepo()
        seed(repo, 50L..69L)
        repo.olderWalk.markHistoryStart(channel, 10L)
        val fetch = FakeFetch { FetchResult.Page(page(30L..49L), null, null) }

        assertEquals(OlderPageResult.Loaded(20), loader(repo, this, fetch).loadOlder(channel))
        assertEquals(listOf(50L), fetch.asked)
    }

    @Test
    fun `an active rate-limit gate is reported without a request`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val fetch = FakeFetch { error("must not fetch") }

        assertEquals(OlderPageResult.RateLimited, loader(repo, this, fetch, gated = { true }).loadOlder(channel))
        assertTrue(fetch.asked.isEmpty())
    }

    @Test
    fun `a 429 answer is RateLimited and a network error is Failed, neither marks the start`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val fetch = FakeFetch { FetchResult.RateLimited(60_000L) }
        val history = loader(repo, this, fetch)

        assertEquals(OlderPageResult.RateLimited, history.loadOlder(channel))
        fetch.answer = { FetchResult.NetworkError(java.io.IOException("offline")) }
        assertEquals(OlderPageResult.Failed, history.loadOlder(channel))
        assertEquals(null, repo.olderWalk.state(channel).historyStartSeq)
        assertEquals(20L, repo.olderWalk.state(channel).storedPosts)
    }

    @Test
    fun `concurrent calls for one channel share a single request`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val gate = CompletableDeferred<Unit>()
        val fetch = FakeFetch {
            gate.await()
            FetchResult.Page(page(80L..99L), null, null)
        }
        val history = loader(repo, this, fetch)

        val calls = List(3) { async { history.loadOlder(channel) } }
        runCurrent()
        gate.complete(Unit)
        val results = calls.awaitAll()

        assertEquals(listOf(100L), fetch.asked)
        assertTrue(results.all { it == OlderPageResult.Loaded(20) })
    }

    @Test
    fun `a lone old bookmark does not move the cursor below a retention hole`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        repo.ingestOlderPosts(channel, listOf(post(7L)), fetchedAtMs = 1_000L)
        repo.setBookmark("$channel/7", bookmarked = true)
        val fetch = FakeFetch { FetchResult.Page(page(80L..99L), null, null) }

        loader(repo, this, fetch).loadOlder(channel)

        assertEquals(listOf(100L), fetch.asked)
    }

    @Test
    fun `a page of rows that cannot be shown is stepped past`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val fetch = FakeFetch { before ->
            if (before == 100L) {
                // Listed rows 80..99, none of them parseable.
                FetchResult.Page(page(LongRange.EMPTY, listed = (80L..99L).toList()), null, null)
            } else {
                FetchResult.Page(page(60L..79L), null, null)
            }
        }

        assertEquals(OlderPageResult.Loaded(20), loader(repo, this, fetch).loadOlder(channel))
        assertEquals(listOf(100L, 80L), fetch.asked)
    }

    @Test
    fun `a full screen window answers End without a request`() = runTest {
        val repo = newRepo()
        seed(repo, 100L..119L)
        val fetch = FakeFetch { error("must not fetch") }

        assertEquals(OlderPageResult.End, loader(repo, this, fetch, windowLimit = 20L).loadOlder(channel))
        assertEquals(null, repo.olderWalk.state(channel).historyStartSeq)
    }
}
