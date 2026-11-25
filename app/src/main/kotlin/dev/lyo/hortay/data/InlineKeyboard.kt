package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.drinkless.tdlib.TdApi

/**
 * A bot's inline keyboard under a post (TDLib `replyMarkupInlineKeyboard`): rows of
 * buttons, drawn under the post content. [messageId] is the message that carries the
 * markup — for an album that need not be the card's anchor, and a callback query must
 * name the carrier.
 */
@Immutable
data class InlineKeyboard(
    val messageId: Long,
    val rows: ImmutableList<ImmutableList<InlineButton>>,
)

@Immutable
data class InlineButton(
    val text: String,
    val action: InlineButtonAction,
    val style: InlineButtonStyle = InlineButtonStyle.Default,
    /** Custom emoji drawn before the label (`inlineKeyboardButton.icon_custom_emoji_id`); null for none. */
    val iconCustomEmojiId: Long? = null,
)

/** Telegram's `ButtonStyle`; the UI maps each to a theme colour role, never a raw colour. */
enum class InlineButtonStyle { Default, Primary, Success, Danger }

@Immutable
sealed interface InlineButtonAction {

    /** Opens [url] through the app's link path (in-app for Telegram links, confirmed otherwise). */
    @Immutable
    data class Url(val url: String) : InlineButtonAction

    /** Sends a callback query with [data] to the bot; the answer may carry text, an alert or a URL. */
    @Immutable
    class Callback(val data: ByteArray) : InlineButtonAction {
        // Content equality: a re-mapped post must compare equal to the previous one, or every
        // TDLib update would re-emit the feed and recompose the card.
        override fun equals(other: Any?): Boolean = other is Callback && other.data.contentEquals(data)
        override fun hashCode(): Int = data.contentHashCode()
    }

    /** Copies [text] to the clipboard. */
    @Immutable
    data class CopyText(val text: String) : InlineButtonAction

    /** Opens the profile of [userId]. */
    @Immutable
    data class User(val userId: Long) : InlineButtonAction

    /**
     * A flow Hortay does not host — login URL, web app, inline switch, payment, callback with
     * password, game. The button is still drawn; a tap opens the post in Telegram.
     */
    @Immutable
    data object OpenInTelegram : InlineButtonAction
}

/** Pure mapping from TDLib reply markup to [InlineKeyboard]. */
internal object InlineKeyboardMapper {

    /**
     * The inline keyboard of [markup] carried by message [messageId], or null when the markup
     * is absent, is not an inline keyboard (reply / force-reply / remove keyboards belong to
     * private chats and have nothing to draw under a post), or has no buttons.
     */
    fun map(markup: TdApi.ReplyMarkup?, messageId: Long): InlineKeyboard? {
        val inline = markup as? TdApi.ReplyMarkupInlineKeyboard ?: return null
        val rows = inline.rows.orEmpty()
            .map { row -> row.orEmpty().filterNotNull().map(::mapButton).toImmutableList() }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        return InlineKeyboard(messageId, rows.toImmutableList())
    }

    private fun mapButton(button: TdApi.InlineKeyboardButton): InlineButton = InlineButton(
        text = button.text.orEmpty(),
        action = mapAction(button.type),
        style = mapStyle(button.style),
        iconCustomEmojiId = button.iconCustomEmojiId.takeIf { it != 0L },
    )

    private fun mapAction(type: TdApi.InlineKeyboardButtonType?): InlineButtonAction = when (type) {
        is TdApi.InlineKeyboardButtonTypeUrl ->
            type.url?.takeIf { it.isNotBlank() }?.let { InlineButtonAction.Url(it) } ?: InlineButtonAction.OpenInTelegram
        is TdApi.InlineKeyboardButtonTypeCallback -> InlineButtonAction.Callback(type.data ?: ByteArray(0))
        is TdApi.InlineKeyboardButtonTypeCopyText -> InlineButtonAction.CopyText(type.text.orEmpty())
        is TdApi.InlineKeyboardButtonTypeUser -> InlineButtonAction.User(type.userId)
        else -> InlineButtonAction.OpenInTelegram
    }

    private fun mapStyle(style: TdApi.ButtonStyle?): InlineButtonStyle = when (style) {
        is TdApi.ButtonStylePrimary -> InlineButtonStyle.Primary
        is TdApi.ButtonStyleSuccess -> InlineButtonStyle.Success
        is TdApi.ButtonStyleDanger -> InlineButtonStyle.Danger
        else -> InlineButtonStyle.Default
    }
}

/** A bot's answer to a callback query (TDLib `callbackQueryAnswer`). */
@Immutable
data class CallbackAnswer(
    val text: String,
    /** Show [text] as a dialog the reader dismisses, not a transient message. */
    val showAlert: Boolean,
    /** A link to open (games, `t.me/<bot>?start=…`); null for none. */
    val url: String?,
)
