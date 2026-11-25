package dev.lyo.hortay.data

import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Message.can_be_saved` → [TimelinePost.protectedContent] / [ChannelMediaItem.protectedContent]:
 * a chat that forbids saving must reach the UI as a protected post, read straight off the
 * message (no `GetMessageProperties` per post).
 */
class ProtectedContentMappingTest {

    private val channelId = -1001L

    private fun td() = FakeTdSender().apply {
        onAny("GetSupergroup") { _ -> TdApi.Supergroup().apply { usernames = null } }
        onAny("GetChat") { req ->
            TdApi.Chat().apply {
                id = (req as TdApi.GetChat).chatId
                title = "Discussion"
            }
        }
    }

    private val chat = TdApi.Chat().apply {
        id = channelId
        title = "Channel"
        type = TdApi.ChatTypeSupergroup(1L, true)
    }

    private fun message(canBeSaved: Boolean, sender: TdApi.MessageSender = TdApi.MessageSenderChat(channelId)) =
        TdApi.Message().apply {
            id = 1L shl 20
            chatId = channelId
            senderId = sender
            this.canBeSaved = canBeSaved
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("body", emptyArray()) }
        }

    @Test
    fun `channel post that cannot be saved maps as protected`() = runTest {
        val mapper = MessageMapper(td(), FakeStrings)
        assertTrue(mapper.toChannelPost(message(canBeSaved = false), chat).protectedContent)
        assertFalse(mapper.toChannelPost(message(canBeSaved = true), chat).protectedContent)
    }

    @Test
    fun `thread comment that cannot be saved maps as protected`() = runTest {
        val mapper = MessageMapper(td(), FakeStrings)
        val sender = TdApi.MessageSenderChat(-2002L)
        assertTrue(mapper.toThreadComment(message(canBeSaved = false, sender)).protectedContent)
        assertFalse(mapper.toThreadComment(message(canBeSaved = true, sender)).protectedContent)
    }

    @Test
    fun `channel media cell carries the protection flag`() {
        fun photo(canBeSaved: Boolean) = TdApi.Message().apply {
            id = 7L
            this.canBeSaved = canBeSaved
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo(
                    false,
                    null,
                    arrayOf(TdApi.PhotoSize("m", TdApi.File().apply { id = 1 }, 320, 240, IntArray(0))),
                )
            }
        }
        assertTrue(photo(canBeSaved = false).toChannelMediaItem()!!.protectedContent)
        assertFalse(photo(canBeSaved = true).toChannelMediaItem()!!.protectedContent)
    }
}
