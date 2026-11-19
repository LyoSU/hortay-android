package dev.lyo.hortay.data.web

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WebRepository.normalizeUsernameCase]: rows written before the parser lowercased
 * handles ("Durov" channel row, "Durov/123" posts) are folded into the lowercase
 * spelling the current parser writes, keeping read / bookmark state and the
 * subscription intent. Runs against real SQL with foreign keys ON, as in production.
 */
class WebUsernameNormalizationTest {

    private fun newRepo(): Pair<WebRepository, WebDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
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
            username = username, title = "Title of $username", description = "",
            avatarUrl = null, subscribers = null, isVerified = false,
        ),
        posts = persistentListOf(*posts),
        olderCursor = null,
    )

    /** What the old ingest left behind: a SUBSCRIBED mixed-case fetch row with posts. */
    private suspend fun legacyMixedRow(repo: WebRepository, db: WebDatabase, name: String, vararg seqs: Long) {
        db.channelQueries.subscribeInsert(name, name)
        repo.ingestPage(page(name, *seqs.map { post(name, it) }.toTypedArray()), null, null, 1_000L)
    }

    private fun state(db: WebDatabase, channel: String) =
        db.postQueries.selectUserStateByChannel(channel).executeAsList().associateBy { it.seq }

    @Test
    fun mixedCasePostsAreRekeyedAndFlagsMergedIntoTheLowercaseTwin() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("durov")
        legacyMixedRow(repo, db, "Durov", 1, 2)
        repo.setBookmark("Durov/1", true)
        repo.markRead("Durov/2")
        repo.setBookmark("Durov/2", true)
        // Post 2 was already re-fetched under the lowercase spelling (no user state).
        repo.ingestPage(page("durov", post("durov", 2, html = "edited")), null, null, 2_000L)

        assertEquals(1, repo.normalizeUsernameCase())

        assertTrue(db.channelQueries.selectMixedCaseUsernames().executeAsList().isEmpty())
        assertNull(db.channelQueries.selectByUsername("Durov").executeAsOneOrNull())
        assertTrue(state(db, "Durov").isEmpty())
        val lower = state(db, "durov")
        assertEquals(setOf(1L, 2L), lower.keys)
        assertTrue(lower.getValue(1L).is_bookmarked)
        assertTrue(lower.getValue(2L).is_read)
        assertTrue(lower.getValue(2L).is_bookmarked)
        // The twin's newer content wins over the stale mixed-case copy.
        assertEquals("edited", db.postQueries.selectFingerprint("durov", 2).executeAsOne().text_html)
        assertEquals(
            setOf("durov/1", "durov/2"),
            db.postQueries.selectBookmarkedIds().executeAsList().toSet(),
        )
        assertEquals(listOf("durov"), repo.subscribedUsernames())
    }

    @Test
    fun bookmarkMirrorTargetsTheLowercaseIdsAfterNormalisation() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("durov")
        legacyMixedRow(repo, db, "Durov", 7)
        repo.normalizeUsernameCase()

        repo.syncBookmarks(setOf("${WebPostAdapter.stableChatId("durov")}/7"))

        assertEquals(listOf("durov/7"), db.postQueries.selectBookmarkedIds().executeAsList())
    }

    @Test
    fun mixedOnlyChannelBecomesLowercaseWithItsSubscription() = runTest {
        val (repo, db) = newRepo()
        legacyMixedRow(repo, db, "SomeChan", 3)

        repo.normalizeUsernameCase()

        assertEquals(listOf("somechan"), repo.subscribedUsernames())
        assertEquals(setOf(3L), state(db, "somechan").keys)
    }

    @Test
    fun lowercaseSubscriptionIntentWinsOverTheMixedRowFlag() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("durov")
        repo.unsubscribe("durov")
        legacyMixedRow(repo, db, "Durov", 1)

        repo.normalizeUsernameCase()

        assertTrue(repo.subscribedUsernames().isEmpty())
        assertEquals(setOf(1L), state(db, "durov").keys)
    }

    @Test
    fun readCursorIsFoldedWithMax() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("durov")
        legacyMixedRow(repo, db, "Durov", 1)
        repo.markChannelRead("durov", 3)
        repo.markChannelRead("Durov", 5)

        repo.normalizeUsernameCase()

        assertEquals(5L, db.channelReadCursorQueries.selectByUsername("durov").executeAsOne())
        assertNull(db.channelReadCursorQueries.selectByUsername("Durov").executeAsOneOrNull())
    }

    @Test
    fun secondRunIsANoOp() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("durov")
        legacyMixedRow(repo, db, "Durov", 1)

        assertEquals(1, repo.normalizeUsernameCase())
        assertEquals(0, repo.normalizeUsernameCase())
        assertFalse(state(db, "durov").isEmpty())
    }
}
