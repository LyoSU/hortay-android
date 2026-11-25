package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InlineKeyboardMapperTest {

    @Test
    fun `maps rows, button kinds, styles and icons`() {
        val markup = TdApi.ReplyMarkupInlineKeyboard(
            arrayOf(
                arrayOf(
                    button("Read more", TdApi.InlineKeyboardButtonTypeUrl("https://example.com/a")),
                    button("👍", TdApi.InlineKeyboardButtonTypeCallback(byteArrayOf(1, 2)), TdApi.ButtonStylePrimary(), icon = 77L),
                ),
                arrayOf(
                    button("Copy", TdApi.InlineKeyboardButtonTypeCopyText("code-42"), TdApi.ButtonStyleSuccess()),
                    button("Author", TdApi.InlineKeyboardButtonTypeUser(555L), TdApi.ButtonStyleDanger()),
                ),
                arrayOf(
                    button("App", TdApi.InlineKeyboardButtonTypeWebApp("https://app.example")),
                    button("Pay", TdApi.InlineKeyboardButtonTypeBuy()),
                    button("Login", TdApi.InlineKeyboardButtonTypeLoginUrl("https://l.example", 1L, "")),
                ),
            ),
        )

        val keyboard = InlineKeyboardMapper.map(markup, messageId = 9L)!!

        assertEquals(9L, keyboard.messageId)
        assertEquals(listOf(2, 2, 3), keyboard.rows.map { it.size })
        val (url, callback) = keyboard.rows[0]
        assertEquals(InlineButtonAction.Url("https://example.com/a"), url.action)
        assertEquals(InlineButtonStyle.Default, url.style)
        assertNull(url.iconCustomEmojiId)
        assertEquals(InlineButtonAction.Callback(byteArrayOf(1, 2)), callback.action)
        assertEquals(InlineButtonStyle.Primary, callback.style)
        assertEquals(77L, callback.iconCustomEmojiId)
        val (copy, user) = keyboard.rows[1]
        assertEquals(InlineButtonAction.CopyText("code-42"), copy.action)
        assertEquals(InlineButtonStyle.Success, copy.style)
        assertEquals(InlineButtonAction.User(555L), user.action)
        assertEquals(InlineButtonStyle.Danger, user.style)
        assertTrue(keyboard.rows[2].all { it.action == InlineButtonAction.OpenInTelegram })
    }

    @Test
    fun `non-inline, absent and empty markup map to null`() {
        assertNull(InlineKeyboardMapper.map(null, 1L))
        assertNull(InlineKeyboardMapper.map(TdApi.ReplyMarkupRemoveKeyboard(false), 1L))
        assertNull(InlineKeyboardMapper.map(TdApi.ReplyMarkupInlineKeyboard(arrayOf(emptyArray<TdApi.InlineKeyboardButton>())), 1L))
    }

    @Test
    fun `a URL button without a URL falls back to opening the post in Telegram`() {
        val markup = TdApi.ReplyMarkupInlineKeyboard(arrayOf(arrayOf(button("x", TdApi.InlineKeyboardButtonTypeUrl("")))))
        assertEquals(InlineButtonAction.OpenInTelegram, InlineKeyboardMapper.map(markup, 1L)!!.rows[0][0].action)
    }

    @Test
    fun `re-mapping the same markup gives an equal keyboard`() {
        // Callback data is a ByteArray; without content equality every re-map of a post
        // would read as a change and re-emit the feed.
        fun markup(data: ByteArray) = TdApi.ReplyMarkupInlineKeyboard(
            arrayOf(arrayOf(button("b", TdApi.InlineKeyboardButtonTypeCallback(data)))),
        )
        assertEquals(InlineKeyboardMapper.map(markup(byteArrayOf(3)), 1L), InlineKeyboardMapper.map(markup(byteArrayOf(3)), 1L))
        assertNotEquals(InlineKeyboardMapper.map(markup(byteArrayOf(3)), 1L), InlineKeyboardMapper.map(markup(byteArrayOf(4)), 1L))
    }

    @Test
    fun `live location maps to a live Location, active until it expires`() {
        fun live(expiresIn: Int) = TdApi.MessageLiveLocation(
            TdApi.LiveLocation(TdApi.Location(50.45, 30.52, 0.0), 900, 0, 0),
            expiresIn,
        )
        val active = MessageContentMapper.map(live(600), EmptyStringResolver) as PostContent.Location
        assertEquals(50.45, active.latitude)
        assertEquals(30.52, active.longitude)
        assertTrue(active.isLive)
        assertTrue(active.liveUntilMs > System.currentTimeMillis())

        val stopped = MessageContentMapper.map(live(0), EmptyStringResolver) as PostContent.Location
        assertTrue(stopped.isLive)
        assertEquals(0L, stopped.liveUntilMs)

        val plain = MessageContentMapper.map(TdApi.MessageLocation(TdApi.Location(1.0, 2.0, 0.0)), EmptyStringResolver)
            as PostContent.Location
        assertFalse(plain.isLive)
    }

    @Test
    fun `audio keeps its caption`() {
        val track = TdApi.Audio().apply {
            title = "Track"
            performer = ""
            fileName = "t.mp3"
            audio = TdApi.File().apply { id = 3 }
        }
        val content = MessageContentMapper.map(
            TdApi.MessageAudio(track, TdApi.FormattedText("Два треки", emptyArray())),
            EmptyStringResolver,
        ) as PostContent.Audio
        assertEquals("Два треки", content.caption.text)
        assertEquals("Два треки", content.captionPlain)
    }

    private fun button(
        text: String,
        type: TdApi.InlineKeyboardButtonType,
        style: TdApi.ButtonStyle = TdApi.ButtonStyleDefault(),
        icon: Long = 0L,
    ) = TdApi.InlineKeyboardButton(text, icon, style, type)

    private object EmptyStringResolver : StringResolver {
        override fun getString(id: Int): String = ""
        override fun getString(id: Int, vararg args: Any?): String = ""
        override fun getQuantityString(id: Int, count: Int, vararg args: Any?): String = ""
    }
}
