package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.HistoryRequestWindow
import dev.lyo.hortay.data.posts.FollowedRefresh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FollowedChannelsRefresher] on virtual time: every channel due once at start, the
 * 5 → 60 min backoff, reset on new posts, the Unavailable rehydrate and slow re-check, the shared-window
 * ceiling, FLOOD_WAIT ending a sweep, pull-to-refresh, foreground gating and the resume edge,
 * and skipping a chat that is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowedChannelsRefresherTest {

    private val minute = 60_000L

    private class Rig(scope: TestScope) {
        val store = FollowedChannelsStore(FollowedChannelsStoreTest.FakeDisk(), scope.backgroundScope)
        val window = HistoryRequestWindow(nowMs = { scope.testScheduler.currentTime })
        val active = MutableStateFlow(true)
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val open = HashSet<Long>()
        val calls = mutableListOf<Pair<Long, Long>>()
        var answer: (Long) -> FollowedRefresh = { FollowedRefresh.NoChange }
        val rehydrates = mutableListOf<Pair<Long, Long>>()
        var rehydrateAnswer: (Long) -> Boolean = { false }
        val refresher = FollowedChannelsRefresher(
            store = store,
            refresh = { chatId, since ->
                calls += chatId to scope.testScheduler.currentTime
                window.record()
                answer(chatId).also { assertTrue(since >= 0) }
            },
            historyWindow = window,
            active = active,
            refreshRequests = requests,
            isChatOpen = { it in open },
            nowMs = { scope.testScheduler.currentTime },
            rehydrate = { chatId, username ->
                assertEquals("c$chatId", username)
                rehydrates += chatId to scope.testScheduler.currentTime
                rehydrateAnswer(chatId)
            },
        )

        fun callTimes(chatId: Long): List<Long> = calls.filter { it.first == chatId }.map { it.second }
    }

    private suspend fun FollowedChannelsStore.follow(chatId: Long, seen: Long = 0L) {
        add(
            FollowedChannel(chatId, "c$chatId", "C$chatId", 0L, readCursor = seen, lastSeenMessageId = seen, status = FollowedChannel.Status.Ok),
        )
    }

    @Test
    fun `quiet channel backs off 5 to 60 minutes`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(200 * minute)

        // Due at start; then each NoChange moves one step: +10, +20, +40, +60, +60 …
        assertEquals(listOf(0L, 10, 30, 70, 130, 190).map { it * minute }, rig.callTimes(1L))
    }

    @Test
    fun `new posts reset to the first step and are marked seen`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L, seen = 5L)
        var n = 0
        rig.answer = { if (n++ < 2) FollowedRefresh.NewPosts(100L + n) else FollowedRefresh.NoChange }
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(21 * minute)

        assertEquals(listOf(0L, 5, 10, 20).map { it * minute }, rig.callTimes(1L))
        assertEquals(102L, rig.store.channels.value.getValue(1L).lastSeenMessageId)
        assertEquals(5L, rig.store.channels.value.getValue(1L).readCursor, "a seeded cursor is left alone")
    }

    @Test
    fun `first fetch of a never-seeded channel enters as read`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        rig.answer = { FollowedRefresh.NewPosts(42L) }
        backgroundScope.launch { rig.refresher.run() }
        runCurrent()

        assertEquals(42L, rig.store.channels.value.getValue(1L).readCursor)
    }

    @Test
    fun `an unavailable channel is marked and then re-checked only once a day`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        rig.answer = { FollowedRefresh.Unavailable }
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(FOLLOWED_UNAVAILABLE_RECHECK_MS - 1)
        assertEquals(1, rig.callTimes(1L).size)
        assertEquals(FollowedChannel.Status.Unavailable, rig.store.channels.value.getValue(1L).status)
        assertEquals(1, rig.rehydrates.size, "one rehydrate before giving up")

        rig.answer = { FollowedRefresh.NoChange }
        advanceTimeBy(2)
        assertEquals(2, rig.callTimes(1L).size)
        assertEquals(FollowedChannel.Status.Ok, rig.store.channels.value.getValue(1L).status)
        val recheckedAt = rig.callTimes(1L).last()
        advanceTimeBy(6 * minute)
        assertEquals(recheckedAt + 5 * minute, rig.callTimes(1L).last(), "back on the first backoff step")
    }

    @Test
    fun `a rehydrate to the same chat retries the fetch once and recovers`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        var n = 0
        rig.answer = { if (n++ == 0) FollowedRefresh.Unavailable else FollowedRefresh.NewPosts(50L) }
        rig.rehydrateAnswer = { true }
        backgroundScope.launch { rig.refresher.run() }
        runCurrent()

        assertEquals(listOf(0L, 0L), rig.callTimes(1L), "the failed fetch and its single retry")
        val entry = rig.store.channels.value.getValue(1L)
        assertEquals(FollowedChannel.Status.Ok, entry.status)
        assertEquals(50L, entry.lastSeenMessageId)
    }

    @Test
    fun `a rehydrate that fails or finds another chat leaves the channel unavailable`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        rig.answer = { FollowedRefresh.Unavailable }
        // The repository answers false both for a lookup failure and for a handle that now
        // resolves to a different chat id (covered in PostsRepositoryFollowedTest).
        rig.rehydrateAnswer = { false }
        backgroundScope.launch { rig.refresher.run() }
        runCurrent()

        assertEquals(1, rig.callTimes(1L).size, "no retry without a successful rehydrate")
        assertEquals(FollowedChannel.Status.Unavailable, rig.store.channels.value.getValue(1L).status)
    }

    @Test
    fun `pull to refresh re-checks an unavailable channel`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        rig.answer = { FollowedRefresh.Unavailable }
        backgroundScope.launch { rig.refresher.run() }
        advanceTimeBy(30 * minute)
        assertEquals(1, rig.callTimes(1L).size)

        rig.answer = { FollowedRefresh.NewPosts(9L) }
        rig.requests.emit(Unit)
        runCurrent()

        assertEquals(listOf(0L, 30 * minute), rig.callTimes(1L))
        assertEquals(FollowedChannel.Status.Ok, rig.store.channels.value.getValue(1L).status)
    }

    @Test
    fun `consecutive rehydrates are spaced at least three seconds apart`() = runTest {
        val rig = Rig(this)
        repeat(3) { rig.store.follow(it + 1L) }
        rig.answer = { FollowedRefresh.Unavailable }
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(minute)

        val times = rig.rehydrates.map { it.second }
        assertEquals(3, times.size)
        times.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= FOLLOWED_REHYDRATE_SPACING_MS, "$a → $b") }
    }

    @Test
    fun `checks wait until the shared window is below the ceiling`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        repeat(FOLLOWED_WINDOW_CEILING) { rig.window.record() }
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(HistoryRequestWindow.WINDOW_MS - 1)
        assertEquals(emptyList<Long>(), rig.callTimes(1L))
        advanceTimeBy(2)
        assertEquals(listOf(HistoryRequestWindow.WINDOW_MS), rig.callTimes(1L))
    }

    @Test
    fun `a full cold sweep never puts more than the ceiling into one window`() = runTest {
        val rig = Rig(this)
        repeat(30) { rig.store.follow(it + 1L, seen = 100L - it) }
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(2 * minute)

        assertEquals(30, rig.calls.size)
        val times = rig.calls.map { it.second }
        for (t in times) {
            val inWindow = times.count { it in t until t + HistoryRequestWindow.WINDOW_MS }
            assertTrue(inWindow <= FOLLOWED_WINDOW_CEILING, "$inWindow calls in the window starting at $t")
        }
        assertEquals(30L, rig.calls.first().first, "oldest last-seen first")
    }

    @Test
    fun `flood wait ends the sweep until the pause`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L, seen = 1L)
        rig.store.follow(2L, seen = 2L)
        var flooded = false
        rig.answer = { id -> if (id == 1L && !flooded) FollowedRefresh.FloodWait.also { flooded = true } else FollowedRefresh.NoChange }
        backgroundScope.launch { rig.refresher.run() }

        runCurrent()
        assertEquals(listOf(1L), rig.calls.map { it.first })

        advanceTimeBy(FOLLOWED_FLOOD_PAUSE_MS + 1)
        assertEquals(listOf(1L, 1L, 2L), rig.calls.map { it.first })
    }

    @Test
    fun `requestRefreshNow and a pull make every channel due at once`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        backgroundScope.launch { rig.refresher.run() }
        runCurrent()

        advanceTimeBy(minute)
        rig.refresher.requestRefreshNow()
        runCurrent()
        advanceTimeBy(minute)
        rig.requests.tryEmit(Unit)
        runCurrent()

        assertEquals(listOf(0L, minute, 2 * minute), rig.callTimes(1L))
    }

    @Test
    fun `nothing is polled in the background and a resume re-checks stale channels`() = runTest {
        val rig = Rig(this)
        rig.active.value = false
        rig.store.follow(1L)
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(30 * minute)
        assertEquals(emptyList<Long>(), rig.callTimes(1L), "inactive: no calls at all")

        rig.active.value = true
        runCurrent()
        assertEquals(listOf(30 * minute), rig.callTimes(1L))

        // Background before the next check (due at +10 min), back after 6 min: the last
        // check is older than one first step, so the resume makes it due right away.
        advanceTimeBy(minute)
        rig.active.value = false
        runCurrent()
        advanceTimeBy(5 * minute)
        rig.active.value = true
        runCurrent()
        assertEquals(listOf(30 * minute, 36 * minute), rig.callTimes(1L))

        // A quick background / foreground flip right after a check does not re-check.
        rig.active.value = false
        runCurrent()
        rig.active.value = true
        runCurrent()
        assertEquals(2, rig.callTimes(1L).size)
    }

    @Test
    fun `an open chat is skipped`() = runTest {
        val rig = Rig(this)
        rig.store.follow(1L)
        rig.open += 1L
        backgroundScope.launch { rig.refresher.run() }

        advanceTimeBy(30 * minute)
        assertEquals(emptyList<Long>(), rig.callTimes(1L))

        rig.open.clear()
        advanceTimeBy(6 * minute)
        assertEquals(1, rig.callTimes(1L).size)
    }

    @Test
    fun `a channel followed later is first checked one step after its seed`() = runTest {
        val rig = Rig(this)
        backgroundScope.launch { rig.refresher.run() }
        runCurrent()

        advanceTimeBy(minute)
        rig.store.follow(9L)
        runCurrent()
        assertEquals(emptyList<Long>(), rig.callTimes(9L))

        advanceTimeBy(5 * minute + 1)
        assertEquals(listOf(6 * minute), rig.callTimes(9L))
    }
}
