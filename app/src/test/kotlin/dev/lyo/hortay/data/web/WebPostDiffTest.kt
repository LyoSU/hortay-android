package dev.lyo.hortay.data.web

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebPostDiffTest {
    private val diff = WebPostDiff()

    private fun samplePost(
        text: String = "hello",
        mediaUrls: List<String> = emptyList(),
        kind: WebMedia.Kind = WebMedia.Kind.Photo,
        preview: WebPreview? = null,
    ) = WebPost(
        id = "demo/1",
        seq = 1L,
        publishedAt = "2026-05-23T10:00:00Z",
        textHtml = text,
        media = mediaUrls.map { url ->
            WebMedia(
                kind = kind,
                url = url,
                aspectRatio = null,
                durationSec = null,
                thumbnailUrl = null,
            )
        }.toPersistentList(),
        webPreview = preview,
        forwardedFrom = null,
        views = null,
        reactions = persistentListOf(),
    )

    @Test
    fun urlRotation_isNotTreatedAsEdit() {
        val old = samplePost(mediaUrls = listOf("https://cdn.tg/p/abc.jpg?token=A"))
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/abc.jpg?token=B"))
        assertNull(diff.detectChange(old, new))
    }

    @Test
    fun textChange_detected() {
        val old = samplePost(text = "before")
        val new = samplePost(text = "after")
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }

    @Test
    fun pathTokenRotation_isNotTreatedAsEdit() {
        // Photo / avatar CDN URLs carry the signed token in the PATH, so even the
        // filename differs between two fetches of the same, unedited post.
        val old = samplePost(mediaUrls = listOf("https://cdn4.telesco.pe/file/AbC123tokenA.jpg"))
        val new = samplePost(mediaUrls = listOf("https://cdn4.telesco.pe/file/XyZ789tokenB.jpg"))
        assertNull(diff.detectChange(old, new))
    }

    @Test
    fun previewImageRotation_isNotTreatedAsEdit() {
        fun preview(image: String) = WebPreview(
            url = "https://example.com/article",
            siteName = "Example",
            title = "Title",
            description = "Description",
            imageUrl = image,
        )
        val old = samplePost(preview = preview("https://cdn4.telesco.pe/file/tokenA.jpg"))
        val new = samplePost(preview = preview("https://cdn4.telesco.pe/file/tokenB.jpg"))
        assertNull(diff.detectChange(old, new))
    }

    @Test
    fun previewTitleChange_detected() {
        val base = WebPreview("https://example.com/a", "Example", "Before", null, null)
        val old = samplePost(preview = base)
        val new = samplePost(preview = base.copy(title = "After"))
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }

    @Test
    fun mediaKindChange_detected() {
        val old = samplePost(mediaUrls = listOf("https://cdn.tg/p/abc.jpg"), kind = WebMedia.Kind.Photo)
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/abc.mp4"), kind = WebMedia.Kind.Video)
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }

    @Test
    fun mediaCountChange_detected() {
        val old = samplePost(mediaUrls = listOf("https://cdn.tg/p/a.jpg"))
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/a.jpg", "https://cdn.tg/p/b.jpg"))
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }

    @Test
    fun emptyMediaToMedia_detected() {
        val old = samplePost(mediaUrls = emptyList())
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/new.jpg"))
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }
}
