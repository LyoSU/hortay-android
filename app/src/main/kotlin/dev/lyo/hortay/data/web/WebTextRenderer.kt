package dev.lyo.hortay.data.web

import org.jsoup.Jsoup

/**
 * Plain-text projection of Telegram channel-preview post HTML (the inner HTML of
 * `.tgme_widget_message_text`).
 *
 * History: this object used to also house a Compose [androidx.compose.ui.text.AnnotatedString]
 * DOM walker (`render()` returning a `WebTextContent` with span runs + inline-emoji
 * placeholders). That path was abandoned when the rendering pipeline switched to
 * the shared TDLib [dev.lyo.hortay.data.FormattedText] model — `PostCard` now reads
 * `FormattedText` from `TimelinePost` via `WebPostAdapter`, and the AnnotatedString
 * walker had no remaining callers (only its own unit tests). Removed in favour of
 * the single TDLib-shaped path so guest mode and authenticated mode share one
 * stability graph and one [dev.lyo.hortay.ui.text.RichText] segmenter.
 *
 * What stays here: [toPlainText] is the only surviving entry point. It strips HTML
 * to plain text suitable for indexed-LIKE search and "first 280 chars" preview
 * snippets. Pure JVM (no Compose dependency) so [dev.lyo.hortay.data.web.WebRepository]
 * can call it before the post ever reaches a Composable.
 */
object WebTextRenderer {

    /**
     * Strip HTML tags to plain text suitable for indexed-LIKE search and "first 280
     * chars" preview snippets. Pure JVM (no Compose dependency) so it can run inside
     * [dev.lyo.hortay.data.web.WebRepository] before the post ever reaches a Composable.
     *
     * Implementation detail: Jsoup's `.text()` collapses runs of whitespace and
     * inserts a single space at block boundaries — exactly what we want for search
     * (matches don't depend on whether the source had `\n\n` or `<br><br>`).
     * `<tg-emoji>` is preserved as its inner unicode glyph (the `<b>` fallback)
     * so a search for "🔥" matches posts that used the custom-emoji wrapper too.
     */
    fun toPlainText(html: String): String {
        if (html.isBlank()) return ""
        return Jsoup.parseBodyFragment(html).body().text()
    }
}
