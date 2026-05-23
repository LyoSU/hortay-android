package dev.lyo.hortay.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArchiveSweepTest {

    private fun meta(text: String) = TdlibContentMeta(
        text = text, entitiesJson = "[]",
        mediaSummaryJson = null, pollJson = null,
        forwardJson = null, replyJson = null,
    )

    @Test
    fun sweep_purgesOlderThanRetention() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val now = 30L * 86_400_000L + 1_000_000L
        val settingsFlow = MutableStateFlow(ArchiveSettings(enabled = true, retentionDays = 30))

        val ancientRepo = ArchiveRepository(db, settingsFlow, clock = { 0L })
        ancientRepo.captureTdlibVersion(ChatRef.tdlib(1), "1", null, null, meta("ancient"))

        val freshRepo = ArchiveRepository(db, settingsFlow, clock = { now })
        freshRepo.captureTdlibVersion(ChatRef.tdlib(1), "2", null, null, meta("fresh"))

        ArchiveSweep(db, settingsFlow, clock = { now }).run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(1, rows.size)
        assertEquals("2", rows[0].message_key)
    }

    @Test
    fun sweep_keepsNewestUpToCap() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(ArchiveSettings(enabled = true, maxRecords = 3))
        val repo = ArchiveRepository(db, settingsFlow, clock = { 1_000L })

        repeat(5) { i ->
            repo.captureTdlibVersion(ChatRef.tdlib(1), "$i", null, null, meta("v$i"))
        }
        ArchiveSweep(db, settingsFlow, clock = { 1_000L }).run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(3, rows.size)
    }

    @Test
    fun unlimitedRetention_keepsEverything() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(ArchiveSettings(
            enabled = true, retentionDays = Int.MAX_VALUE, maxRecords = Int.MAX_VALUE))
        val ancientRepo = ArchiveRepository(db, settingsFlow, clock = { 0L })
        ancientRepo.captureTdlibVersion(ChatRef.tdlib(1), "1", null, null, meta("ancient"))

        ArchiveSweep(db, settingsFlow, clock = { Long.MAX_VALUE / 2 }).run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(1, rows.size)
    }
}
