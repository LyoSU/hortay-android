package dev.lyo.telread.data

/**
 * UI-friendly text with span metadata, decoupled from `TdApi.FormattedText`.
 *
 * Telegram supports a wide entity set; we map the visually-meaningful subset and
 * collapse niche ones (cashtag, bank-card, phone-number) into [Span.Plain] — they still
 * appear as text but without click affordance for now.
 */
data class FormattedText(val text: String, val spans: List<Span>) {

    companion object {
        val Empty = FormattedText("", emptyList())
        fun plain(text: String) = FormattedText(text, emptyList())
    }

    data class Span(val start: Int, val end: Int, val style: Style)

    sealed interface Style {
        data object Bold : Style
        data object Italic : Style
        data object Underline : Style
        data object Strikethrough : Style
        data object Code : Style
        data class Pre(val language: String?) : Style
        data class TextUrl(val url: String) : Style
        data object Url : Style
        data object Mention : Style
        data class MentionName(val userId: Long) : Style
        data object Hashtag : Style
        data object BotCommand : Style
        data object Spoiler : Style
        data class CustomEmoji(val emojiId: Long) : Style
        data object BlockQuote : Style
    }
}
