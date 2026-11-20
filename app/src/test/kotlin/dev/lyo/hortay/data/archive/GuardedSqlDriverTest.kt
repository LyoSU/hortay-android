package dev.lyo.hortay.data.archive

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardedSqlDriverTest {

    private fun fresh() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ArchiveDatabase.Schema.create(it) }

    /** A failing open (a migration that throws) degrades to the fallback once, logged, without throwing. */
    @Test
    fun failingOpenServesTheFallback() {
        val failures = mutableListOf<Throwable>()
        var primaryOpens = 0
        val driver = GuardedSqlDriver(
            openPrimary = {
                primaryOpens++
                error("migration failed")
            },
            openFallback = ::fresh,
            onFailure = { failures += it },
        )
        val db = ArchiveDatabase(driver)

        assertEquals(0L, db.archivedMessageQueries.countAll().executeAsOne())
        assertFalse(driver.isAvailable)
        assertEquals(0L, db.archivedMessageQueries.countAll().executeAsOne())
        assertEquals(1, primaryOpens)
        assertEquals(listOf("migration failed"), failures.map { it.message })
    }

    /** A driver that constructs but cannot run its first statement counts as a failed open. */
    @Test
    fun failingFirstStatementServesTheFallback() {
        var failures = 0
        val driver = GuardedSqlDriver(
            openPrimary = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { it.close() } },
            openFallback = ::fresh,
            onFailure = { failures++ },
        )
        assertFalse(driver.isAvailable)
        assertEquals(1, failures)
    }

    @Test
    fun workingOpenIsAvailable_andListenersDoNotForceIt() {
        var opened = false
        val driver = GuardedSqlDriver(
            openPrimary = {
                opened = true
                fresh()
            },
            openFallback = ::fresh,
            onFailure = { error("unexpected") },
        )
        var notified = 0
        val listener = Query.Listener { notified++ }
        driver.addListener("ArchivedMessage", listener = listener)
        assertFalse(opened)

        driver.notifyListeners("ArchivedMessage")
        assertEquals(1, notified)
        assertTrue(driver.isAvailable)
        assertTrue(opened)
        driver.removeListener("ArchivedMessage", listener = listener)
        driver.notifyListeners("ArchivedMessage")
        assertEquals(1, notified)
    }
}
