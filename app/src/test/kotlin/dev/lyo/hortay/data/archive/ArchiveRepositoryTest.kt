package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
        repo.captureTdlibVersion(chat, "100", null, null, meta("hello"))
        repo.captureTdlibVersion(chat, "100", null, null, meta("hello"))

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
        repo.captureTdlibVersion(chat, "100", null, null, meta("hello"))
        t = 5_000L  // > 500 ms later
        repo.captureTdlibVersion(chat, "100", null, 2_000L, meta("hello there"))

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList()
        assertEquals(2, rows.size)
    }

    @Test
    fun masterToggleOff_skipsCapture() = runTest {
        val (repo, db) = newRepo(settings = ArchiveSettings(enabled = false))
        repo.captureTdlibVersion(ChatRef.tdlib(42), "100", null, null, meta("hello"))

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun excludedChat_skipsCapture() = runTest {
        val chat = ChatRef.tdlib(42)
        val (repo, db) = newRepo(settings = ArchiveSettings(
            enabled = true, excludedChats = persistentSetOf(chat)))
        repo.captureTdlibVersion(chat, "100", null, null, meta("hello"))

        assertEquals(0, db.postSnapshotQueries.selectRevisions("TDLIB", "42", "100").executeAsList().size)
    }

    @Test
    fun captureDelete_writesDeletedRow() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, null, meta("hello"))
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
    fun observeRevisions_emitsInsertedRowsAscByTime() = runTest {
        var t = 1_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(db,
            MutableStateFlow(ArchiveSettings(enabled = true)), clock = { t })
        val chat = ChatRef.tdlib(42)

        repo.captureTdlibVersion(chat, "100", null, null, meta("v1"))
        t = 5_000L
        repo.captureTdlibVersion(chat, "100", null, 2L, meta("v2"))

        val revisions = repo.observeRevisions(chat, "100").first()
        assertEquals(2, revisions.size)
        assertEquals(SnapshotKind.VERSION, revisions[0].kind)
        assertEquals("v1", (revisions[0].content as ArchivedContent.Tdlib).meta.text)
    }

    @Test
    fun loggedOutMidFlight_clearsAllSnapshots() = runTest {
        val (repo, db) = newRepo()
        val chat = ChatRef.tdlib(42)
        repo.captureTdlibVersion(chat, "100", null, null, meta("a"))
        repo.captureTdlibVersion(chat, "101", null, null, meta("b"))

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
        repo.captureTdlibVersion(tdlibChat, "100", null, null, meta("tdlib version"))

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
}
