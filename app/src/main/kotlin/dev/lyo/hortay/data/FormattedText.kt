package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/**
 * UI-friendly text with span metadata, decoupled from `TdApi.FormattedText`.
 *
 * Telegram supports a wide entity set; we map the visually-meaningful subset and
 * collapse niche ones (cashtag, bank-card, phone-number) into [Span.Plain] — they still
 * appear as text but without click affordance for now.
 */
@Immutable
data class FormattedText(val text: String, val spans: List<Span>) {

    companion object {
        val Empty = FormattedText("", emptyList())
        fun plain(text: String) = FormattedText(text, emptyList())
    }

    @Immutable
    data class Span(val start: Int, val end: Int, val style: Style)

    @Immutable
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

        /**
         * Block quote. [expandable] mirrors TDLib's
         * `TextEntityTypeExpandableBlockQuote` — when true the quote is shown collapsed
         * (a few lines + an "expand" affordance) on full-reading surfaces; a plain
         * `TextEntityTypeBlockQuote` is `expandable = false` and always shown in full.
         */
        data class BlockQuote(val expandable: Boolean = false) : Style
    }
}
