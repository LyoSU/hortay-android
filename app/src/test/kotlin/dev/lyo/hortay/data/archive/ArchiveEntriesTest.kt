package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The archive browser's entry model: [ArchiveRepository.observeEntries] over the
 * `selectArchiveEntryRows` query plus [buildArchiveEntries]. Rows are inserted straight
 * into the table so the tests pin the read path, independent of how captures write.
 */
class ArchiveEntriesTest {

    private val chat = ChatRef.tdlib(42)

    private fun newDb(): Pair<ArchiveRepository, ArchiveDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        return ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)), clock = { 0L }) to db
    }

    private fun ArchiveDatabase.version(
        key: String,
        seenAt: Long,
        hash: String,
        text: String = "",
        album: String? = null,
        thumb: ByteArray? = null,
        chatRef: ChatRef = chat,
        isComment: Boolean = false,
    ) = postSnapshotQueries.insert(
        source_kind = chatRef.kind.name, source_key = chatRef.key, message_key = key,
        album_key = album, kind = "VERSION", seen_at_ms = seenAt, edited_at_ms = null,
        content_kind = "tdlib", content_blob = byteArrayOf(1), content_hash = hash,
        text_preview = text, media_minithumb = thumb, deleted_msg_keys = null,
        is_comment = if (isComment) 1L else 0L, captured_at_ms = seenAt,
    )

    private fun ArchiveDatabase.deleted(key: String, seenAt: Long, keys: List<String> = listOf(key), album: String? = null) =
        postSnapshotQueries.insert(
            source_kind = chat.kind.name, source_key = chat.key, message_key = key,
            album_key = album, kind = "DELETED", seen_at_ms = seenAt, edited_at_ms = null,
            content_kind = "marker", content_blob = byteArrayOf(0), content_hash = "del-$key-$seenAt",
            text_preview = "", media_minithumb = null,
            deleted_msg_keys = keys.joinToString(",", "[", "]") { "\"$it\"" },
            is_comment = 0L, captured_at_ms = seenAt,
        )

    @Test
    fun baselineOnlyMessage_isNotListed() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "a", "published once")
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test
    fun repeatedIdenticalCaptures_doNotMakeAnEdit() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "a", "same")
        db.version("1", 200, "a", "same")
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test
    fun editedMessage_isOneEntryWithItsVersionCount() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "a", "first")
        db.version("1", 200, "b", "second")
        db.version("1", 250, "b", "second")
        db.version("1", 300, "a", "first again")

        val entries = repo.observeEntries().first()
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals(ArchiveEntryStatus.Edited, e.status)
        // a → b → (b folded) → a: three versions, like the revision sheet's collapse.
        assertEquals(3, e.versionCount)
        assertEquals(300L, e.eventAtMs)
        assertEquals("first again", e.textPreview)
        assertEquals(4, e.snapshotIds.size)
    }

    @Test
    fun deletedAlbum_isOneEntryWithPreviewFromItsMembers() = runTest {
        val (repo, db) = newDb()
        val thumb = byteArrayOf(9, 9)
        db.version("1", 100, "m1", "", album = "alb")
        db.version("2", 100, "m2", "album caption", album = "alb")
        db.version("3", 100, "m3", "", album = "alb", thumb = thumb)
        db.deleted("1", 500, keys = listOf("1", "2", "3"), album = "alb")

        val entries = repo.observeEntries().first()
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals(ArchiveEntryStatus.Deleted, e.status)
        assertEquals("1", e.messageKey)
        assertEquals(500L, e.eventAtMs)
        assertEquals("album caption", e.textPreview)
        assertArrayEquals(thumb, e.mediaMinithumb)
        // Three member baselines plus the DELETED marker: removing the entry purges all.
        assertEquals(4, e.snapshotIds.size)
    }

    @Test
    fun deletionOfANeverArchivedMessage_isNotListedNorCounted() = runTest {
        val (repo, db) = newDb()
        db.deleted("7", 500)
        db.version("8", 100, "x", "kept")
        // Album deletion where only one member was archived: listed, from that member.
        db.deleted("8", 600, keys = listOf("8", "9"))

        val entries = repo.observeEntries().first()
        assertEquals(listOf("8"), entries.map { it.messageKey })
        assertEquals("kept", entries.single().textPreview)
    }

    @Test
    fun editedMemberOfDeletedAlbum_foldsIntoTheDeletion() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "m1", "", album = "alb")
        db.version("2", 100, "m2", "old caption", album = "alb")
        db.version("2", 200, "m2b", "new caption", album = "alb")
        db.deleted("1", 500, keys = listOf("1", "2"), album = "alb")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Deleted, e.status)
        assertEquals("new caption", e.textPreview)
    }

    @Test
    fun entriesCarryChannelMetadataAndComeNewestFirst() = runTest {
        val (repo, db) = newDb()
        db.archivedChannelQueries.upsertInsert("TDLIB", "42", "Channel", null, byteArrayOf(5), 0L, 0L)
        db.version("1", 100, "a", "one")
        db.version("1", 200, "b", "two")
        db.version("2", 150, "c", "gone")
        db.deleted("2", 900)

        val entries = repo.observeEntries().first()
        assertEquals(listOf("2", "1"), entries.map { it.messageKey })
        assertEquals("Channel", entries.first().channelTitle)
        assertArrayEquals(byteArrayOf(5), entries.first().channelPhotoMinithumb)
    }

    @Test
    fun guestModeVersion_countsTheLiveVersionToo() = runTest {
        val (repo, db) = newDb()
        db.version("10", 100, "w", "replaced text", chatRef = ChatRef.web("chan"))

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Edited, e.status)
        assertEquals(2, e.versionCount)
    }

    @Test
    fun storageBytes_includeCopiedMediaFiles() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "a", "x")
        db.archivedMediaFileQueries.insert("sha", "/p", 1_000L, null, 0L, 1L)

        // One-byte blob + the 1 000-byte file.
        assertEquals(1_001L, repo.observeStorageBytes().first())
    }

    @Test
    fun buildEntries_messageNamedByTwoDeletionsJoinsTheLaterOne() {
        fun row(id: Long, key: String, kind: SnapshotKind, at: Long, keys: List<String> = emptyList()) =
            ArchiveEntryRow(id, chat, key, null, kind, at, "h$id", "", keys, isComment = false, hasThumb = false)
        val drafts = buildArchiveEntries(
            listOf(
                row(1, "1", SnapshotKind.VERSION, 10),
                row(2, "2", SnapshotKind.VERSION, 10),
                row(3, "1", SnapshotKind.DELETED, 100, listOf("1", "2")),
                row(4, "2", SnapshotKind.DELETED, 200, listOf("2")),
            ),
            emptyMap(),
        )
        assertEquals(listOf("2", "1"), drafts.map { it.entry.messageKey })
        assertEquals(listOf(2L, 4L), drafts[0].entry.snapshotIds)
        assertEquals(listOf(1L, 3L), drafts[1].entry.snapshotIds)
    }
}
