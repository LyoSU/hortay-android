package dev.lyo.hortay.data.archive

import kotlinx.collections.immutable.persistentSetOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveDeletionGateTest {

    private val chat = ChatRef.tdlib(-100123L)
    private val other = ChatRef.tdlib(-100456L)

    @Test
    fun `deletions are kept only with the archive on and the Deletions switch on`() {
        assertTrue(ArchiveSettings(enabled = true).keepsDeletionsFor(chat))
        assertFalse(ArchiveSettings(enabled = false).keepsDeletionsFor(chat))
        assertFalse(ArchiveSettings(enabled = true, captureDeletes = false).keepsDeletionsFor(chat))
    }

    @Test
    fun `an excluded chat keeps no deletions while other chats still do`() {
        val s = ArchiveSettings(enabled = true, excludedChats = persistentSetOf(chat))
        assertFalse(s.keepsDeletionsFor(chat))
        assertTrue(s.keepsDeletionsFor(other))
    }

    @Test
    fun `the Edits switch does not decide deletions`() {
        assertTrue(ArchiveSettings(enabled = true, captureEdits = false).keepsDeletionsFor(chat))
    }
}
