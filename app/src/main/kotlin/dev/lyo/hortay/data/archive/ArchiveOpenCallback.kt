package dev.lyo.hortay.data.archive

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase

/**
 * The open-helper callback of `archive.db`: SQLDelight's create / upgrade (with the Kotlin
 * migration steps of [ArchiveMigrations]), and a corruption handler that does NOT delete
 * the file. The default one (`SupportSQLiteOpenHelper.Callback.onCorruption`) deletes the
 * database; the archive is the only copy of what it holds, so a corrupt file is left for
 * repair and the open fails into [GuardedSqlDriver]'s fallback instead.
 */
class ArchiveOpenCallback : AndroidSqliteDriver.Callback(ArchiveDatabase.Schema, *ArchiveMigrations.callbacks) {
    override fun onCorruption(db: SupportSQLiteDatabase) {
        Log.e(TAG, "archive.db reported corruption; leaving the file untouched")
    }

    private companion object {
        const val TAG = "ArchiveDb"
    }
}

/** `archive.db` behind a [GuardedSqlDriver]; see its KDoc for the failure contract. */
fun openArchiveDriver(context: Context): GuardedSqlDriver = GuardedSqlDriver(
    openPrimary = {
        AndroidSqliteDriver(
            schema = ArchiveDatabase.Schema,
            context = context,
            name = ARCHIVE_DB_NAME,
            callback = ArchiveOpenCallback(),
        )
    },
    openFallback = { AndroidSqliteDriver(schema = ArchiveDatabase.Schema, context = context, name = null) },
    onFailure = { e -> Log.e("ArchiveDb", "archive.db could not be opened; the archive is off for this run", e) },
)

const val ARCHIVE_DB_NAME = "archive.db"
