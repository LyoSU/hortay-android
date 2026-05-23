package dev.lyo.hortay.data.web

/**
 * Detects whether one [WebPost] is a meaningful edit of another.
 *
 * Two known false positives we explicitly filter:
 * - **CDN URL rotation.** t.me/s/ refreshes media URL tokens hourly. We compare
 *   the path's last segment (filename) only, ignoring query string.
 * - **View-count / reaction churn.** These are live state, not edits — ignored.
 *
 * @return [Change.EDITED] if the textual or media content changed, null otherwise.
 */
class WebPostDiff {
    enum class Change { EDITED }

    fun detectChange(old: WebPost, new: WebPost): Change? {
        if (old.textHtml != new.textHtml) return Change.EDITED
        if (filenamesOf(old.media.map { it.url }) != filenamesOf(new.media.map { it.url })) {
            return Change.EDITED
        }
        if (old.webPreview != new.webPreview) return Change.EDITED
        if (old.forwardedFrom != new.forwardedFrom) return Change.EDITED
        return null
    }

    private fun filenamesOf(urls: List<String>): List<String> =
        urls.map { url -> url.substringBefore('?').substringAfterLast('/') }
}
