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
        var now = 0L
        val repo = ReactionEmojiIconRepository(td, scope, this.style, ioDispatcher = dispatcher, nowMs = { now })
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
    fun `animations come from the same single response and only as TGS`() = runTest {
        val f = Fixture(this)
        f.td.onAny("GetEmojiReaction") {
            emojiReaction(HEART, webpIcon(fileId = 1)).apply {
                centerAnimation = tgsSticker(fileId = 3)
                aroundAnimation = tgsSticker(fileId = 4)
                // Not played by the reaction glyph — must not be fetched or kept.
                appearAnimation = tgsSticker(fileId = 2)
                selectAnimation = tgsSticker(fileId = 5)
                effectAnimation = tgsSticker(fileId = 6)
                activateAnimation = tgsSticker(fileId = 7)
            }
        }

        repeat(3) { f.repo.request(HEART) }

        assertEquals(1, f.td.rpcCount("GetEmojiReaction"), "animations must not cost extra calls")
        assertEquals(1, f.td.rpcCounts.values.sum(), "no other TDLib request — animation files download lazily from the UI")
        val icon = f.repo.get(HEART)
        assertEquals(1, icon?.media?.fileId)
        assertEquals(3, icon?.centerAnimation?.fileId)
        assertEquals(4, icon?.aroundAnimation?.fileId)
        f.scope.cancel()
    }

    @Test
    fun `missing or non-TGS animations leave the static icon usable`() = runTest {
        val f = Fixture(this)
        f.td.onAny("GetEmojiReaction") {
            emojiReaction(FIRE, webpIcon(fileId = 8)).apply {
                appearAnimation = webpIcon(fileId = 9)
                centerAnimation = TdApi.Sticker(
                    10L, 0L, 100, 100, "", TdApi.StickerFormatWebm(), TdApi.StickerFullTypeRegular(), null, file(10),
                )
                aroundAnimation = null
            }
        }

        f.repo.request(FIRE)

        val icon = f.repo.get(FIRE)
        assertEquals(8, icon?.media?.fileId)
        assertNull(icon?.centerAnimation)
        assertNull(icon?.aroundAnimation)
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
    fun `a transient failure is retried after the cooldown, not memoised`() = runTest {
        val f = Fixture(this)
        var calls = 0
        f.td.onAny("GetEmojiReaction") { req ->
            calls++
            if (calls == 1) TdApi.Error(TdClient.SEND_TIMEOUT_CODE, "SEND_TIMEOUT")
            else emojiReaction((req as TdApi.GetEmojiReaction).emoji, webpIcon(fileId = 7))
        }

        f.repo.request(NERD)
        assertFalse(f.repo.isMissing(NERD), "a timeout is not a definite miss")
        f.repo.request(NERD)
        assertEquals(1, f.td.rpcCount("GetEmojiReaction"), "no retry inside the cooldown")

        f.now += 30_000L
        f.repo.request(NERD)
        assertEquals(2, f.td.rpcCount("GetEmojiReaction"))
        assertEquals(7, f.repo.get(NERD)?.media?.fileId)
        f.scope.cancel()
    }

    @Test
    fun `a rate-limited lookup is transient too`() = runTest {
        val f = Fixture(this)
        f.td.onAny("GetEmojiReaction") { TdApi.Error(429, "Too Many Requests: retry after 5") }

        f.repo.request(NERD)

        assertFalse(f.repo.isMissing(NERD))
        assertNull(f.repo.get(NERD))
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

        f.td.onAny("GetEmojiReaction") {
            webpReaction(FIRE, fileId = 11).apply {
                centerAnimation = tgsSticker(fileId = 12)
                aroundAnimation = tgsSticker(fileId = 13)
            }
        }
        f.repo.request(FIRE)
        f.td.onAny("GetEmojiReaction") { TdApi.Error(404, "Not Found") }
        f.repo.request(THUMBS_UP)
        assertEquals(11, f.repo.get(FIRE)?.media?.fileId)
        assertEquals(12, f.repo.get(FIRE)?.centerAnimation?.fileId)
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
        assertNull(f.repo.get(FIRE), "animation file ids are wiped with the icon that holds them")
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

    private fun tgsSticker(fileId: Int) = TdApi.Sticker(
        fileId.toLong(), 0L, 512, 512, "", TdApi.StickerFormatTgs(), TdApi.StickerFullTypeRegular(), null, file(fileId),
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
        const val NERD = "🤓"
    }
}
