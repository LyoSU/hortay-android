package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.FormattedText

/** Forwards to [WebPostAdapter]'s internal walker so tests can inspect FormattedText output. */
internal object WebPostAdapterFormattingHook {
    fun htmlToFormatted(html: String): FormattedText = WebPostAdapter.htmlToFormattedForTest(html)
}
