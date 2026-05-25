package dev.lyo.hortay.data.archive

import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PendingEditBufferTest {

    @Test
    fun `stashed content is returned by commit within TTL`() {
        var now = 0L
        val buf = PendingEditBuffer(nowMs = { now })
        val content = TdApi.MessageText(TdApi.FormattedText("hello", emptyArray()), null, null)
        buf.stash(chatId = 1L, messageId = 100L, content = content)
        now += 100L
        assertSame(content, buf.commitOnEdited(1L, 100L))
    }

    @Test
    fun `expired entry returns null on commit`() {
        var now = 0L
        val buf = PendingEditBuffer(nowMs = { now })
        buf.stash(1L, 100L, TdApi.MessageText(TdApi.FormattedText("x", emptyArray()), null, null))
        now += PendingEditBuffer.TTL_MS + 1L
        assertNull(buf.commitOnEdited(1L, 100L))
    }

    @Test
    fun `commit removes the entry`() {
        val buf = PendingEditBuffer(nowMs = { 0L })
        buf.stash(1L, 100L, TdApi.MessageText(TdApi.FormattedText("x", emptyArray()), null, null))
        assertNotNull(buf.commitOnEdited(1L, 100L))
        assertNull(buf.commitOnEdited(1L, 100L), "second commit must not return same content")
    }

    @Test
    fun `stash overwrites previous content for same key`() {
        val buf = PendingEditBuffer(nowMs = { 0L })
        val first = TdApi.MessageText(TdApi.FormattedText("first", emptyArray()), null, null)
        val second = TdApi.MessageText(TdApi.FormattedText("second", emptyArray()), null, null)
        buf.stash(1L, 100L, first)
        buf.stash(1L, 100L, second)
        assertSame(second, buf.commitOnEdited(1L, 100L))
    }

    @Test
    fun `pruneExpired drops aged entries`() {
        var now = 0L
        val buf = PendingEditBuffer(nowMs = { now })
        buf.stash(1L, 1L, TdApi.MessageText(TdApi.FormattedText("a", emptyArray()), null, null))
        buf.stash(1L, 2L, TdApi.MessageText(TdApi.FormattedText("b", emptyArray()), null, null))
        now += PendingEditBuffer.TTL_MS + 1L
        buf.pruneExpired()
        assertEquals(0, buf.size())
    }

    @Test
    fun `clear empties everything`() {
        val buf = PendingEditBuffer()
        buf.stash(1L, 1L, TdApi.MessageText(TdApi.FormattedText("a", emptyArray()), null, null))
        buf.stash(1L, 2L, TdApi.MessageText(TdApi.FormattedText("b", emptyArray()), null, null))
        buf.clear()
        assertEquals(0, buf.size())
    }
}
