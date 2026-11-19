package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionEmojiIconRepositoryTest {

    private class Fixture(test: TestScope, style: EmojiStyle = EmojiStyle.Telegram) {
        val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val td = FakeTdSender()
        val style = MutableStateFlow(style)
        val repo = ReactionEmojiIconRepository(td, scope, this.style, ioDispatcher = dispatcher)
    }

    @Test
    fun `concurrent and repeated requests for one emoji issue a single call`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.td.onAnySuspending("GetEmojiReaction") { req ->
            gate.await()
            emojiReaction((req as TdApi.GetEmojiReaction).emoji, webpIcon(fileId = 41))
        }

        repeat(5) { f.repo.request(HEART) }
        assertEquals(1, f.td.rpcCount("GetEmojiReaction"), "in-flight lookups must dedupe")

        gate.complete(Unit)
        advanceUntilIdle()
        repeat(5) { f.repo.request(HEART) }

        assertEquals(1, f.td.rpcCount("GetEmojiReaction"), "resolved lookups must hit the cache")
        assertEquals(41, f.repo.get(HEART)?.media?.fileId)
        f.scope.cancel()
    }

    @Test
    fun `distinct emoji resolve one call at a time`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        f.td.onAnySuspending("GetEmojiReaction") { req ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            gate.await()
            inFlight--
            emojiReaction((req as TdApi.GetEmojiReaction).emoji, webpIcon(fileId = 7))
        }

        listOf(HEART, FIRE, THUMBS_UP).forEach(f.repo::request)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, maxInFlight, "lookups must be serialised, not fanned out")
        assertEquals(3, f.td.rpcCount("GetEmojiReaction"))
        assertEquals(3, f.repo.icons.value.size)
        f.scope.cancel()
    }

    @Test
    fun `a failed lookup is cached and never retried in-session`() = runTest {
        val f = Fixture(this)
        f.td.onAny("GetEmojiReaction") { TdApi.Error(404, "Not Found") }

        f.repo.request(HEART)
        f.repo.request(HEART)

        assertEquals(1, f.td.rpcCount("GetEmojiReaction"))
        assertTrue(f.repo.isMissing(HEART))
        assertNull(f.repo.get(HEART))
        f.scope.cancel()
    }

    @Test
    fun `an icon without a static raster counts as missing`() = runTest {
        val f = Fixture(this)
        val animatedOnly = TdApi.Sticker(
            1L, 0L, 100, 100, HEART, TdApi.StickerFormatTgs(), TdApi.StickerFullTypeRegular(), null, file(9),
        )
        f.td.onAny("GetEmojiReaction") { emojiReaction(HEART, animatedOnly) }

        f.repo.request(HEART)
        f.repo.request(HEART)

        assertEquals(1, f.td.rpcCount("GetEmojiReaction"))
        assertTrue(f.repo.isMissing(HEART))
        f.scope.cancel()
    }

    @Test
    fun `system emoji style issues no request at all`() = runTest {
        val f = Fixture(this, style = EmojiStyle.System)
        f.td.onAny("GetEmojiReaction") { req ->
            emojiReaction((req as TdApi.GetEmojiReaction).emoji, webpIcon(fileId = 3))
        }

        listOf(HEART, FIRE, HEART).forEach(f.repo::request)
        assertEquals(0, f.td.rpcCount("GetEmojiReaction"))
        assertTrue(f.repo.icons.value.isEmpty())

        // Switching back to Telegram style takes effect on the next composed glyph.
        f.style.value = EmojiStyle.Telegram
        f.repo.request(HEART)
        assertEquals(1, f.td.rpcCount("GetEmojiReaction"))
        assertEquals(3, f.repo.get(HEART)?.media?.fileId)
        f.scope.cancel()
    }

    @Test
    fun `logout clears the cache and drops a lookup that was in flight`() = runTest {
        val f = Fixture(this)
        val session = AccountSession(f.scope)
        session.onEnd("reactionEmojiIcons") { f.repo.clear() }
        val loggedOut = MutableSharedFlow<Unit>()
        session.bind(loggedOut)

        f.td.onAny("GetEmojiReaction") { webpReaction(FIRE, fileId = 11) }
        f.repo.request(FIRE)
        f.td.onAny("GetEmojiReaction") { TdApi.Error(404, "Not Found") }
        f.repo.request(THUMBS_UP)
        assertEquals(11, f.repo.get(FIRE)?.media?.fileId)
        assertTrue(f.repo.isMissing(THUMBS_UP))

        // A lookup still waiting on TDLib when the account logs out.
        val gate = CompletableDeferred<Unit>()
        f.td.onAnySuspending("GetEmojiReaction") { req ->
            gate.await()
            emojiReaction((req as TdApi.GetEmojiReaction).emoji, webpIcon(fileId = 99))
        }
        f.repo.request(HEART)

        loggedOut.emit(Unit)
        assertTrue(f.repo.icons.value.isEmpty(), "account A's file ids must be wiped")
        assertFalse(f.repo.isMissing(THUMBS_UP), "failure memo is per session")

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(f.repo.get(HEART), "a result from before logout must not land after the wipe")

        // The next session resolves afresh.
        val before = f.td.rpcCount("GetEmojiReaction")
        f.repo.request(HEART)
        assertEquals(before + 1, f.td.rpcCount("GetEmojiReaction"))
        assertEquals(99, f.repo.get(HEART)?.media?.fileId)
        f.scope.cancel()
    }

    private fun file(id: Int) = TdApi.File().apply { this.id = id }

    private fun webpIcon(fileId: Int) = TdApi.Sticker(
        fileId.toLong(), 0L, 100, 100, "", TdApi.StickerFormatWebp(), TdApi.StickerFullTypeRegular(), null, file(fileId),
    )

    private fun webpReaction(emoji: String, fileId: Int) = emojiReaction(emoji, webpIcon(fileId))

    private fun emojiReaction(emoji: String, staticIcon: TdApi.Sticker) = TdApi.EmojiReaction().apply {
        this.emoji = emoji
        this.title = ""
        this.isActive = true
        this.staticIcon = staticIcon
    }

    private companion object {
        const val HEART = "❤"
        const val FIRE = "🔥"
        const val THUMBS_UP = "👍"
    }
}
