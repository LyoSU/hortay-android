package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The archive browser's entry model: [ArchiveRepository.observeEntries] over the
 * `selectEntryMessages` query plus [buildArchiveEntries]. Rows are inserted straight into
 * the tables so the tests pin the read path, independent of how captures write.
 */
class ArchiveEntriesTest {

    private val chat = ChatRef.tdlib(42)

    private fun newDb(): Pair<ArchiveRepository, ArchiveDatabase> {
        val db = newArchiveDb()
        return ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)), clock = { 0L }) to db
    }

    @Suppress("LongParameterList")
    private fun ArchiveDatabase.version(
        key: String,
        sortAt: Long,
        text: String = "",
        album: String? = null,
        thumb: ByteArray? = null,
        chatRef: ChatRef = chat,
        isComment: Boolean = false,
        blob: ByteArray = byteArrayOf(1),
        mediaSha: String? = null,
    ) {
        archivedMessageQueries.insertIfAbsent(
            chatRef.kind.name, chatRef.key, key, album, if (isComment) 1L else 0L, null, sortAt, sortAt,
        )
        val id = messageOf(chatRef, key)!!.id
        archivedMessageQueries.touch(album, if (isComment) 1L else 0L, null, sortAt, id)
        messageVersionQueries.insert(
            id, sortAt, null, sortAt, VersionOrigin.LIVE_EDIT.name,
            if (chatRef.kind == SourceKind.WEB) CONTENT_KIND_WEB else CONTENT_KIND_TDLIB,
            blob, "h$key$sortAt", ContentNormalizer.HASH_VERSION, text, mediaSha, thumb,
        )
    }

    private fun ArchiveDatabase.deleted(at: Long, vararg keys: String, album: String? = null) {
        val ids = keys.mapNotNull { messageOf(chat, it)?.id }
        archivedMessageQueries.markDeleted(at, album, ids)
    }

    @Test
    fun baselineOnlyMessage_isNotListed() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "published once")
        assertTrue(repo.observeEntries().first().isEmpty())
    }

    @Test
    fun editedMessage_isOneEntryWithItsVersionCount() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "first")
        db.version("1", 200, "second")
        db.version("1", 300, "first again")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Edited, e.status)
        assertEquals(3, e.versionCount)
        assertEquals(300L, e.eventAtMs)
        assertEquals("first again", e.textPreview)
        assertEquals(listOf(db.messageOf(chat, "1")!!.id), e.messageIds)
    }

    @Test
    fun deletedAlbum_isOneEntryWithPreviewFromItsMembers() = runTest {
        val (repo, db) = newDb()
        val thumb = byteArrayOf(9, 9)
        db.version("1", 100, "", album = "alb")
        db.version("2", 100, "album caption", album = "alb")
        db.version("3", 100, "", album = "alb", thumb = thumb)
        db.deleted(500, "1", "2", "3", album = "alb")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Deleted, e.status)
        assertEquals("1", e.messageKey)
        assertEquals(500L, e.eventAtMs)
        assertEquals("album caption", e.textPreview)
        assertArrayEquals(thumb, e.mediaMinithumb)
        // Removing the entry purges every member.
        assertEquals(3, e.messageIds.size)
    }

    @Test
    fun editedMemberOfDeletedAlbum_foldsIntoTheDeletion() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "", album = "alb")
        db.version("2", 100, "old caption", album = "alb")
        db.version("2", 200, "new caption", album = "alb")
        db.deleted(500, "1", "2", album = "alb")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Deleted, e.status)
        assertEquals("new caption", e.textPreview)
    }

    /** A member deleted on its own still folds the rest of its album into the deletion. */
    @Test
    fun partlyDeletedAlbum_foldsTheSurvivingMembers() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "", album = "alb")
        db.version("2", 100, "caption", album = "alb")
        db.version("2", 200, "caption 2", album = "alb")
        db.deleted(500, "1")

        val e = repo.observeEntries().first().single()
        assertEquals("1", e.messageKey)
        assertEquals(2, e.messageIds.size)
    }

    /** The row stands for a deletion: it opens and previews the deleted member, not a live sibling. */
    @Test
    fun partlyDeletedAlbum_anchorsOnTheDeletedMember() = runTest {
        val (repo, db) = newDb()
        val liveThumb = byteArrayOf(1)
        val goneThumb = byteArrayOf(2)
        db.version("1", 100, "caption", album = "alb", thumb = liveThumb)
        db.version("2", 100, "gone text", album = "alb", thumb = goneThumb)
        db.deleted(500, "2")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Deleted, e.status)
        assertEquals("2", e.messageKey)
        assertEquals("gone text", e.textPreview)
        assertArrayEquals(goneThumb, e.mediaMinithumb)
        assertEquals(2, e.messageIds.size)
    }

    @Test
    fun partlyDeletedAlbum_fallsBackToALiveMemberForTheMissingPreview() {
        fun row(id: Long, key: String, deletedAt: Long?, text: String) = ArchiveEntryRow(
            messageId = id, chat = chat, messageKey = key, albumKey = "a", isComment = false,
            deletedAtMs = deletedAt, versionCount = 1, latestAtMs = 10L, textPreview = text,
            thumbVersionId = null,
        )
        val draft = buildArchiveEntries(
            listOf(row(1, "1", null, "caption"), row(2, "2", 500L, "")),
            emptyMap(),
        ).single()
        assertEquals("2", draft.entry.messageKey)
        assertEquals("caption", draft.entry.textPreview)
    }

    @Test
    fun entriesCarryChannelMetadataAndComeNewestFirst() = runTest {
        val (repo, db) = newDb()
        db.archivedChannelQueries.upsertInsert("TDLIB", "42", "Channel", null, byteArrayOf(5), 0L, 0L)
        db.version("1", 100, "one")
        db.version("1", 200, "two")
        db.version("2", 150, "gone")
        db.deleted(900, "2")

        val entries = repo.observeEntries().first()
        assertEquals(listOf("2", "1"), entries.map { it.messageKey })
        assertEquals("Channel", entries.first().channelTitle)
        assertArrayEquals(byteArrayOf(5), entries.first().channelPhotoMinithumb)
    }

    @Test
    fun guestModeVersion_countsTheLiveVersionToo() = runTest {
        val (repo, db) = newDb()
        db.version("10", 100, "replaced text", chatRef = ChatRef.web("chan"))

        val e = repo.observeEntries().first().single()
        assertEquals(ArchiveEntryStatus.Edited, e.status)
        assertEquals(2, e.versionCount)
    }

    @Test
    fun storageBytes_includeCopiedMediaFiles() = runTest {
        val (repo, db) = newDb()
        db.version("1", 100, "x")
        db.archivedMediaFileQueries.insert("sha", "/p", 1_000L, null, 0L, 1L)

        // One-byte blob + the 1 000-byte file.
        assertEquals(1_001L, repo.observeStorageBytes().first())
    }

    private fun mediaBlob(type: String, mimeType: String? = null): ByteArray = ContentBlobCodec.encode(
        testMeta("").copy(mediaRef = ArchivedMediaRef(type = type, mimeType = mimeType)),
    )

    private fun ArchiveDatabase.mediaFile(sha: String) =
        archivedMediaFileQueries.insert(sha, "/archive_media/$sha.bin", 10L, null, 0L, 0L)

    @Test
    fun editedPhoto_carriesTheArchivedCopyOfThePreviewedVersion() = runTest {
        val (repo, db) = newDb()
        val thumb = byteArrayOf(7)
        db.mediaFile("p1")
        db.version("1", 100, "old", thumb = thumb, blob = mediaBlob("photo"), mediaSha = "p1")
        db.version("1", 200, "new", thumb = thumb, blob = mediaBlob("photo"), mediaSha = "p1")

        val e = repo.observeEntries().first().single()
        assertArrayEquals(thumb, e.mediaMinithumb)
        assertEquals(ArchivedThumbnailCopy("/archive_media/p1.bin", isVideoFrame = false), e.mediaCopy)
    }

    @Test
    fun deletedAlbumVideo_carriesAVideoFrameCopy() = runTest {
        val (repo, db) = newDb()
        db.mediaFile("v1")
        db.version("1", 100, "caption", album = "alb")
        db.version("2", 100, "", album = "alb", thumb = byteArrayOf(3), blob = mediaBlob("video"), mediaSha = "v1")
        db.deleted(500, "1", "2", album = "alb")

        val e = repo.observeEntries().first().single()
        assertEquals(ArchivedThumbnailCopy("/archive_media/v1.bin", isVideoFrame = true), e.mediaCopy)
    }

    @Test
    fun animationCopy_isAFrameUnlessItIsAnImage() {
        assertEquals(true, thumbnailCopyOf(CONTENT_KIND_TDLIB, mediaBlob("animation", "video/mp4"), "/a")?.isVideoFrame)
        assertEquals(false, thumbnailCopyOf(CONTENT_KIND_TDLIB, mediaBlob("animation", "image/gif"), "/a")?.isVideoFrame)
        assertEquals(true, thumbnailCopyOf(CONTENT_KIND_TDLIB, mediaBlob("videoNote"), "/a")?.isVideoFrame)
    }

    /** The revision sheet classifies through the same function as the list. */
    @Test
    fun archivedCopyClassification_matchesTheList() {
        assertEquals(false, archivedCopyIsVideoFrame(ArchivedMediaRef(type = "animation", mimeType = "image/gif")))
        assertEquals(true, archivedCopyIsVideoFrame(ArchivedMediaRef(type = "animation", mimeType = "video/mp4")))
        assertEquals(false, archivedCopyIsVideoFrame(ArchivedMediaRef(type = "photo")))
        assertEquals(true, archivedCopyIsVideoFrame(ArchivedMediaRef(type = "video")))
        assertNull(archivedCopyIsVideoFrame(ArchivedMediaRef(type = "document")))
    }

    /** No copy on the previewed version, or a copy that is not a picture: minithumb only. */
    @Test
    fun entryWithoutDrawableCopy_keepsOnlyTheMinithumb() = runTest {
        val (repo, db) = newDb()
        val thumb = byteArrayOf(4)
        db.mediaFile("old")
        db.mediaFile("doc")
        db.version("1", 100, "a", thumb = thumb, blob = mediaBlob("photo"), mediaSha = "old")
        db.version("1", 200, "b", thumb = thumb, blob = mediaBlob("photo"))
        db.version("2", 100, "c", thumb = thumb, blob = mediaBlob("document"), mediaSha = "doc")
        db.version("2", 200, "d", thumb = thumb, blob = mediaBlob("document"), mediaSha = "doc")
        db.version("3", 100, "e", thumb = thumb, blob = byteArrayOf(1), mediaSha = "old")
        db.version("3", 200, "f", thumb = thumb, blob = byteArrayOf(1), mediaSha = "old")

        val entries = repo.observeEntries().first()
        assertEquals(3, entries.size)
        entries.forEach {
            assertArrayEquals(thumb, it.mediaMinithumb)
            assertNull(it.mediaCopy, it.messageKey)
        }
    }

    @Test
    fun buildEntries_deletionWithoutVersionsIsSkipped() {
        fun row(id: Long, key: String, versions: Int, deletedAt: Long?, album: String? = null) = ArchiveEntryRow(
            messageId = id, chat = chat, messageKey = key, albumKey = album, isComment = false,
            deletedAtMs = deletedAt, versionCount = versions, latestAtMs = 10L, textPreview = "",
            thumbVersionId = null,
        )
        val drafts = buildArchiveEntries(
            listOf(row(1, "7", 0, 500L), row(2, "8", 1, 600L, album = "a"), row(3, "9", 0, 600L, album = "a")),
            emptyMap(),
        )
        assertEquals(listOf("8"), drafts.map { it.entry.messageKey })
        assertEquals(listOf(2L, 3L), drafts.single().entry.messageIds)
    }
}
