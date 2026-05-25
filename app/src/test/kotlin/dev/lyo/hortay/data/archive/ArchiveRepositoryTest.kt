package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArchiveRepositoryTest {

    private fun newRepo(
        settings: ArchiveSettings = ArchiveSettings(enabled = true),
        now: Long = 1_000L,
    ): Pair<ArchiveRepository, ArchiveDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(settings)
        return ArchiveRepository(db, settingsFlow, clock = { now }) to db
    }

    private fun meta(text: String) = TdlibContentMeta(
        text = text, entitiesJson = "[]",
        mediaSummaryJson = null, pollJson = null,
        forwardJson = null, replyJson = null,
    )

    @Test
    fun captureEdit_dedupesIdenticalContentHash() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibEdit(chat, "100", null, 2_000L, meta("hello"))
        repo.captureTdlibEdit(chat, "100", null, 2_000L, meta("hello"))

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(1, rows.size)
    }

    @Test
    fun captureEdit_writesWhenContentDiffers() = runTest {
        var t = 1_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settings = MutableStateFlow(ArchiveSettings(enabled = true))
        val repo = ArchiveRepository(db, settings, clock = { t })

        val chat = ChatRef.tdlib(42)
        repo.captureTdlibEdit(chat, "100", null, 1_500L, meta("hello"))
        t = 5_000L
        repo.captureTdlibEdit(chat, "100", null, 2_000L, meta("hello there"))

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
    }

    @Test
    fun masterToggleOff_skipsCapture() = runTest {
        val (repo, db) = newRepo(settings = ArchiveSettings(enabled = false))
        repo.captureTdlibEdit(ChatRef.tdlib(42), "100", null, 1_500L, meta("hello"))
        repo.captureTdlibBaseline(ChatRef.tdlib(42), "100", null, meta("hello"), originalDateMs = 500L)

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun excludedChat_skipsCapture() = runTest {
        val chat = ChatRef.tdlib(42)
        val (repo, db) = newRepo(settings = ArchiveSettings(
            enabled = true, excludedChats = persistentSetOf(chat)))
        repo.captureTdlibEdit(chat, "100", null, 1_500L, meta("hello"))
        repo.captureTdlibBaseline(chat, "100", null, meta("hello"), originalDateMs = 500L)

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun captureDelete_writesDeletedRow() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibBaseline(chat, "100", null, meta("hello"), originalDateMs = 500L)
        repo.captureTdlibDelete(chat, listOf("100"), albumKey = null, isComment = false)

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
        assertEquals("DELETED", rows.last().kind)
    }

    @Test
    fun captureDelete_groupsAlbumMembers() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibDelete(chat, listOf("100", "101", "102"), albumKey = "5", isComment = false)

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(1, rows.size)
        assertEquals("DELETED", rows.first().kind)
        assertEquals("""["100","101","102"]""", rows.first().deleted_msg_keys)
    }

    @Test
    fun upsertChannel_updatesTitleAndHandle() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.upsertChannel(chat, "Old Title", "old_handle", null, false)
        repo.upsertChannel(chat, "New Title", "new_handle", null, true)

        val row = db.archivedChannelQueries.selectOne("TDLIB", "42").executeAsOne()
        assertEquals("New Title", row.title)
        assertEquals("new_handle", row.handle)
        assertEquals(1L, row.is_verified)
    }

    @Test
    fun baseline_stampedAtOriginalDate_notClock() = runTest {
        val (repo, db) = newRepo(now = 9_999L)
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibBaseline(chat, "100", null, meta("v1"), originalDateMs = 500L)

        val row = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsOne()
        assertEquals(500L, row.seen_at_ms)
        assertNull(row.edited_at_ms)
    }

    @Test
    fun baseline_idempotentEvenWhenContentDiffers() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibBaseline(chat, "100", null, meta("v1"), originalDateMs = 500L)
        // Second call with different content must NOT write — baseline is a fixed
        // point on the timeline, not a hash-keyed dedup.
        repo.captureTdlibBaseline(chat, "100", null, meta("v1-different"), originalDateMs = 500L)

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(1, rows.size)
        assertEquals("v1", toContent(rows[0]).meta.text)
    }

    /**
     * Pre-edited posts (those seen for the first time with `Message.editDate > 0`)
     * record their first-observed state via `priorEditedAtMs`, so a later
     * deletion has a VERSION row to JOIN against in the tombstone-feed query
     * (no orphan DELETED rows). Re-ingest of the same post is still idempotent.
     */
    @Test
    fun baseline_acceptsPriorEditedAt_andIsIdempotentOnReingest() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibBaseline(
            chat, "100", null, meta("v1-edited"),
            originalDateMs = 500L, priorEditedAtMs = 700L,
        )
        repo.captureTdlibBaseline(
            chat, "100", null, meta("v1-edited"),
            originalDateMs = 500L, priorEditedAtMs = 700L,
        )

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(1, rows.size)
        assertEquals(500L, rows[0].seen_at_ms)
        assertEquals(700L, rows[0].edited_at_ms)
    }

    /**
     * Race fix: edit row lands FIRST (cold-start UME beats the launched baseline
     * coroutine), then baseline arrives. Old unified captureTdlibVersion would
     * have stamped the baseline with clock() instead of originalDateMs and the
     * revision timeline would show the edit row preceding the baseline on
     * ASC-by-seen_at sort.
     *
     * Expected: baseline.seen_at_ms = 500, edit.seen_at_ms = 1000;
     * timeline ASC = [baseline, edit].
     */
    @Test
    fun race_editFirstThenBaseline_timelineOrderedCorrectly() = runTest {
        var t = 1_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(db,
            MutableStateFlow(ArchiveSettings(enabled = true)), clock = { t })
        val chat = ChatRef.tdlib(42)

        // Edit lands first (UME won the race).
        repo.captureTdlibEdit(chat, "100", null, 800L, meta("v2-edited"))
        t = 1_200L
        // Baseline arrives late from the captureBaselineSnapshot coroutine.
        repo.captureTdlibBaseline(chat, "100", null, meta("v1-original"), originalDateMs = 500L)

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
        // First by seen_at ASC is the baseline (500 ms < 1000 ms).
        assertEquals(500L, rows[0].seen_at_ms)
        assertNull(rows[0].edited_at_ms)
        assertEquals("v1-original", toContent(rows[0]).meta.text)
        // Second is the edit at clock-time.
        assertEquals(1_000L, rows[1].seen_at_ms)
        assertEquals(800L, rows[1].edited_at_ms)
        assertEquals("v2-edited", toContent(rows[1]).meta.text)
    }

    @Test
    fun edit_seenAtAlwaysClock_evenWhenNoBaselineExists() = runTest {
        var t = 1_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(db,
            MutableStateFlow(ArchiveSettings(enabled = true)), clock = { t })
        val chat = ChatRef.tdlib(42)

        // Comment-style: no baseline ever captured. Edit alone.
        repo.captureTdlibEdit(chat, "100", null, 800L, meta("edited"), isComment = true)

        val row = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsOne()
        assertEquals(1_000L, row.seen_at_ms)
        assertEquals(800L, row.edited_at_ms)
    }

    @Test
    fun observeRevisions_emitsInsertedRowsAscByTime() = runTest {
        var t = 1_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(db,
            MutableStateFlow(ArchiveSettings(enabled = true)), clock = { t })
        val chat = ChatRef.tdlib(42)

        repo.captureTdlibBaseline(chat, "100", null, meta("v1"), originalDateMs = 500L)
        t = 5_000L
        repo.captureTdlibEdit(chat, "100", null, 2_000L, meta("v2"))

        val revisions = repo.observeRevisions(chat, "100").first()
        assertEquals(2, revisions.size)
        assertEquals(SnapshotKind.VERSION, revisions[0].kind)
        assertEquals("v1", (revisions[0].content as ArchivedContent.Tdlib).meta.text)
    }

    /**
     * Comment tombstones must NOT leak into the channel-feed tombstone stream —
     * a deleted comment lives only inside its discussion thread, surfacing it
     * as a ghost-card in the channel timeline would show a tombstone where the
     * original post never existed.
     */
    @Test
    fun tombstones_excludeComments() = runTest {
        val (repo, _) = newRepo()
        val channelChat = ChatRef.tdlib(42)
        // Channel post + its delete.
        repo.captureTdlibBaseline(channelChat, "100", null, meta("post body"), originalDateMs = 500L)
        repo.upsertChannel(channelChat, "Test Channel", "test_handle", null, false)
        repo.captureTdlibDelete(channelChat, listOf("100"), albumKey = null, isComment = false)
        // Comment in the discussion-group chat + its delete. Note discussion chat
        // is a different id (TDLib convention: linked supergroup, not the channel).
        val discussion = ChatRef.tdlib(99)
        repo.captureTdlibEdit(discussion, "200", null, 1_500L, meta("comment body"), isComment = true)
        repo.captureTdlibDelete(discussion, listOf("200"), albumKey = null, isComment = true)

        val tombstones = repo.observeTdlibTombstones().first()
        assertEquals(1, tombstones.size)
        assertEquals(42L, tombstones[0].chatId)
        assertEquals("post body", tombstones[0].text)
    }

    @Test
    fun loggedOutMidFlight_clearsAllSnapshots() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibBaseline(chat, "100", null, meta("a"), originalDateMs = 100L)
        repo.captureTdlibBaseline(chat, "101", null, meta("b"), originalDateMs = 200L)

        repo.clear()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(0, rows.size)
    }

    @Test
    fun concurrentCaptureFromTwoSources_writesBoth() = runTest {
        val (repo, db) = newRepo()
        val tdlibChat = ChatRef.tdlib(42)
        val webChat = ChatRef.web("demo")

        // Capture from TDLib path
        repo.captureTdlibBaseline(tdlibChat, "100", null, meta("tdlib version"), originalDateMs = 500L)

        // Capture from web path (different source kind)
        val webPost = dev.lyo.hortay.data.web.WebPost(
            id = "demo/100", seq = 100L, publishedAt = "2026-05-23T10:00:00Z",
            textHtml = "web version",
            media = kotlinx.collections.immutable.persistentListOf(),
            webPreview = null, forwardedFrom = null, views = null,
            reactions = kotlinx.collections.immutable.persistentListOf(),
        )
        repo.captureWebVersion(webChat, "100", webPost)

        val tdlibRows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        val webRows = db.postSnapshotQueries.selectRevisions("WEB", "demo", "100").executeAsList()
        assertEquals(1, tdlibRows.size)
        assertEquals(1, webRows.size)
    }

    private fun toContent(row: dev.lyo.hortay.data.archive.db.PostSnapshot): ArchivedContent.Tdlib =
        ArchivedContent.Tdlib(ContentBlobCodec.decode(row.content_blob))
}
