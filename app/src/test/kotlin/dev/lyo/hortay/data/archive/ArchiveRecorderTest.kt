package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

class ArchiveRecorderTest {

    private class Harness(scope: TestScope, settings: ArchiveSettings = ArchiveSettings(enabled = true)) {
        val archive = TestArchive()
        val db: ArchiveDatabase = archive.db
        val settingsFlow = MutableStateFlow(settings)
        private var t = 1_700_000_000_000L
        val journal = InMemoryArchiveJournal()
        val repo = ArchiveRepository(db, settingsFlow, clock = { t++ }, journal = journal)

        /** What `GetMessageLocally` returns, keyed by message id. */
        val local = HashMap<Long, TdApi.Message>()

        /** A message id whose local read suspends until its gate completes. */
        val gates = HashMap<Long, CompletableDeferred<Unit>>()
        val reads = mutableListOf<Long>()
        private val session = CoroutineScope(scope.coroutineContext + Job())

        val recorder = ArchiveRecorder(
            repo = repo,
            journal = journal,
            // Not a child of the test (runTest would wait on the endless consumer), and not
            // backgroundScope (advanceUntilIdle ignores background work).
            scope = { session },
            readLocal = { _, messageId ->
                reads += messageId
                gates[messageId]?.await()
                local[messageId]
            },
            copyMedia = { null },
            consumerContext = EmptyCoroutineContext,
            clock = { 0L },
        )

        fun texts(messageId: Long): List<String> = db.textsOf(ChatRef.tdlib(CHAT), messageId.toString())

        fun editedAt(messageId: Long): List<Long?> =
            db.versionsOf(ChatRef.tdlib(CHAT), messageId.toString()).map { it.edit_date_ms }

        fun messageKeys(): List<String> =
            db.archivedMessageQueries.selectPageForExport(0L, Long.MAX_VALUE).executeAsList().map { it.message_key }

        fun decisions(): List<Pair<ArchiveEventKind, ArchiveDecision>> =
            journal.records().map { it.event to it.decision }
    }

    private fun text(s: String) = TdApi.MessageText(TdApi.FormattedText(s, emptyArray()), null, null)

    private fun msg(id: Long, body: String, editDate: Int = 0, date: Int = 100, albumId: Long = 0L) =
        TdApi.Message().apply {
            this.chatId = CHAT
            this.id = id
            this.date = date
            this.editDate = editDate
            this.mediaAlbumId = albumId
            this.content = text(body)
        }

    private fun ArchiveRecorder.edit(id: Long, editDate: Int, buffered: TdApi.MessageContent? = null) =
        recordEdit(CHAT, id, editDate, buffered, albumKey = null, channel = null, isComment = false)

    // --- Ordering ---

