package dev.lyo.hortay.sim

import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.record.TdFieldFilter
import dev.lyo.hortay.data.record.TdJson
import dev.lyo.hortay.data.record.TdRedactor
import dev.lyo.hortay.data.record.TdSessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The record → anonymise → replay pipeline, end to end and without a device: a session of
 * the repository against the [SimTelegram] model is recorded through the same
 * [TdSessionLog] the debug build uses, then replayed into a fresh repository by
 * [ReplayTelegram]. A session pulled from a phone goes through the second half unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReplayTest {

    @Test
    fun `every field shape survives a json round trip`() {
        val chatId = -1_000_000_000_123L
        val message = TdApi.Message().apply {
            id = 5L shl 20
            this.chatId = chatId
            date = 1_700_000_000
            senderId = TdApi.MessageSenderChat(chatId)
            content = TdApi.MessageText(
                TdApi.FormattedText("hi 👋 there", arrayOf(TdApi.TextEntity(0, 2, TdApi.TextEntityTypeBold()))),
                null,
                null,
            )
            replyMarkup = TdApi.ReplyMarkupInlineKeyboard(
                arrayOf(arrayOf(TdApi.InlineKeyboardButton().apply { text = "a" })),
            )
        }
        val samples = listOf(
            message,
            TdApi.UpdateDeleteMessages(chatId, longArrayOf(1L shl 20, 2L shl 20), true, false),
            TdApi.Minithumbnail(4, 4, byteArrayOf(1, 2, 3)),
            TdApi.Location(50.45, 30.52, 1.5),
            TdApi.Chats(2, longArrayOf(1L, 2L)),
        )
        for (sample in samples) {
            val json = TdJson.encode(sample)
            assertEquals(json, TdJson.encode(TdJsonDecoder.decode(json)), sample.javaClass.simpleName)
        }
        val back = TdJsonDecoder.decode(TdJson.encode(message)) as TdApi.Message
        assertEquals("hi 👋 there", (back.content as TdApi.MessageText).text.text)
        assertEquals("a", (back.replyMarkup as TdApi.ReplyMarkupInlineKeyboard).rows[0][0].text)
    }

    @Test
    fun `redaction hides content and keeps what the app computes with`() {
        val redactor = TdRedactor(salt = 42L)
        val realChatId = -1_000_000_123_456L
        val text = "Привіт, світе! 𝐇𝐢 👋 2024\nhttps://t.me/kyiv"
        val chat = TdApi.Chat().apply {
            id = realChatId
            title = "Новини Києва"
            type = TdApi.ChatTypeSupergroup(123_456L, true)
        }
        val message = TdApi.Message().apply {
            id = 7L shl 20
            chatId = realChatId
            senderId = TdApi.MessageSenderChat(realChatId)
            content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
        }

        val chatJson = TdJson.encode(chat, redactor)
        val messageJson = TdJson.encode(message, redactor)
        val newChatId = chatJson.getValue("id").jsonPrimitive.long
        val newSupergroupId = chatJson.getValue("type").jsonObject.getValue("supergroupId").jsonPrimitive.long

        assertNotEquals(realChatId, newChatId)
        assertEquals(-1_000_000_000_000L - newSupergroupId, newChatId, "channel id relation")
        assertEquals(newChatId, messageJson.getValue("chatId").jsonPrimitive.long)
        assertEquals(newChatId, messageJson.getValue("senderId").jsonObject.getValue("chatId").jsonPrimitive.long)
        assertEquals(7L shl 20, messageJson.getValue("id").jsonPrimitive.long, "message ids are kept")

        val scrambled = (TdJsonDecoder.decode(messageJson) as TdApi.Message).let { (it.content as TdApi.MessageText).text.text }
        assertEquals(text.length, scrambled.length, "entity offsets must still line up")
        for (kept in listOf(", ", "! ", " 👋 ", "\n", "://", ".", "/")) assertTrue(kept in scrambled, "kept: $kept")
        for (leaked in listOf("Привіт", "світе", "𝐇𝐢", "2024", "https", "kyiv")) assertFalse(leaked in scrambled, "leaked: $leaked")
        assertFalse("Києва" in chatJson.toString())

        // Deterministic within a recording, so equal strings stay equal and distinct ones distinct.
        fun username(value: String) = TdJson.encode(TdApi.SearchPublicChat(value), redactor).getValue("username").jsonPrimitive.content
        assertEquals(username("durov"), username("durov"))
        assertNotEquals(username("durov"), username("pavel"))

        assertEquals("FLOOD_WAIT_42", TdJson.encode(TdApi.Error(420, "FLOOD_WAIT_42"), redactor).getValue("message").jsonPrimitive.content)
        val thumb = TdJson.encode(TdApi.Minithumbnail(4, 4, byteArrayOf(1, 2, 3)), redactor).getValue("data").jsonPrimitive.content
        assertEquals(Base64.getEncoder().encodeToString(ByteArray(3)), thumb)

        // A private chat's id is the user's id, before and after.
        val user = TdJson.encode(TdApi.User().apply { id = 777L }, redactor).getValue("id").jsonPrimitive.long
        assertEquals(user, TdJson.encode(TdApi.Chat().apply { id = 777L }, redactor).getValue("id").jsonPrimitive.long)
    }

    @Test
    fun `credentials and untyped options never reach the log`() {
        val out = StringBuilder()
        val log = TdSessionLog(out, TdFieldFilter.None, nowMs = { 0L })
        log.onRequest(1, TdApi.SetTdlibParameters())
        log.onResponse(1, TdApi.Ok())
        log.onRequest(2, TdApi.CheckAuthenticationCode("12345"))
        log.onResponse(2, TdApi.Ok())
        log.onUpdate(TdApi.UpdateOption("my_id", TdApi.OptionValueInteger(987_654_321L)))
        log.onRequest(3, TdApi.GetChat(5L))
        log.onResponse(3, TdApi.Error(400, "Chat not found"))

        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, out.toString())
        assertFalse("12345" in out)
        assertFalse("987654321" in out)
        assertTrue("GetChat" in lines[1] && "Chat not found" in lines[2])
    }

    @Test
    fun `a recorded session replays into the same feed`() {
        val recorded = record(TdFieldFilter.None)
        val replayed = replay(recorded.recording)

        assertTrue(recorded.shape.any { "x3" in it }, "the session must contain an album: ${recorded.shape}")
        assertEquals(emptyList<String>(), replayed.misses)
        assertEquals(recorded.shape, replayed.shape)
    }

    @Test
    fun `an anonymised session still replays into the same feed`() {
        val recorded = record(TdRedactor(salt = 7L))
        val replayed = replay(recorded.recording)

        assertFalse("seed " in recorded.recording || "Channel " in recorded.recording, "content leaked")
        assertEquals(emptyList<String>(), replayed.misses)
        assertEquals(recorded.shape, replayed.shape)
    }

    private class Outcome(val recording: String, val shape: List<String>, val misses: List<String>)

    private fun record(filter: TdFieldFilter): Outcome {
        lateinit var outcome: Outcome
        runTest {
            val text = StringBuilder()
            val log = TdSessionLog(text, filter, nowMs = { testScheduler.currentTime })
            val h = SimHarness(this, SimQuirks(latencyMs = 20, maxHistoryPage = 3), tap = log)
            val account = seededAccount("a", base = -1_000_000_005_000L, channels = 5, postsEach = 6)
            val (first, second) = account.channels.keys.toList()
            h.sim.signIn(account)
            script(h.repo, { h.posts }) { step ->
                if (step == 1) {
                    h.sim.post(first, albumSize = 3)
                    h.sim.post(second)
                } else {
                    h.sim.delete(second, listOf(account.channels.getValue(second).messages.firstKey()))
                }
            }
            outcome = Outcome(text.toString(), shape(h.posts), emptyList())
        }
        return outcome
    }

    private fun replay(recording: String): Outcome {
        lateinit var outcome: Outcome
        runTest {
            val td = ReplayTelegram(recording)
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val wired = WiredRepository(td, scope)
            val player = launch { td.play() }
            script(wired.repo, { wired.repo.posts.value }) {}
            player.join()
            outcome = Outcome(recording, shape(wired.repo.posts.value), td.misses.toList())
            scope.cancel()
        }
        return outcome
    }

    /** The reader's side of the session, on the same virtual timetable when recording and replaying. */
    private suspend fun TestScope.script(
        repo: PostsRepository,
        posts: () -> List<TimelinePost>,
        world: suspend (step: Int) -> Unit,
    ) {
        val sync = launch { repo.triggerInitialSync() }
        at(2_000)
        sync.join()
        world(1)
        at(4_000)
        val older = launch { posts().map { it.chatId }.distinct().forEach { repo.loadOlder(it, limit = 3) } }
        at(6_000)
        older.join()
        world(2)
        at(8_000)
    }

    private fun TestScope.at(ms: Long) {
        testScheduler.advanceTimeBy(ms - testScheduler.currentTime)
        testScheduler.runCurrent()
    }

    /** The feed without chat ids (anonymisation remaps them): per channel, its cards by message id. */
    private fun shape(posts: List<TimelinePost>): List<String> =
        posts.filter { !it.isDeleted && it.parentId == null }
            .groupBy { it.chatId }
            .values
            .map { cards -> cards.sortedBy { it.id }.joinToString(",") { "${it.id}x${it.albumMessageIds.size.coerceAtLeast(1)}" } }
            .sorted()
}
