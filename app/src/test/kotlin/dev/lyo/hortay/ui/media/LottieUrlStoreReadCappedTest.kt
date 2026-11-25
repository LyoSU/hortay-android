package dev.lyo.hortay.ui.media

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream

class LottieUrlStoreReadCappedTest {

    @Test
    fun `reads a body within the cap in full`() {
        val bytes = ByteArray(40_000) { it.toByte() }
        assertArrayEquals(bytes, LottieUrlStore.readCapped(bytes.inputStream(), maxBytes = 40_000))
    }

    @Test
    fun `stops at the cap instead of buffering an endless body`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(LottieUrlStore.readCapped(endless, maxBytes = 100_000))
        // Reading stopped within one buffer past the cap.
        assertTrue(served < 200_000) { "read $served bytes" }
    }
}
