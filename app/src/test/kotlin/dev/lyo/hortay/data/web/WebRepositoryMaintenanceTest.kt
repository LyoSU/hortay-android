package dev.lyo.hortay.data.web

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * web.db behaviour that only shows up against real SQL: the media-TTL sweep
 * selection, ingest vs. subscription state, and search-text case folding.
 */
class WebRepositoryMaintenanceTest {

    private fun newRepo(): Pair<WebRepository, WebDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        val db = WebDatabase(driver)
        return WebRepository(db, FakeStrings, ioDispatcher = Dispatchers.Unconfined) to db
    }

    private fun post(username: String, seq: Long, html: String = "post $seq") = WebPost(
        id = "$username/$seq",
        seq = seq,
        publishedAt = "2026-01-01T00:00:00+00:00",
        textHtml = html,
        media = persistentListOf(),
        webPreview = null,
        forwardedFrom = null,
        views = "1",
        reactions = persistentListOf(),
    )

    private fun page(username: String, vararg posts: WebPost) = WebChannelPage(
        channel = WebChannelInfo(
            username = username, title = username, description = "",
            avatarUrl = null, subscribers = null, isVerified = false,
        ),
        posts = persistentListOf(*posts),
        olderCursor = null,
    )

    @Test
    fun staleMedia_considersOnlyTheLatestPostPerChannel() = runTest {
        val (repo, _) = newRepo()
        repo.subscribe("fresh")
        repo.subscribe("stale")
        // "fresh": old history fetched long ago, newest page fetched just now.
        repo.ingestPage(page("fresh", post("fresh", 1)), null, null, fetchedAtMs = 1_000L)
        repo.ingestPage(page("fresh", post("fresh", 2)), null, null, fetchedAtMs = 100_000L)
        // "stale": its latest post is older than the TTL cutoff.
        repo.ingestPage(page("stale", post("stale", 1)), null, null, fetchedAtMs = 1_000L)

        assertEquals(listOf("stale"), repo.channelsWithStaleMedia(olderThanMs = 50_000L))
    }

    @Test
    fun ingest_doesNotResurrectAnUnsubscribedChannel() = runTest {
        val (repo, _) = newRepo()
        repo.subscribe("chan")
        // Unsubscribe lands while the fetch is in flight; its page is ingested after.
        repo.unsubscribe("chan")
        repo.ingestPage(page("chan", post("chan", 1)), null, null, fetchedAtMs = 1_000L)

        assertTrue(repo.subscribedUsernames().isEmpty())
    }

    @Test
    fun ingest_keepsAnActiveSubscription() = runTest {
        val (repo, _) = newRepo()
        repo.subscribe("chan")
        repo.ingestPage(page("chan", post("chan", 1)), null, null, fetchedAtMs = 1_000L)

        assertEquals(listOf("chan"), repo.subscribedUsernames())
    }

    @Test
    fun search_matchesNonAsciiTextCaseInsensitively() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("chan")
        repo.ingestPage(page("chan", post("chan", 1, html = "Привіт, Світе")), null, null, 1_000L)

        val hits = db.postQueries.searchPlain("%привіт%", 10).executeAsList()
        assertEquals(1, hits.size)
    }

    @Test
    fun vacuum_foldsLegacyMixedCaseSearchText() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("chan")
        repo.ingestPage(page("chan", post("chan", 1, html = "x")), null, null, 1_000L)
        // A row written before ingest lowercased text_plain.
        db.postQueries.updateSearchText("Привіт", "chan/1")
        assertTrue(db.postQueries.searchPlain("%привіт%", 10).executeAsList().isEmpty())

        repo.vacuum()

        assertEquals(1, db.postQueries.searchPlain("%привіт%", 10).executeAsList().size)
    }
}
