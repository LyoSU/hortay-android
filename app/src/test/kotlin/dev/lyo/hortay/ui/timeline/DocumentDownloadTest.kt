package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.MediaState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pure state / label / MIME logic behind the in-app document card. */
class DocumentDownloadTest {

    private val pending = DocumentFileState.Downloading(progress = null, downloadedBytes = 0L, totalBytes = 0L)

    @Test
    fun `untouched slot is idle and taps download`() {
        val state = documentFileState(MediaState.Idle, requested = false)
        assertEquals(DocumentFileState.Idle, state)
        assertEquals(DocumentTapAction.Download, state.tapAction())
    }

    @Test
    fun `a tap shows the ring before the slot answers`() {
        assertEquals(pending, documentFileState(MediaState.Idle, requested = true))
        assertEquals(pending, documentFileState(MediaState.Paused, requested = true))
        assertEquals(pending, documentFileState(MediaState.Failed("x"), requested = true))
        assertEquals(DocumentTapAction.Cancel, pending.tapAction())
    }

    @Test
    fun `active download is determinate once bytes land`() {
        val media = MediaState.Downloading(progress = 0.25f, downloadedBytes = 256, totalBytes = 1024, active = true)
        val state = documentFileState(media, requested = false)
        assertEquals(DocumentFileState.Downloading(0.25f, 256, 1024), state)
        assertEquals(DocumentTapAction.Cancel, state.tapAction())
    }

    @Test
    fun `active download without bytes is indeterminate`() {
        val media = MediaState.Downloading(progress = 0f, downloadedBytes = 0, totalBytes = 1024, active = true)
        val state = documentFileState(media, requested = false) as DocumentFileState.Downloading
        assertNull(state.progress)
    }

    @Test
    fun `dormant partial file is idle unless requested`() {
        val media = MediaState.Downloading(progress = 0.5f, downloadedBytes = 512, totalBytes = 1024, active = false)
        assertEquals(DocumentFileState.Idle, documentFileState(media, requested = false))
        assertEquals(DocumentFileState.Downloading(0.5f, 512, 1024), documentFileState(media, requested = true))
    }

    @Test
    fun `cancelled and failed slots offer download again`() {
        assertEquals(DocumentFileState.Idle, documentFileState(MediaState.Paused, requested = false))
        val failed = documentFileState(MediaState.Failed("stalled"), requested = false)
        assertEquals(DocumentFileState.Failed, failed)
        assertEquals(DocumentTapAction.Download, failed.tapAction())
    }

    @Test
    fun `file on disk opens`() {
        val state = documentFileState(MediaState.Ready("/data/doc.pdf"), requested = true)
        assertEquals(DocumentFileState.Ready("/data/doc.pdf"), state)
        assertEquals(DocumentTapAction.Open, state.tapAction())
    }

    @Test
    fun `meta line per state`() {
        assertEquals(DocumentMeta.Size(2048), documentMeta(DocumentFileState.Idle, 2048))
        assertEquals(DocumentMeta.None, documentMeta(DocumentFileState.Idle, 0))
        assertEquals(DocumentMeta.Size(2048), documentMeta(DocumentFileState.Ready("/p"), 2048))
        assertEquals(DocumentMeta.Failed(2048), documentMeta(DocumentFileState.Failed, 2048))
        assertEquals(
            DocumentMeta.Progress(256, 1024),
            documentMeta(DocumentFileState.Downloading(0.25f, 256, 1024), 999),
        )
    }

    @Test
    fun `progress falls back to the message size and clamps`() {
        assertEquals(DocumentMeta.Progress(0, 2048), documentMeta(pending, 2048))
        assertEquals(DocumentMeta.None, documentMeta(pending, 0))
        assertEquals(
            DocumentMeta.Progress(2048, 2048),
            documentMeta(DocumentFileState.Downloading(1f, 4096, 0), 2048),
        )
    }

    private val table = mapOf("pdf" to "application/pdf", "txt" to "text/plain", "weird" to "not a mime")
    private fun mime(name: String, declared: String) = documentMimeType(name, declared) { table[it] }

    @Test
    fun `declared mime wins and loses its parameters`() {
        assertEquals("application/pdf", mime("a.bin", "application/pdf"))
        assertEquals("text/plain", mime("a", "Text/Plain; charset=utf-8"))
    }

    @Test
    fun `generic or broken declared mime falls back to the extension`() {
        assertEquals("application/pdf", mime("Report.PDF", "application/octet-stream"))
        assertEquals("text/plain", mime("notes.txt", ""))
        assertEquals("application/pdf", mime("a.pdf", "garbage"))
    }

    @Test
    fun `unknown type is octet-stream`() {
        assertEquals("application/octet-stream", mime("archive.xyz", ""))
        assertEquals("application/octet-stream", mime("noext", "application/octet-stream"))
        assertEquals("application/octet-stream", mime("a.weird", ""))
    }
}
