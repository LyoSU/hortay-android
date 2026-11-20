package dev.lyo.hortay.data.archive

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A version written before its media was downloaded gets the archived copy a later
 * duplicate capture made (`attachMediaIfSame`), instead of that copy being swept as an
 * orphan and re-made on every launch.
 */
class ArchiveMediaAttachTest {

    private val chat = ChatRef.tdlib(CHAT)

    private class Fixture(available: Boolean = true) {
        val archive = TestArchive()
        val db = archive.db
        val repo = ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)), available = { available })

        fun mediaShas(chat: ChatRef, key: String): List<String?> = db.versionsOf(chat, key).map { it.media_sha }

        /** A copied file whose claim is long past the sweep's grace window. */
        fun storeFile(sha: String) {
            db.archivedMediaFileQueries.insert(sha, "/archive/$sha.bin", 1L, null, 0L, 0L)
        }

        fun orphans(): List<String> = db.archivedMediaFileQueries.selectOrphans(Long.MAX_VALUE).executeAsList().map { it.sha }
    }

    private fun photoMeta(text: String, uniqueId: String = "u1", sha: String? = null) = testMeta(text).copy(
        mediaSummaryJson = """{"type":"photo","w":800,"h":600}""",
        mediaRef = ArchivedMediaRef(type = "photo", width = 800, height = 600, uniqueId = uniqueId, localArchiveSha = sha),
    )

    @Test
    fun baselineDuplicateWithCopy_attachesItToTheStoredVersion() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap"), originalDateMs = 500L)
        f.storeFile("sha1")
        assertEquals(listOf("sha1"), f.orphans())

        val out = f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap", sha = "sha1"), originalDateMs = 500L)

        assertEquals(ArchiveDecision.DUP_HASH, out.decision)
        assertEquals("$DETAIL_BASELINE_PRESENT,$DETAIL_MEDIA_ATTACHED", out.detail)
        assertEquals(listOf<String?>("sha1"), f.mediaShas(chat, "1"))
        assertEquals(emptyList<String>(), f.orphans())
        val shown = f.repo.observeRevisions(chat, "1").first().single().content as ArchivedContent.Tdlib
        assertEquals("sha1", shown.meta.mediaRef?.localArchiveSha)
    }

    @Test
    fun editAndReconcileDuplicates_attachToTheLatestVersionOnly() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("v1"), originalDateMs = 500L)
        f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = 2_000L, meta = photoMeta("v2"))

        val edit = f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = 2_000L, meta = photoMeta("v2", sha = "e"))
        assertEquals(ArchiveDecision.DUP_HASH, edit.decision)
        assertTrue(hasMediaAttached(edit.detail))
        assertEquals(listOf(null, "e"), f.mediaShas(chat, "1"))

        val g = Fixture()
        g.repo.captureTdlibBaseline(chat, "2", null, photoMeta("same"), originalDateMs = 500L)
        val reconcile = g.repo.captureTdlibReconcile(chat, "2", null, editedAtMs = 3_000L, meta = photoMeta("same", sha = "r"))
        assertEquals(ArchiveDecision.DUP_HASH, reconcile.decision)
        assertEquals(DETAIL_MEDIA_ATTACHED, reconcile.detail)
        assertEquals(listOf<String?>("r"), g.mediaShas(chat, "2"))
    }

    @Test
    fun existingReference_isNeverReplaced() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap", sha = "first"), originalDateMs = 500L)

        val out = f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap", sha = "second"), originalDateMs = 500L)

        assertEquals(ArchiveDecision.DUP_HASH, out.decision)
        assertEquals(DETAIL_BASELINE_PRESENT, out.detail)
        assertEquals(listOf<String?>("first"), f.mediaShas(chat, "1"))
    }

    @Test
    fun differentMediaIdentity_isNotAttached() = runTest {
        val f = Fixture()
        f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = 2_000L, meta = photoMeta("cap", uniqueId = "u1"))

        val out = f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = 2_000L, meta = photoMeta("cap", "u2", "other"))

        assertEquals(ArchiveDecision.DUP_HASH, out.decision)
        assertFalse(hasMediaAttached(out.detail))
        assertEquals(listOf<String?>(null), f.mediaShas(chat, "1"))
    }

    @Test
    fun differentContent_writesANewVersionAndLeavesTheOldOneAlone() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("old"), originalDateMs = 500L)

        val out = f.repo.captureTdlibEdit(chat, "1", null, editedAtMs = 2_000L, meta = photoMeta("new", sha = "n"))

        assertEquals(ArchiveDecision.WRITTEN, out.decision)
        assertNull(out.detail)
        assertEquals(listOf(null, "n"), f.mediaShas(chat, "1"))
    }

    @Test
    fun disabledOrUnavailableArchive_neverAttaches() = runTest {
        val f = Fixture()
        f.repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap"), originalDateMs = 500L)
        val off = ArchiveRepository(f.db, MutableStateFlow(ArchiveSettings(enabled = false)))
        val excluded = ArchiveRepository(
            f.db, MutableStateFlow(ArchiveSettings(enabled = true, excludedChats = persistentSetOf(chat))),
        )
        val unavailable = ArchiveRepository(f.db, MutableStateFlow(ArchiveSettings(enabled = true)), available = { false })

        for (repo in listOf(off, unavailable)) {
            assertEquals(
                ArchiveDecision.DISABLED,
                repo.captureTdlibBaseline(chat, "1", null, photoMeta("cap", sha = "s"), originalDateMs = 500L).decision,
            )
            repo.captureTdlibEdit(chat, "1", null, editedAtMs = 500L, meta = photoMeta("cap", sha = "s"))
            repo.captureTdlibReconcile(chat, "1", null, editedAtMs = 900L, meta = photoMeta("cap", sha = "s"))
        }
        assertEquals(
            ArchiveDecision.EXCLUDED,
            excluded.captureTdlibBaseline(chat, "1", null, photoMeta("cap", sha = "s"), originalDateMs = 500L).decision,
        )

        assertEquals(listOf<String?>(null), f.mediaShas(chat, "1"))
    }

    // --- Recorder: the baseline-present re-ingest that used to skip without a copy ---

    @Test
    fun reIngestWithDownloadedPhoto_attachesTheCopyToTheBaseline() = runTest {
        val f = Fixture()
        val journal = InMemoryArchiveJournal()
        val copies = mutableListOf<TdApi.MessageContent>()
        val session = CoroutineScope(coroutineContext + Job())
        fun recorder() = ArchiveRecorder(
            repo = f.repo, journal = journal, scope = { session }, readLocal = { _, _ -> null },
            copyMedia = { content ->
                copies += content
                MediaFileFromContent.extract(content)?.local?.takeIf { it.isDownloadingCompleted }?.let { "photo-sha" }
            },
            consumerContext = EmptyCoroutineContext, clock = { 0L },
        )

        // First ingest: the photo is not downloaded yet, so the baseline has no copy.
        recorder().recordIngest(photoMessage(downloaded = false), newToFeed = true, channel = null)
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), f.mediaShas(chat, "1"))

        // Next launch (fresh recorder session): the file is on disk now.
        recorder().recordIngest(photoMessage(downloaded = true), newToFeed = true, channel = null)
        advanceUntilIdle()

        assertEquals(listOf<String?>("photo-sha"), f.mediaShas(chat, "1"))
        val last = journal.records().last()
        assertEquals(ArchiveDecision.DUP_HASH, last.decision)
        assertTrue(hasMediaAttached(last.detail))
        assertTrue(last.persist)

        // Once attached, a further re-ingest neither copies nor touches the reference.
        val copiesBefore = copies.size
        recorder().recordIngest(photoMessage(downloaded = true), newToFeed = true, channel = null)
        advanceUntilIdle()
        assertEquals(copiesBefore, copies.size)
        assertEquals(listOf<String?>("photo-sha"), f.mediaShas(chat, "1"))
        session.coroutineContext[Job]?.cancel()
    }

    // --- Recorder: an edited post re-ingested at the edit date the archive already holds ---

    /** Recorder sessions over one archive; `copyMedia` succeeds only for a downloaded file. */
    private class RecorderRig(val f: Fixture, private val session: CoroutineScope) {
        val journal = InMemoryArchiveJournal()
        val copies = mutableListOf<TdApi.MessageContent>()

        /** What `GetMessageLocally` returns for the live-edit path. */
        var local: TdApi.Message? = null

        fun recorder() = ArchiveRecorder(
            repo = f.repo, journal = journal, scope = { session }, readLocal = { _, _ -> local },
            copyMedia = { content ->
                copies += content
                MediaFileFromContent.extract(content)?.local?.takeIf { it.isDownloadingCompleted }?.let { "edit-sha" }
            },
            consumerContext = EmptyCoroutineContext, clock = { 0L },
        )
    }

    @Test
    fun reIngestAtTheStoredEditDate_attachesTheCopyToTheLiveEditVersion() = runTest {
        val session = CoroutineScope(coroutineContext + Job())
        val rig = RecorderRig(Fixture(), session)
        // Live edit while the photo is not downloaded: the version has no copy.
        rig.local = photoMessage(downloaded = false, editDate = 300)
        rig.recorder().recordEdit(CHAT, 1L, 300, null, albumKey = null, channel = null, isComment = false)
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), rig.f.mediaShas(chat, "1"))

        // Next launch: re-ingested at the same edit date, the file is on disk now.
        rig.recorder().recordIngest(photoMessage(downloaded = true, editDate = 300), newToFeed = true, channel = null)
        advanceUntilIdle()

        assertEquals(listOf<String?>("edit-sha"), rig.f.mediaShas(chat, "1"))
        val last = rig.journal.records().last()
        assertEquals(ArchiveDecision.STALE, last.decision)
        assertEquals("$DETAIL_NO_NEWER_EDIT,$DETAIL_MEDIA_ATTACHED", last.detail)
        assertTrue(last.persist)

        // Attached: the next re-ingest neither copies nor touches the reference.
        val copiesBefore = rig.copies.size
        rig.recorder().recordIngest(photoMessage(downloaded = true, editDate = 300), newToFeed = true, channel = null)
        advanceUntilIdle()
        assertEquals(copiesBefore, rig.copies.size)
        assertEquals(listOf<String?>("edit-sha"), rig.f.mediaShas(chat, "1"))
        assertEquals(DETAIL_NO_NEWER_EDIT, rig.journal.records().last().detail)
        session.coroutineContext[Job]?.cancel()
    }

    @Test
    fun reIngestAtTheStoredEditDateWithDifferentContent_neitherAttachesNorWrites() = runTest {
        val session = CoroutineScope(coroutineContext + Job())
        val rig = RecorderRig(Fixture(), session)
        rig.local = photoMessage(downloaded = false, editDate = 300, caption = "edited")
        rig.recorder().recordEdit(CHAT, 1L, 300, null, albumKey = null, channel = null, isComment = false)
        advanceUntilIdle()

        rig.recorder().recordIngest(
            photoMessage(downloaded = true, editDate = 300, caption = "changed"), newToFeed = true, channel = null,
        )
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), rig.f.mediaShas(chat, "1"))
        val last = rig.journal.records().last()
        assertEquals(ArchiveDecision.STALE, last.decision)
        assertEquals(DETAIL_NO_NEWER_EDIT, last.detail)
        session.coroutineContext[Job]?.cancel()
    }

    private fun photoMessage(downloaded: Boolean, editDate: Int = 0, caption: String = "cap") = TdApi.Message().apply {
        chatId = CHAT
        id = 1L
        date = 100
        this.editDate = editDate
        content = TdApi.MessagePhoto().apply {
            this.caption = TdApi.FormattedText(caption, emptyArray())
            photo = TdApi.Photo().apply {
                sizes = arrayOf(
                    TdApi.PhotoSize().apply {
                        type = "x"
                        width = 800
                        height = 600
                        photo = TdApi.File().apply {
                            local = TdApi.LocalFile().apply {
                                path = if (downloaded) "/tdlib/photo.jpg" else ""
                                isDownloadingCompleted = downloaded
                            }
                            remote = TdApi.RemoteFile().apply {
                                id = "r1"
                                uniqueId = "u1"
                            }
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val CHAT = 42L
    }
}
