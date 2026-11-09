package dev.lyo.hortay.data.web

/**
 * Detects whether one [WebPost] is a meaningful edit of another.
 *
 * Two known false positives we explicitly filter:
 * - **CDN URL rotation.** t.me/s/ re-signs media URLs hourly, and for photos,
 *   avatars and link-preview images the rotating signed token lives in the URL
 *   PATH, not only the query string — an earlier filename-based comparison
 *   (path tail minus query) still recorded every rotation as an edit. Media is
 *   therefore compared by shape only: count, kind, aspect ratio and duration.
 *   The link preview is compared without its [WebPreview.imageUrl] for the same
 *   reason. Trade-off: swapping one photo for a different photo of the same
 *   shape is not detected — accepted, a rotation-driven false "edit" on every
 *   sweep is far worse for the archive.
 * - **View-count / reaction churn.** These are live state, not edits — ignored.
 *
 * @return [Change.EDITED] if the textual or media content changed, null otherwise.
 */
class WebPostDiff {
    enum class Change { EDITED }

    fun detectChange(old: WebPost, new: WebPost): Change? {
        if (old.textHtml != new.textHtml) return Change.EDITED
        if (mediaShapeOf(old.media) != mediaShapeOf(new.media)) return Change.EDITED
        if (old.webPreview?.copy(imageUrl = null) != new.webPreview?.copy(imageUrl = null)) {
            return Change.EDITED
        }
        if (old.forwardedFrom != new.forwardedFrom) return Change.EDITED
        return null
    }

    private data class MediaShape(
        val kind: WebMedia.Kind,
        val aspectRatio: Float?,
        val durationSec: Int?,
    )

    private fun mediaShapeOf(media: List<WebMedia>): List<MediaShape> =
        media.map { MediaShape(it.kind, it.aspectRatio, it.durationSec) }
}
