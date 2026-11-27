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

/** [WebRepository.guestSearchCandidates] feeding [GuestChannelIndex] from real SQL. */
class GuestSearchCandidatesTest {

    private fun newRepo(): Pair<WebRepository, WebDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val db = WebDatabase(driver)
        return WebRepository(db, FakeStrings, ioDispatcher = Dispatchers.Unconfined) to db
    }

    private fun post(seq: Long, html: String, forward: WebForwardSource? = null) = WebPost(
        id = "alpha/$seq",
        seq = seq,
        publishedAt = "2026-01-01T00:00:${seq.toString().padStart(2, '0')}+00:00",
        textHtml = html,
        media = persistentListOf(),
        webPreview = null,
        forwardedFrom = forward,
        views = "1",
        reactions = persistentListOf(),
    )

    @Test
    fun channelsForwardsAndMentionsBecomeSearchable() = runTest {
        val (repo, db) = newRepo()
        repo.subscribe("alpha")
        repo.subscribe("former")
        db.channelQueries.unsubscribe("former")
        repo.ingestPage(
            WebChannelPage(
                channel = WebChannelInfo(
                    username = "alpha", title = "Alpha Daily", description = "",
                    avatarUrl = "https://cdn/a.jpg", subscribers = "1K", isVerified = false,
                ),
                posts = persistentListOf(
                    post(1, "via", WebForwardSource("Beta News", "https://t.me/beta_news/12")),
                    post(2, "again", WebForwardSource("Beta News", "https://t.me/beta_news/13")),
                    post(3, "read @gamma_chan and @gamma_chan"),
                    post(4, "private", WebForwardSource("Secret", "")),
                    post(5, "ask @BotFather or @premiumbot", WebForwardSource("Helper", "https://t.me/helper_bot/1")),
                ),
                olderCursor = null,
            ),
            null,
            null,
            1_000L,
        )

        val idx = GuestChannelIndex.build(repo.guestSearchCandidates())

        val alpha = idx["alpha"]!!
        assertTrue(alpha.isSubscribed)
        assertEquals("Alpha Daily", alpha.title)
        assertTrue(idx["former"]!!.hasSource(GuestChannelSource.Known))
        val beta = idx["beta_news"]!!
        assertEquals("Beta News", beta.title)
        assertEquals(2, beta.forwardCount)
        assertEquals(2, idx["gamma_chan"]!!.mentionCount)
        assertEquals(listOf("beta_news"), idx.search("beta").map { it.channel.username })
        assertEquals(null, idx["botfather"])
        assertEquals(null, idx["helper_bot"])
        assertEquals(4, idx.size)
    }

    @Test
    fun mentionedSuggestionsLeaveBotsOut() = runTest {
        val (repo, _) = newRepo()
        repo.subscribe("alpha")
        repo.ingestPage(
            WebChannelPage(
                channel = WebChannelInfo(
                    username = "alpha", title = "Alpha", description = "",
                    avatarUrl = null, subscribers = null, isVerified = false,
                ),
                posts = persistentListOf(
                    post(1, "@gamma_chan via @BotFather"),
                    post(2, "@gamma_chan and @premiumbot", WebForwardSource("Helper", "https://t.me/helper_bot/3")),
                    post(3, "news", WebForwardSource("Beta News", "https://t.me/beta_news/12")),
                ),
                olderCursor = null,
            ),
            null,
            null,
            1_000L,
        )

        assertEquals(
            listOf("gamma_chan" to 2, "beta_news" to 1),
            repo.mentionedUsernamesFromSubscribed(),
        )
    }
}
