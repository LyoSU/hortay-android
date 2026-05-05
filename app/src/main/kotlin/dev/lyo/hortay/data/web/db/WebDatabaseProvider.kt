package dev.lyo.hortay.data.web.db

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

/**
 * Process-wide construction site for the anonymous-mode database. Owns the
 * [AndroidSqliteDriver] (and therefore the underlying [SupportSQLiteOpenHelper])
 * for the entire app lifetime — opening/closing per call would lose connection-
 * pooled prepared statements and re-run the WAL pragma every time, which on
 * older devices stalls 50-100 ms.
 *
 * Why a thin provider class instead of constructing the driver inline in
 * [dev.lyo.hortay.AppGraph]:
 *   - The PRAGMA setup runs in [AndroidSqliteDriver.Callback.onOpen], not at
 *     `db.openHelper.writableDatabase` time. Bundling that callback alongside the
 *     driver factory keeps the lifecycle obvious.
 *   - Schema migration plumbing (verifyMigrations + future `<n>.sqm` files) lives
 *     here too — AppGraph stays a list of `val` declarations without intricate
 *     bootstrap code mixed in.
 *
 * Reliability defaults baked in:
 *   - `journal_mode = WAL` — concurrent reader-during-writer is the dominant
 *     usage shape (background sweep writes while UI flow re-reads). Without WAL,
 *     readers block on a writer for the full transaction.
 *   - `synchronous = NORMAL` — fsync only at WAL checkpoint, not on every commit.
 *     A device crash at the wrong moment loses the last few seconds of inserts.
 *     For a read-only-mirror cache that's recovered on the next sweep this is
 *     the correct trade. Don't drop further to OFF — that risks losing the WAL
 *     entirely and corrupting the file.
 *   - `foreign_keys = ON` — without this, the FK from post → channel is purely
 *     advisory and ON DELETE CASCADE does nothing. SQLite ships with this OFF
 *     by default for backwards compatibility.
 *   - `temp_store = MEMORY` — the FTS5 ranking sort needs scratch space; pinning
 *     it to memory dodges the SD-card-backed temp file path on low-end devices.
 */
object WebDatabaseProvider {

    private const val TAG = "WebDatabase"
    const val FILE_NAME = "web.db"

    fun create(context: Context): WebDatabase {
        val driver: SqlDriver = AndroidSqliteDriver(
            schema = WebDatabase.Schema,
            context = context.applicationContext,
            name = FILE_NAME,
            callback = object : AndroidSqliteDriver.Callback(WebDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // PRAGMAs must run on every connection open — they're per-connection
                    // settings, not persisted in the DB file. AndroidSqliteDriver may
                    // open multiple connections under load; the callback fires on each.
                    //
                    // Important Android quirk: `PRAGMA journal_mode = WAL` returns the
                    // new mode as a single-row result, which means it must be issued
                    // via `query()` (consuming the cursor) — `execSQL()` blows up with
                    // "Queries can be performed using SQLiteDatabase query or rawQuery
                    // methods only". The setter-form PRAGMAs below return nothing and
                    // can use execSQL safely.
                    db.query("PRAGMA journal_mode = WAL").use { it.moveToFirst() }
                    db.execSQL("PRAGMA synchronous = NORMAL")
                    db.execSQL("PRAGMA foreign_keys = ON")
                    db.execSQL("PRAGMA temp_store = MEMORY")
                    Log.i(TAG, "web.db opened (WAL, FK on)")
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    // Default behaviour deletes the DB file silently. We log first so a
                    // corruption event isn't invisible — corrupt cache is recoverable
                    // (next sweep rebuilds), but a silent recurring delete would mask
                    // a real disk problem.
                    Log.e(TAG, "web.db corruption detected; rebuilding")
                    super.onCorruption(db)
                }
            },
        )
        return WebDatabase(driver)
    }
}
