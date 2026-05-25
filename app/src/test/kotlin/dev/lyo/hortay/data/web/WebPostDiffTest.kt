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
    ) = WebPost(
        id = "demo/1",
        seq = 1L,
        publishedAt = "2026-05-23T10:00:00Z",
        textHtml = text,
        media = mediaUrls.map { url ->
            WebMedia(
                kind = WebMedia.Kind.Photo,
                url = url,
                aspectRatio = null,
                durationSec = null,
                thumbnailUrl = null,
            )
        }.toPersistentList(),
        webPreview = null,
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
    fun mediaFilenameChange_detected() {
        val old = samplePost(mediaUrls = listOf("https://cdn.tg/p/abc.jpg?token=A"))
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/xyz.jpg?token=A"))
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }

    @Test
    fun emptyMediaToMedia_detected() {
        val old = samplePost(mediaUrls = emptyList())
        val new = samplePost(mediaUrls = listOf("https://cdn.tg/p/new.jpg"))
        assertEquals(WebPostDiff.Change.EDITED, diff.detectChange(old, new))
    }
}
