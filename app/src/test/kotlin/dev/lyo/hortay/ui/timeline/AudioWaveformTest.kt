package dev.lyo.hortay.ui.timeline

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage for [decodeWaveform] — Telegram's 5-bit LSB-first `voiceNote.waveform` packing.
 * Values are hand-computed against the upstream reference unpacking loop.
 */
class AudioWaveformTest {

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, decodeWaveform(ByteArray(0)).size)
    }

    @Test
    fun `sample count is bytes times eight over five`() {
        // 5 bytes = 40 bits = 8 five-bit samples.
        assertEquals(8, decodeWaveform(ByteArray(5)).size)
    }

    @Test
    fun `unpacks single-byte-aligned samples`() {
        // 0x1F fills the first sample's five low bits; the rest are zero.
        assertArrayEquals(intArrayOf(31, 0, 0), decodeWaveform(byteArrayOf(0x1F, 0x00)))
    }

    @Test
    fun `unpacks a sample spanning two bytes`() {
        // Second sample starts at bit 5: three high bits of byte 0 (0b111) plus two low
        // bits of byte 1 (0b01 << 3) → 0b01111 = 15.
        assertArrayEquals(intArrayOf(0, 15, 0), decodeWaveform(byteArrayOf(0xE0.toByte(), 0x01)))
    }

    @Test
    fun `every decoded value stays in the five-bit range`() {
        val bytes = ByteArray(16) { (it * 37).toByte() }
        assertTrue(decodeWaveform(bytes).all { it in 0..31 })
    }
}
