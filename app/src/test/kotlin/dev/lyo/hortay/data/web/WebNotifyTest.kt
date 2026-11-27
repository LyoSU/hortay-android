package dev.lyo.hortay.data.web

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Guest new-post notifications over a real in-memory web.db: the 7 → 8 migration, the
 * baseline bookkeeping ([WebNotifyStore]), what a notification is about
 * ([WebNotifySelection]), the background check's gates ([WebNotifyCheck]) and the
 * coordinator's open / read / foreground hooks ([WebNotifyCoordinator]).
 */
class WebNotifyTest {

    private val channel = "chan"

    private fun newRepo(): WebRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WebDatabase.Schema.create(driver)
        return WebRepository(WebDatabase(driver), FakeStrings, ioDispatcher = Dispatchers.Unconfined)
    }

    private fun post(seq: Long, text: String = "post $seq") = WebPost(
        id = "$channel/$seq",
        seq = seq,
        publishedAt = "2026-01-01T00:00:${(seq % 60).toString().padStart(2, '0')}+00:00",
        textHtml = text,
        media = persistentListOf(),
        webPreview = null,
        forwardedFrom = null,
        views = "1",
        reactions = persistentListOf(),
    )

    private fun page(posts: List<WebPost>) = WebChannelPage(
        channel = WebChannelInfo(
            username = channel, title = "Chan", description = "",
            avatarUrl = null, subscribers = null, isVerified = false,
        ),
        posts = posts.toPersistentList(),
        olderCursor = null,
        listedSeqs = posts.map { it.seq }.toPersistentList(),
    )

    private suspend fun seed(repo: WebRepository, seqs: LongRange) {
        repo.subscribe(channel)
        ingest(repo, seqs.map { post(it) })
    }

    private suspend fun ingest(repo: WebRepository, posts: List<WebPost>) {
        repo.ingestPage(page(posts), etag = null, lastModified = null, fetchedAtMs = 1_000L)
    }

    private suspend fun baseline(repo: WebRepository): Long? = repo.notify.notifiedSeqIfOn(channel)

    // ---- Migration ---------------------------------------------------------------------

    @Test
    fun `7 to 8 adds the bell off and no baseline, keeping existing rows`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "web.db")
        File("src/main/sqldelight/databases/7.db").copyTo(file)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(null, "INSERT INTO channel(username, title, is_subscribed) VALUES ('old', 'Old', 1)", 0)

        WebDatabase.Schema.migrate(driver, 7L, WebDatabase.Schema.version)

        assertEquals(8L, WebDatabase.Schema.version)
        val row = WebDatabase(driver).channelQueries.selectByUsername("old").executeAsOne()
        assertFalse(row.notify)
        assertNull(row.notified_seq)
        assertTrue(row.is_subscribed)
        driver.close()
    }

    // ---- Baseline bookkeeping ----------------------------------------------------------

    @Test
    fun `turning the bell on takes the newest stored post as the baseline`() = runTest {
        val repo = newRepo()
        seed(repo, 10L..14L)

        repo.notify.setNotify(channel, on = true)

        assertEquals(14L, baseline(repo))
        assertEquals(listOf(channel), repo.notify.notifyChannels().map { it.username })
    }

    @Test
    fun `the bell on a channel with nothing stored has no baseline yet`() = runTest {
        val repo = newRepo()
        repo.subscribe(channel)

        repo.notify.setNotify(channel, on = true)

        assertNull(baseline(repo))
        assertEquals(1, repo.notify.notifyChannels().size)
    }

    @Test
    fun `unsubscribing turns the bell off and clearing the cache does not`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..3L)
        repo.notify.setNotify(channel, on = true)

        repo.clearAllCache()
        assertEquals(3L, baseline(repo))

        repo.unsubscribe(channel)
        assertTrue(repo.notify.notifyChannels().isEmpty())
        assertNull(baseline(repo))
    }

    @Test
    fun `the baseline only moves up`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..5L)
        repo.notify.setNotify(channel, on = true)

        repo.notify.advance(channel, 3L)
        assertEquals(5L, baseline(repo))
        repo.notify.advance(channel, 9L)
        assertEquals(9L, baseline(repo))
    }

    @Test
    fun `a foreground ingest counts as seen for bell-on channels only`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..5L)
        repo.notify.setNotify(channel, on = true)
        ingest(repo, (6L..8L).map { post(it) })

        repo.notify.advanceAllToLatest()

        assertEquals(8L, baseline(repo))
    }

    // ---- Selection ---------------------------------------------------------------------

    @Test
    fun `only posts above the baseline are selected, newest first, with their first line`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..3L)
        ingest(repo, listOf(post(4L, "first line<br/>second line"), post(5L)))

        val selection = WebNotifySelection.select(
            candidates = repo.channelPostsAfter(channel, 3L),
            notifiedSeq = 3L,
            channelHidden = false,
            mutedWords = emptySet(),
        )

        assertEquals(5L, selection.maxSeq)
        assertEquals(listOf(5L, 4L), selection.previews.map { it.seq })
        assertEquals("first line", selection.previews[1].text)
        assertEquals(ReplyMediaKind.None, selection.previews[1].kind)
    }

    @Test
    fun `muted words and hidden channels notify nothing but still move the baseline`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..1L)
        ingest(repo, listOf(post(2L, "nothing to see"), post(3L, "a spoiler here")))
        val candidates = repo.channelPostsAfter(channel, 1L)

        val muted = WebNotifySelection.select(candidates, 1L, channelHidden = false, mutedWords = setOf("spoiler"))
        assertEquals(listOf(2L), muted.previews.map { it.seq })
        assertEquals(3L, muted.maxSeq)

        val hidden = WebNotifySelection.select(candidates, 1L, channelHidden = true, mutedWords = emptySet())
        assertTrue(hidden.previews.isEmpty())
        assertEquals(3L, hidden.maxSeq)
    }

    @Test
    fun `merging adds counts and keeps the newest five lines`() {
        val first = WebNotifySelection.merge(previous = null, newLines = listOf("c", "b", "a"), newCount = 3, newMaxSeq = 3L)
        assertEquals(WebNotifySelection.Shown(3, listOf("c", "b", "a"), 3L), first)

        val second = WebNotifySelection.merge(first, newLines = listOf("f", "e", "d"), newCount = 3, newMaxSeq = 6L)
        assertEquals(6, second.count)
        assertEquals(listOf("f", "e", "d", "c", "b"), second.lines)
        assertEquals(WebNotifySelection.MAX_LINES, second.lines.size)
        assertEquals(6L, second.maxSeq)
    }

    // ---- Background check --------------------------------------------------------------

    private class FakeNotifications : WebPostNotifications {
        val shownBy = mutableMapOf<String, WebNotifySelection.Shown>()
        val cancelled = mutableListOf<String>()
        var cancelledAll = 0

        override fun shown(username: String) = shownBy[username]
        override suspend fun show(channel: WebNotifyStore.NotifyChannel, shown: WebNotifySelection.Shown) {
            shownBy[channel.username] = shown
        }
        override fun cancel(username: String) {
            cancelled += username
            shownBy.remove(username)
        }
        override fun cancelAll() {
            cancelledAll++
            shownBy.clear()
        }
        override fun label(kind: ReplyMediaKind) = "label:$kind"
    }

    private fun check(
        repo: WebRepository,
        notifications: WebPostNotifications,
        guest: Boolean = true,
        foreground: Boolean = false,
        gated: Boolean = false,
        fetches: MutableList<String> = mutableListOf(),
        fetch: suspend (String) -> WebFeedSource.BackgroundFetch = { WebFeedSource.BackgroundFetch.Fresh },
    ) = WebNotifyCheck(
        store = repo.notify,
        isGuestActive = { guest },
        isForeground = { foreground },
        isGated = { gated },
        fetchNewest = { username -> fetches += username; fetch(username) },
        postsAfter = { username, after -> repo.channelPostsAfter(username, after) },
        isHidden = { false },
        mutedWords = { emptySet() },
        notifications = notifications,
    )

    @Test
    fun `the check is disabled outside guest mode and with no bell on`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        val fetches = mutableListOf<String>()

        assertEquals(WebNotifyCheck.Outcome.Disabled, check(repo, FakeNotifications(), fetches = fetches).run())
        repo.notify.setNotify(channel, on = true)
        assertEquals(
            WebNotifyCheck.Outcome.Disabled,
            check(repo, FakeNotifications(), guest = false, fetches = fetches).run(),
        )
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun `the check fetches nothing in the foreground or behind the rate-limit gate`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        repo.notify.setNotify(channel, on = true)
        val fetches = mutableListOf<String>()

        assertEquals(
            WebNotifyCheck.Outcome.Foreground,
            check(repo, FakeNotifications(), foreground = true, fetches = fetches).run(),
        )
        assertEquals(
            WebNotifyCheck.Outcome.Gated,
            check(repo, FakeNotifications(), gated = true, fetches = fetches).run(),
        )
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun `a rate limit mid-run stops before notifying`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        repo.notify.setNotify(channel, on = true)
        val notifications = FakeNotifications()

        val outcome = check(repo, notifications, fetch = { WebFeedSource.BackgroundFetch.Gated }).run()

        assertEquals(WebNotifyCheck.Outcome.Gated, outcome)
        assertTrue(notifications.shownBy.isEmpty())
    }

    @Test
    fun `new posts are notified once and raise the baseline`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        repo.notify.setNotify(channel, on = true)
        val notifications = FakeNotifications()
        val newPosts = check(repo, notifications, fetch = {
            ingest(repo, (1L..4L).map { post(it) })
            WebFeedSource.BackgroundFetch.Fresh
        })

        assertEquals(WebNotifyCheck.Outcome.Done, newPosts.run())

        val shown = notifications.shownBy.getValue(channel)
        assertEquals(2, shown.count)
        assertEquals(listOf("post 4", "post 3"), shown.lines)
        assertEquals(4L, shown.maxSeq)
        assertEquals(4L, baseline(repo))

        // Nothing new on the next run: the notification stays as it is.
        check(repo, notifications, fetch = { WebFeedSource.BackgroundFetch.NoChange }).run()
        assertEquals(shown, notifications.shownBy.getValue(channel))
    }

    @Test
    fun `a channel without a baseline gets one instead of a notification`() = runTest {
        val repo = newRepo()
        repo.subscribe(channel)
        repo.notify.setNotify(channel, on = true)
        val notifications = FakeNotifications()

        check(repo, notifications, fetch = {
            ingest(repo, (1L..5L).map { post(it) })
            WebFeedSource.BackgroundFetch.Fresh
        }).run()

        assertTrue(notifications.shownBy.isEmpty())
        assertEquals(5L, baseline(repo))
    }

    // ---- Coordinator -------------------------------------------------------------------

    private class FakeScheduling : WebNotifyScheduling {
        val calls = mutableListOf<String>()
        override fun schedule(wifiOnly: Boolean) {
            calls += "schedule:$wifiOnly"
        }
        override fun cancel() {
            calls += "cancel"
        }
    }

    @Test
    fun `work is scheduled only in guest mode with a bell on, and leaving guest mode clears everything`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        val notifications = FakeNotifications()
        val scheduling = FakeScheduling()
        val guest = MutableStateFlow(true)
        val wifiOnly = MutableStateFlow(false)
        val coordinator = WebNotifyCoordinator(
            store = repo.notify,
            notifications = notifications,
            scheduling = scheduling,
            guestActive = guest,
            wifiOnly = wifiOnly,
            scope = backgroundScope,
        )
        coordinator.bind()
        testScheduler.runCurrent()
        assertEquals(listOf("cancel"), scheduling.calls)

        coordinator.setNotify(channel, on = true)
        testScheduler.runCurrent()
        wifiOnly.value = true
        testScheduler.runCurrent()
        assertEquals(listOf("cancel", "schedule:false", "schedule:true"), scheduling.calls)

        guest.value = false
        testScheduler.runCurrent()
        assertEquals("cancel", scheduling.calls.last())
        assertTrue(notifications.cancelledAll > 0)
    }

    @Test
    fun `opening the channel or reading past the notification clears it`() = runTest {
        val repo = newRepo()
        seed(repo, 1L..2L)
        repo.notify.setNotify(channel, on = true)
        val notifications = FakeNotifications()
        val coordinator = WebNotifyCoordinator(
            store = repo.notify,
            notifications = notifications,
            scheduling = FakeScheduling(),
            guestActive = MutableStateFlow(true),
            wifiOnly = MutableStateFlow(false),
            scope = backgroundScope,
        )
        val shown = WebNotifySelection.Shown(count = 2, lines = listOf("b", "a"), maxSeq = 4L)

        ingest(repo, (1L..4L).map { post(it) })
        notifications.shownBy[channel] = shown
        coordinator.onChannelRead(channel, 3L)
        assertEquals(shown, notifications.shownBy[channel])
        assertEquals(3L, baseline(repo))
        coordinator.onChannelRead(channel, 4L)
        assertNull(notifications.shownBy[channel])

        ingest(repo, (1L..6L).map { post(it) })
        notifications.shownBy[channel] = shown
        coordinator.onChannelOpened(channel)
        assertNull(notifications.shownBy[channel])
        assertEquals(6L, baseline(repo))
    }
}
