package dev.lyo.hortay.data.web

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `channel.channel_id`: written by the fetch upsert, never erased by a page without a
 * token, carried by the username-case merge, and added to an existing v5 database by
 * 5.sqm without touching its rows.
 */
class WebChannelIdTest {

    private fun newRepo(): Pair<WebRepository, WebDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val db = WebDatabase(driver)
        return WebRepository(db, FakeStrings, ioDispatcher = Dispatchers.Unconfined) to db
    }

    private fun page(username: String, channelId: Long?) = WebChannelPage(
        channel = WebChannelInfo(username, "Title", "", null, null, false),
        posts = persistentListOf(),
        olderCursor = null,
        channelId = channelId,
    )

    @Test
    fun `a page without a token keeps the known id`() = runTest {
        val (repo, _) = newRepo()
        repo.subscribe("news")
        assertNull(repo.channelIdFor("news"))
        repo.ingestPage(page("news", 42L), null, null, 1_000L)
        assertEquals(42L, repo.channelIdFor("news"))
        repo.ingestPage(page("news", null), null, null, 2_000L)
        assertEquals(42L, repo.channelIdFor("news"))
        // A page from a channel that got the handle since: the new id wins.
        repo.ingestPage(page("news", 43L), null, null, 3_000L)
        assertEquals(43L, repo.channelIdFor("news"))
    }

    @Test
    fun `the insert path stores the id of a never-seen channel`() = runTest {
        val (repo, _) = newRepo()
        repo.ingestPage(page("fresh", 7L), null, null, 1_000L)
        assertEquals(7L, repo.channelIdFor("fresh"))
        assertNull(repo.channelIdFor("unknown"))
    }

    @Test
    fun `the username-case merge carries the id`() = runTest {
        val (repo, db) = newRepo()
        db.channelQueries.subscribeInsert("Durov", "Durov")
        repo.ingestPage(page("Durov", 1006503122L), null, null, 1_000L)
        repo.normalizeUsernameCase()
        assertEquals(1006503122L, repo.channelIdFor("durov"))
    }

    @Test
    fun `5_sqm adds the column to a v5 database and keeps its rows`(@TempDir dir: File) {
        val snapshot = File("src/main/sqldelight/databases/5.db")
        val file = File(dir, "web.db")
        snapshot.copyTo(file)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(null, "INSERT INTO channel(username, title) VALUES ('old', 'Old')", 0)

        WebDatabase.Schema.migrate(driver, 5, WebDatabase.Schema.version)

        val db = WebDatabase(driver)
        val row = db.channelQueries.selectByUsername("old").executeAsOne()
        assertEquals("Old", row.title)
        assertNull(row.channel_id)
        db.channelQueries.upsertFromFetchUpdate(
            title = "Old", description = "", avatarUrl = null, subscribers = null,
            isVerified = false, lastFetchedAtMs = 1L, lastEtag = null, lastModified = null,
            olderCursor = null, channelId = 9L, username = "old",
        )
        assertEquals(9L, db.channelQueries.selectChannelId("old").executeAsOne().channel_id)
        driver.close()
    }
}
