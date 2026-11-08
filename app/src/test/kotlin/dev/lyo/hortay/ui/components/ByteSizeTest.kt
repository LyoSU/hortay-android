package dev.lyo.hortay.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class ByteSizeTest {

    private val units = arrayOf("B", "KB", "MB", "GB")

    private fun fmt(bytes: Long): String {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.US)
        return try { formatByteSize(bytes, units) } finally { Locale.setDefault(prev) }
    }

    @Test
    fun `bytes, whole and fractional values, and the top unit`() {
        assertEquals("0 B", fmt(0))
        assertEquals("0 B", fmt(-5))
        assertEquals("512 B", fmt(512))
        assertEquals("2 KB", fmt(2048))
        assertEquals("1.5 KB", fmt(1536))
        assertEquals("150 MB", fmt(150L * 1024 * 1024 + 300_000))
        assertEquals("2048 GB", fmt(2048L * 1024 * 1024 * 1024))
    }

    @Test
    fun `humanizeBytes splits value and unit for same-unit progress labels`() {
        assertEquals("12" to "MB", humanizeBytes(12L * 1024 * 1024, units))
    }
}
