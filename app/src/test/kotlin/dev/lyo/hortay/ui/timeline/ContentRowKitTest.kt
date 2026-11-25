package dev.lyo.hortay.ui.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import dev.lyo.hortay.data.InvoicePrice
import java.util.Locale

/** Pure helpers behind the content rows: file badge label / family and coordinate format. */
class ContentRowKitTest {

    @Test
    fun `badge label is the uppercase extension`() {
        assertEquals("PDF", fileExtensionLabel("Звіт за листопад 2026.pdf"))
        assertEquals("XLSX", fileExtensionLabel("budget_final_v3_really_final_approved.xlsx"))
        assertEquals("TXT", fileExtensionLabel("notes.TXT"))
    }

    @Test
    fun `badge label is null without a usable extension`() {
        assertNull(fileExtensionLabel("README"))
        assertNull(fileExtensionLabel(".gitignore"))
        assertNull(fileExtensionLabel("archive."))
        assertNull(fileExtensionLabel("report.final draft"))
        assertNull(fileExtensionLabel(""))
    }

    @Test
    fun `badge label is null for extensions longer than four characters`() {
        assertNull(fileExtensionLabel("photo.jpeg2000"))
        assertEquals("JPEG", fileExtensionLabel("photo.jpeg"))
    }

    @Test
    fun `family by extension wins over mime`() {
        assertEquals(FileFamily.Sheet, fileFamilyOf("a.xlsx", "application/octet-stream"))
        assertEquals(FileFamily.Archive, fileFamilyOf("a.zip", "application/pdf"))
        assertEquals(FileFamily.Audio, fileFamilyOf("a.flac", ""))
        assertEquals(FileFamily.Image, fileFamilyOf("a.png", ""))
        assertEquals(FileFamily.Code, fileFamilyOf("Main.kt", "text/plain"))
        assertEquals(FileFamily.Document, fileFamilyOf("a.pdf", "application/pdf"))
    }

    @Test
    fun `family falls back to mime when the extension is unknown`() {
        assertEquals(FileFamily.Audio, fileFamilyOf("track", "audio/mpeg"))
        assertEquals(FileFamily.Image, fileFamilyOf("clip", "video/mp4"))
        assertEquals(FileFamily.Sheet, fileFamilyOf("data", "application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(FileFamily.Archive, fileFamilyOf("bundle", "application/x-7z-compressed"))
        assertEquals(FileFamily.Document, fileFamilyOf("blob", ""))
    }

    @Test
    fun `coordinates use a dot decimal and degree signs in every locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("uk-UA"))
            assertEquals("49.23311°, 28.46819°", formatCoordinates(49.233113, 28.468192))
            assertEquals("-33.86882°, 151.20930°", formatCoordinates(-33.86882, 151.2093))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `stars price reads as a count with a star`() {
        assertEquals("250 ⭐", formatInvoicePrice(InvoicePrice("XTR", 250), Locale.US))
        assertEquals("1,500 ⭐", formatInvoicePrice(InvoicePrice("xtr", 1500), Locale.US))
    }

    @Test
    fun `fiat price uses the currency's fraction digits`() {
        assertEquals("$19.99", formatInvoicePrice(InvoicePrice("USD", 1999), Locale.US))
        assertEquals("¥1,999", formatInvoicePrice(InvoicePrice("JPY", 1999), Locale.JAPAN).replace('￥', '¥'))
    }

    @Test
    fun `unknown currency falls back to two decimals and the code`() {
        assertEquals("19.99 ZZZ", formatInvoicePrice(InvoicePrice("ZZZ", 1999), Locale.US))
    }

    @Test
    fun `map request divides the measured width by the density scale and keeps 2 to 1`() {
        val r = mapThumbnailRequest(50.45, 30.52, isLive = false, widthPx = 1000, density = 2.625f)
        assertEquals(2, r.scale)
        assertEquals(480, r.width)
        assertEquals(240, r.height)
        assertEquals(15, r.zoom)
        assertEquals(50.45, r.latitude)
        assertEquals(30.52, r.longitude)
    }

    @Test
    fun `map scale rounds the density up but never asks for scale 3`() {
        assertEquals(2, mapThumbnailRequest(0.0, 0.0, isLive = false, widthPx = 600, density = 1.5f).scale)
        assertEquals(1, mapThumbnailRequest(0.0, 0.0, isLive = false, widthPx = 600, density = 1f).scale)
        assertEquals(2, mapThumbnailRequest(0.0, 0.0, isLive = false, widthPx = 600, density = 3.5f).scale)
    }

    @Test
    fun `map request stays inside TDLib bounds and zooms out for live locations`() {
        val wide = mapThumbnailRequest(0.0, 0.0, isLive = true, widthPx = 5000, density = 1f)
        assertEquals(1, wide.scale)
        assertEquals(512, wide.width)
        assertEquals(256, wide.height)
        assertEquals(13, wide.zoom)
        val tiny = mapThumbnailRequest(0.0, 0.0, isLive = false, widthPx = 10, density = 4f)
        assertEquals(2, tiny.scale)
        assertEquals(16, tiny.width)
        assertEquals(16, tiny.height)
    }

    @Test
    fun `nearby widths share one map request`() {
        val a = mapThumbnailRequest(1.0, 2.0, isLive = false, widthPx = 1000, density = 3f)
        val b = mapThumbnailRequest(1.0, 2.0, isLive = false, widthPx = 1010, density = 3f)
        assertEquals(a, b)
    }
}