    @Test
    fun editsAreWrittenInEnqueueOrderEvenWhenTheFirstReadIsSlow() = runTest {
        val h = Harness(this)
        h.local[1L] = msg(1L, "A", editDate = 200)
        h.local[2L] = msg(2L, "B", editDate = 201)
        h.gates[1L] = CompletableDeferred()

        h.recorder.edit(1L, 200)
        h.recorder.edit(2L, 201)
        runCurrent()
        // B is queued behind the in-flight A; it neither reads nor writes yet.
        assertEquals(listOf(1L), h.reads)
        assertEquals(emptyList<String>(), h.texts(2L))

        h.gates.getValue(1L).complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), h.messageKeys())
    }

    // --- Edit content source ---

    @Test
    fun umeBeforeUmcWritesOneVersionFromTheLocalRead() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(5L, "v1"), newToFeed = true, channel = null)
        // UME arrives with nothing buffered; TDLib already holds the edited message.
        h.local[5L] = msg(5L, "v2", editDate = 300)
        h.recorder.edit(5L, 300, buffered = null)
        advanceUntilIdle()

        assertEquals(listOf("v1", "v2"), h.texts(5L))
        assertEquals(listOf(null, 300_000L), h.editedAt(5L))
        val edit = h.journal.records().last()
        assertEquals(ArchiveContentSource.LOCAL_READ, edit.contentSource)
    }

    @Test
    fun umcBeforeUmeWritesOneVersionPreferringTheLocalRead() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(5L, "v1"), newToFeed = true, channel = null)
        h.local[5L] = msg(5L, "v2", editDate = 300)
        h.recorder.edit(5L, 300, buffered = text("v2"))
        // TDLib re-emits the same edit (reconnect): deduped by hash.
        h.recorder.edit(5L, 300, buffered = text("v2"))
        advanceUntilIdle()

        assertEquals(listOf("v1", "v2"), h.texts(5L))
        assertEquals(
            listOf(
                ArchiveEventKind.BASELINE to ArchiveDecision.WRITTEN,
                ArchiveEventKind.EDIT to ArchiveDecision.WRITTEN,
                ArchiveEventKind.EDIT to ArchiveDecision.DUP_HASH,
            ),
            h.decisions(),
        )
    }

    @Test
    fun bufferedContentIsTheFallbackWhenTheLocalReadFails() = runTest {
        val h = Harness(this)
        h.recorder.edit(6L, 400, buffered = text("from-umc"))
        advanceUntilIdle()
        assertEquals(listOf("from-umc"), h.texts(6L))
        assertEquals(ArchiveContentSource.BUFFERED, h.journal.records().single().contentSource)
    }

    @Test
    fun editWithNoContentAnywhereRecordsNoContent() = runTest {
        val h = Harness(this)
        h.recorder.edit(7L, 400, buffered = null)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), h.texts(7L))
        assertEquals(listOf(ArchiveEventKind.EDIT to ArchiveDecision.NO_CONTENT), h.decisions())
    }

    @Test
    fun laterEditReadLocallyIsRecordedWithItsOwnEditDateAndNotTwice() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(8L, "v1"), newToFeed = true, channel = null)
        // Two edits queued; by the time the first is processed TDLib already holds the second.
        h.local[8L] = msg(8L, "v3", editDate = 500)
        h.recorder.edit(8L, 400, buffered = text("v2"))
        h.recorder.edit(8L, 500, buffered = text("v3"))
        advanceUntilIdle()
        assertEquals(listOf("v1", "v3"), h.texts(8L))
        assertEquals(listOf(null, 500_000L), h.editedAt(8L))
    }

    // --- Non-edit content updates ---

    @Test
    fun pollVoteStyleContentUpdatesNeverCreateVersions() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(9L, "poll"), newToFeed = true, channel = null)
        // A bare UpdateMessageContent only stashes into the pairing buffer; no UME follows.
        val buffer = PendingEditBuffer(nowMs = { 0L })
        buffer.stash(CHAT, 9L, text("poll (votes changed)"))
        // A non-edit UpdateMessageEdited carries editDate = 0.
        h.recorder.edit(9L, 0, buffered = buffer.commitOnEdited(CHAT, 9L))
        // A reply-markup-only edit: real edit date, unchanged content.
        h.local[9L] = msg(9L, "poll", editDate = 600)
        h.recorder.edit(9L, 600)
        advanceUntilIdle()

        assertEquals(listOf("poll"), h.texts(9L))
        assertEquals(
            listOf(
                ArchiveEventKind.BASELINE to ArchiveDecision.WRITTEN,
                ArchiveEventKind.EDIT to ArchiveDecision.DUP_HASH,
            ),
            h.decisions(),
        )
    }

    // --- Reconcile ---

    @Test
    fun reingestWithNewerEditDateAndDifferentContentWritesOneVersion() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(10L, "v1"), newToFeed = true, channel = null)
        advanceUntilIdle()
        h.recorder.recordIngest(msg(10L, "v2", editDate = 200), newToFeed = false, channel = null)
        advanceUntilIdle()

        assertEquals(listOf("v1", "v2"), h.texts(10L))
        assertEquals(listOf(null, 200_000L), h.editedAt(10L))
        assertEquals(ArchiveEventKind.RECONCILE to ArchiveDecision.WRITTEN, h.decisions().last())
    }

    @Test
    fun reingestWithSameContentWritesNothing() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(11L, "v1"), newToFeed = true, channel = null)
        h.recorder.recordIngest(msg(11L, "v1", editDate = 200), newToFeed = false, channel = null)
        advanceUntilIdle()

        assertEquals(listOf("v1"), h.texts(11L))
        assertEquals(ArchiveEventKind.RECONCILE to ArchiveDecision.DUP_HASH, h.decisions().last())
    }

    @Test
    fun reingestWithOlderEditDateWritesNothing() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(12L, "v1"), newToFeed = true, channel = null)
        h.local[12L] = msg(12L, "v3", editDate = 500)
        h.recorder.edit(12L, 500)
        advanceUntilIdle()
        // A history page fetched before the edit lands afterwards with an older state.
        h.recorder.recordIngest(msg(12L, "v2", editDate = 400), newToFeed = false, channel = null)
        advanceUntilIdle()

        assertEquals(listOf("v1", "v3"), h.texts(12L))
        val last = h.journal.records().last()
        assertEquals(ArchiveEventKind.RECONCILE to ArchiveDecision.STALE, last.event to last.decision)
    }

    @Test
    fun unchangedReingestIsDroppedBeforeEnqueueing() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(13L, "v1"), newToFeed = true, channel = null)
        advanceUntilIdle()
        // Not new to the feed and never edited: no event at all.
        h.recorder.recordIngest(msg(13L, "v1"), newToFeed = false, channel = null)
        // New to the feed again (evicted and re-added) at the same state: deduped this session.
        h.recorder.recordIngest(msg(13L, "v1"), newToFeed = true, channel = null)
        advanceUntilIdle()
        assertEquals(1, h.journal.records().size)
    }

    @Test
    fun archiveOffEnqueuesNothing() = runTest {
        val h = Harness(this, ArchiveSettings(enabled = false))
        h.recorder.recordIngest(msg(14L, "v1"), newToFeed = true, channel = null)
        h.recorder.edit(14L, 200, buffered = text("v2"))
        h.recorder.recordDelete(CHAT, longArrayOf(14L), channel = null)
        advanceUntilIdle()
        assertTrue(h.reads.isEmpty())
        assertTrue(h.journal.records().isEmpty())
    }

    // --- Deletes ---

    @Test
    fun splitAlbumDeletePulsesCollapseIntoOneDeletion() = runTest {
        val h = Harness(this)
        for (id in 20L..22L) {
            h.recorder.recordIngest(msg(id, "photo $id", albumId = 77L), newToFeed = true, channel = null)
        }
        advanceUntilIdle()
        h.recorder.recordDelete(CHAT, longArrayOf(20L, 21L), channel = null)
        advanceTimeBy(50)
        h.recorder.recordDelete(CHAT, longArrayOf(22L), channel = null)
        advanceTimeBy(ArchiveRecorder.DELETE_DEBOUNCE_MS + 50)
        runCurrent()

        val deletedAt = (20L..22L).map { h.db.messageOf(ChatRef.tdlib(CHAT), "$it")?.deleted_at_ms }
        assertTrue(deletedAt.all { it != null }, deletedAt.toString())
        assertEquals(1, deletedAt.toSet().size)
        assertEquals(1, h.decisions().count { it.first == ArchiveEventKind.DELETE })
        assertEquals(ArchiveEventKind.DELETE to ArchiveDecision.WRITTEN, h.decisions().last())
        assertEquals("keys=3", h.journal.records().last().detail)
    }

    @Test
    fun deleteOfNeverArchivedMessagesIsLoggedButNotPersisted() = runTest {
        val h = Harness(this)
        h.recorder.recordDelete(CHAT, longArrayOf(50L, 51L), channel = null)
        h.recorder.recordCommentDelete(CHAT, longArrayOf(52L))
        advanceTimeBy(ArchiveRecorder.DELETE_DEBOUNCE_MS + 50)
        runCurrent()

        assertEquals(0L, h.db.countMessages())
        val records = h.journal.records()
        assertEquals(3, records.size)
        for (r in records) {
            assertEquals(ArchiveEventKind.DELETE to ArchiveDecision.NO_CONTENT, r.event to r.decision)
            assertTrue(r.detail.orEmpty().startsWith(DETAIL_NEVER_ARCHIVED), r.detail)
            assertFalse(r.persist)
        }
    }

    // --- Re-ingest labelling ---

    @Test
    fun reingestOfANeverEditedBaselinedMessageIsABaselineDuplicate() = runTest {
        val h = Harness(this)
        // Baselined in an earlier session: this recorder's in-session dedup has not seen it.
        h.repo.captureTdlibBaseline(
            ChatRef.tdlib(CHAT), "60", null, TdlibContentMetaExtractor.extract(text("v1")), originalDateMs = 100_000L,
        )
        h.recorder.recordIngest(msg(60L, "v1"), newToFeed = true, channel = null)
        advanceUntilIdle()

        val r = h.journal.records().single()
        assertEquals(ArchiveEventKind.BASELINE to ArchiveDecision.DUP_HASH, r.event to r.decision)
        assertEquals(DETAIL_BASELINE_PRESENT, r.detail)
        assertFalse(r.persist)
        assertEquals(listOf("v1"), h.texts(60L))
    }

    // --- Logout ---

    @Test
    fun logoutMidQueueWritesNothingAfterwards() = runTest {
        val h = Harness(this)
        h.local[30L] = msg(30L, "A", editDate = 200)
        h.local[31L] = msg(31L, "B", editDate = 201)
        h.gates[30L] = CompletableDeferred()
        h.recorder.edit(30L, 200)
        h.recorder.edit(31L, 201)
        h.recorder.recordDelete(CHAT, longArrayOf(31L), channel = null)
        runCurrent()

        val reset = launch { h.recorder.reset() }
        runCurrent()
        h.gates.getValue(30L).complete(Unit)
        advanceUntilIdle()
        assertTrue(reset.isCompleted)

        assertEquals(0L, h.db.countMessages())
        assertEquals(listOf(30L), h.reads)

        // The next session's events still flow through a fresh consumer.
        h.local[32L] = msg(32L, "C", editDate = 300)
        h.recorder.edit(32L, 300)
        advanceUntilIdle()
        assertEquals(listOf("C"), h.texts(32L))
    }

    // --- Journal ---

    @Test
    fun journalRecordsCarryNoTextAndOneRecordPerDecision() = runTest {
        val h = Harness(this)
        val secret = "SECRET POST WORDS"
        h.recorder.recordIngest(msg(40L, "$secret v1"), newToFeed = true, channel = null)
        h.local[40L] = msg(40L, "$secret v2", editDate = 200)
        h.recorder.edit(40L, 200)
        h.recorder.edit(40L, 200)
        h.recorder.recordCommentDelete(CHAT, longArrayOf(40L))
        advanceUntilIdle()

        val records = h.journal.records()
        assertEquals(
            listOf(
                ArchiveEventKind.BASELINE to ArchiveDecision.WRITTEN,
                ArchiveEventKind.EDIT to ArchiveDecision.WRITTEN,
                ArchiveEventKind.EDIT to ArchiveDecision.DUP_HASH,
                ArchiveEventKind.DELETE to ArchiveDecision.WRITTEN,
            ),
            records.map { it.event to it.decision },
        )
        for (r in records) {
            val line = r.toJsonLine()
            assertFalse(line.contains("SECRET"), line)
            r.newHash?.let { assertTrue(line.contains("\"hash\":\"${it.take(8)}\""), line) }
            assertFalse(r.newHash != null && line.contains(r.newHash!!), "full hash leaked: $line")
        }
        assertTrue(records[3].isComment)
    }

    @Test
    fun editRecordsCarryTheContentFreeDiffAgainstTheLatestVersion() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(41L, "SECRET one"), newToFeed = true, channel = null)
        h.local[41L] = msg(41L, "SECRET one two", editDate = 200)
        h.recorder.edit(41L, 200)
        advanceUntilIdle()

        val edit = h.journal.records().last()
        assertEquals(ArchiveEventKind.EDIT to ArchiveDecision.WRITTEN, edit.event to edit.decision)
        val line = edit.toJsonLine()
        assertTrue(line.contains("\"diff\":\"text\""), line)
        assertTrue(line.contains("\"textLenOld\":10") && line.contains("\"textLenNew\":14"), line)
        assertFalse(line.contains("SECRET"), line)
        // Persisted, so an exported journal shows it too.
        assertEquals("text", h.journal.readAll().last()["diff"].toString().trim('"'))
    }

    @Test
    fun inMemoryJournalIsBounded() {
        val journal = InMemoryArchiveJournal(capacity = 3)
        repeat(5) { i -> journal.record(record("m$i")) }
        assertEquals(listOf("m2", "m3", "m4"), journal.records().map { it.messageKey })
    }

    @Test
    fun fileJournalRotatesAndSkipsNonPersistedRecords(@TempDir dir: File) {
        val logged = mutableListOf<String>()
        val journal = FileArchiveJournal(dir, perFile = 2, logcat = { logged += it })
        repeat(5) { i -> journal.record(record("m$i")) }
        journal.record(record("noise", persist = false))

        val kept = journal.readAll().map { it["msg"].toString().trim('"') }
        assertEquals(listOf("m2", "m3", "m4"), kept)
        assertEquals(6, logged.size)

        // A fresh instance over the same files keeps rotating from the stored line count.
        val reopened = FileArchiveJournal(dir, perFile = 2)
        reopened.record(record("m5"))
        reopened.record(record("m6"))
        assertEquals(listOf("m4", "m5", "m6"), reopened.readAll().map { it["msg"].toString().trim('"') })

        reopened.clear()
        assertTrue(reopened.readAll().isEmpty())
    }

    @Test
    fun clearingTheArchiveClearsTheJournal() = runTest {
        val h = Harness(this)
        h.recorder.recordIngest(msg(50L, "v1"), newToFeed = true, channel = null)
        advanceUntilIdle()
        assertEquals(1, h.journal.records().size)
        h.repo.clear()
        assertTrue(h.journal.records().isEmpty())
    }

    private fun record(key: String, persist: Boolean = true) = ArchiveJournalRecord(
        wallTimeMs = 1L, sourceKind = SourceKind.TDLIB, chatKey = "1", messageKey = key,
        event = ArchiveEventKind.EDIT, decision = ArchiveDecision.WRITTEN, persist = persist,
    )

    private companion object {
        const val CHAT = -100_123L
    }
}
