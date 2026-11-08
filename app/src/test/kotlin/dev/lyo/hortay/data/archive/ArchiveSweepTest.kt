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
        ancientRepo.captureTdlibBaseline(ChatRef.tdlib(1), "1", null, meta("ancient"), originalDateMs = 0L)

        val freshRepo = ArchiveRepository(db, settingsFlow, clock = { now })
        freshRepo.captureTdlibBaseline(ChatRef.tdlib(1), "2", null, meta("fresh"), originalDateMs = now)

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
            repo.captureTdlibBaseline(ChatRef.tdlib(1), "$i", null, meta("v$i"), originalDateMs = 1_000L)
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
        ancientRepo.captureTdlibBaseline(ChatRef.tdlib(1), "1", null, meta("ancient"), originalDateMs = 0L)

        ArchiveSweep(db, settingsFlow, clock = { Long.MAX_VALUE / 2 }).run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(1, rows.size)
    }

    @Test
    fun capHoldsUnderBurst() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(ArchiveSettings(enabled = true, maxRecords = 100))
        val repo = ArchiveRepository(db, settingsFlow, clock = { 1_000L })

        // Write 250 rows (the repo evicts every 100 inserts, so we expect ≤ 200 at all times)
        repeat(250) { i ->
            repo.captureTdlibBaseline(ChatRef.tdlib(1), "$i", null, meta("v$i"), originalDateMs = 1_000L)
        }

        ArchiveSweep(db, settingsFlow, clock = { 1_000L }).run()

        val rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(100, rows.size)
    }

    @Test
    fun retentionChange_triggersImmediateSweep() = runTest {
        // (note: actual retention-change-triggered sweep happens in ArchiveSettingsViewModel.setRetentionDays
        // via sweep.run(); this test simply verifies the sweep itself respects current settings)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val settingsFlow = MutableStateFlow(ArchiveSettings(enabled = true, retentionDays = 90))
        val now = 30L * 86_400_000L + 1_000_000L

        val ancientRepo = ArchiveRepository(db, settingsFlow, clock = { 0L })
        ancientRepo.captureTdlibBaseline(ChatRef.tdlib(1), "1", null, meta("ancient"), originalDateMs = 0L)

        // Initial sweep at 90d retention — ancient (30d old) stays
        ArchiveSweep(db, settingsFlow, clock = { now }).run()
        var rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(1, rows.size)

        // User changes retention to 7d — sweep should now purge
        settingsFlow.value = settingsFlow.value.copy(retentionDays = 7)
        ArchiveSweep(db, settingsFlow, clock = { now }).run()
        rows = db.postSnapshotQueries.selectAllForFilter(null, null, null, null).executeAsList()
        assertEquals(0, rows.size)
    }

    /**
     * A baseline is stamped `seen_at_ms = publication time` for the timeline's
     * "first dot = published" anchor. Retention must key on capture time, or an
     * old post's baseline is swept the night it is captured — losing the
     * original of a later edit and the body of a later tombstone.
     */
    @Test
    fun sweep_keepsFreshlyCapturedBaselineOfOldPost() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ArchiveDatabase.Schema.create(driver)
        val db = ArchiveDatabase(driver)
        val now = 100L * 86_400_000L
        val settingsFlow = MutableStateFlow(ArchiveSettings(enabled = true, retentionDays = 30))
        val repo = ArchiveRepository(db, settingsFlow, clock = { now })

        // Published 90 days ago, first observed (captured) now.
        repo.captureTdlibBaseline(
            ChatRef.tdlib(1), "1", null, meta("original"),
            originalDateMs = now - 90L * 86_400_000L,
        )
        repo.captureTdlibEdit(ChatRef.tdlib(1), "1", null, editedAtMs = now, meta = meta("edited"))

        ArchiveSweep(db, settingsFlow, clock = { now }).run()

        val rows = db.postSnapshotQueries.selectRevisions("TDLIB", "1", "1").executeAsList()
        assertEquals(2, rows.size)
        // Timeline anchor is untouched: the first dot is still the publication time.
        assertEquals(now - 90L * 86_400_000L, rows[0].seen_at_ms)
    }
}
