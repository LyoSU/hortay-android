package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.FormattedText

/**
 * Turns a channel's plain-text description (TDLib's `supergroupFullInfo.description` carries
 * no entities) into a [FormattedText] whose URLs, `@mentions` and `#hashtags` are spans, so the
 * shared renderer makes them tappable exactly like in a post: a URL or mention opens through
 * the app's link handler (t.me routes in-app), a hashtag goes through the hashtag hook.
 *
 * Only unambiguous URLs become links: an explicit `http(s)://`, a `www.` host, or a
 * `host.tld/path`. A bare `name.tld` without a path is left as text — in free-form prose it
 * is as often a file name or an abbreviation as a website. Trailing sentence punctuation is
 * trimmed off a URL. Mentions follow Telegram's username shape (a letter, then 4–32 letters,
 * digits or underscores in total); a mention or hashtag inside a URL is never split out.
 * Pure so it is unit-tested.
 */
internal fun linkifyDescription(text: String): FormattedText {
    if (text.isEmpty()) return FormattedText.plain(text)
    val spans = mutableListOf<FormattedText.Span>()
    val urlRanges = mutableListOf<IntRange>()
    URL_REGEX.findAll(text).forEach { match ->
        var end = match.range.last + 1
        while (end > match.range.first && text[end - 1] in URL_TRAILING_PUNCTUATION) end--
        // A closing parenthesis stays when the URL itself opened one (Wikipedia-style links).
        if (end < match.range.last + 1 && text[end] == ')' &&
            text.substring(match.range.first, end).count { it == '(' } >
            text.substring(match.range.first, end).count { it == ')' }
        ) {
            end++
        }
        if (end > match.range.first) {
            spans += FormattedText.Span(match.range.first, end, FormattedText.Style.Url)
            urlRanges += match.range.first until end
        }
    }
    fun insideUrl(range: IntRange) = urlRanges.any { it.first <= range.last && range.first <= it.last }
    MENTION_REGEX.findAll(text).forEach { match ->
        if (!insideUrl(match.range)) {
            spans += FormattedText.Span(match.range.first, match.range.last + 1, FormattedText.Style.Mention)
        }
    }
    HASHTAG_REGEX.findAll(text).forEach { match ->
        if (!insideUrl(match.range)) {
            spans += FormattedText.Span(match.range.first, match.range.last + 1, FormattedText.Style.Hashtag)
        }
    }
    spans.sortBy { it.start }
    return FormattedText(text, spans)
}

private val URL_REGEX = Regex(
    """(?i)(?<![\w@.])(?:https?://[^\s<>"]+|www\.[^\s<>"]+|(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s<>"]*)""",
)
private val MENTION_REGEX = Regex("""(?<![\w@])@[A-Za-z][A-Za-z0-9_]{3,31}(?![\w@])""")
private val HASHTAG_REGEX = Regex("""(?<![\w#])#[\p{L}\p{N}_]*\p{L}[\p{L}\p{N}_]*""")
private const val URL_TRAILING_PUNCTUATION = ".,!?:;)]}'\"»…"
