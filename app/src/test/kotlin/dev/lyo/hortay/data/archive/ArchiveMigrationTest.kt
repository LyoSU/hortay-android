package dev.lyo.hortay.data.archive

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Schema 2 → 3 (2.sqm + [ArchiveMigrations.afterV2]) over a realistic v1 archive.
 *
 * The v1 fixture is written with the schema-2 DDL (PostSnapshot with captured_at_ms,
 * ArchivedMediaFile with ref_count): 0.12.0's schema 1 after 1.sqm. Schema 2 never shipped
 * on its own; the real 1 → 3 upgrade from a 0.12.0 file is [ArchiveUpgradeFrom012Test].
 * Migrated exactly as the Android driver does: the `.sqm` statements and the
 * AfterVersion(2) callback in one transaction. Runs on the JDBC driver's bundled SQLite (version printed by
 * [jdbcSqliteVersion]), which is newer than Android 8's 3.18 — the SQL itself sticks to
 * 3.18 features (no window functions, no JSON1, no upsert, no RETURNING).
 */
class ArchiveMigrationTest {

    private data class MessageRow(
        val key: String,
        val album: String?,
        val isComment: Long,
        val publishedAt: Long?,
        val firstCaptured: Long,
        val lastEvent: Long,
        val deletedAt: Long?,
    )

    private data class VersionRow(
        val key: String,
        val sortAt: Long,
        val editDate: Long?,
        val observedAt: Long,
        val origin: String,
        val kind: String,
        val hash: String,
        val hashVersion: Long,
        val mediaSha: String?,
    )

    private fun legacy(
        d: SqlDriver,
        key: String,
        kind: String,
        seenAt: Long,
        editedAt: Long?,
        captured: Long,
        blob: ByteArray = byteArrayOf(0),
        hash: String = "h-$key-$seenAt",
        album: String? = null,
        deletedKeys: String? = null,
        isComment: Long = 0,
        sourceKind: String = "TDLIB",
        sourceKey: String = "42",
        contentKind: String = if (kind == "DELETED") "marker" else if (sourceKind == "WEB") "web" else "tdlib",
        text: String = "",
    ) {
        d.execute(
            null,
            "INSERT INTO PostSnapshot(source_kind, source_key, message_key, album_key, kind, seen_at_ms, " +
                "edited_at_ms, content_kind, content_blob, content_hash, text_preview, media_minithumb, " +
                "deleted_msg_keys, is_comment, captured_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,?)",
            14,
        ) {
            bindString(0, sourceKind)
            bindString(1, sourceKey)
            bindString(2, key)
            bindString(3, album)
            bindString(4, kind)
            bindLong(5, seenAt)
            bindLong(6, editedAt)
            bindString(7, contentKind)
            bindBytes(8, blob)
            bindString(9, hash)
            bindString(10, text)
            bindString(11, deletedKeys)
            bindLong(12, isComment)
            bindLong(13, captured)
        }
    }

    private fun blob(text: String, sha: String? = null): ByteArray = ContentBlobCodec.encode(
        TdlibContentMeta(
            text = text, entitiesJson = "[]", mediaSummaryJson = null, pollJson = null,
            forwardJson = null, replyJson = null,
            mediaRef = sha?.let { ArchivedMediaRef(type = "photo", uniqueId = "u-$it", localArchiveSha = it) },
        ),
    )

    /** The v1 archive: one case per row group, keys named by what they cover. */
    private fun v1Fixture(d: SqlDriver) {
        for (stmt in V2_DDL) d.execute(null, stmt, 0)
        // Baseline only.
        legacy(d, "1", "VERSION", seenAt = 1_000, editedAt = null, captured = 50_000, blob = blob("one"))
        // Multi-edit: baseline + two live edits (seen = device clock, edited = Telegram's date).
        legacy(d, "2", "VERSION", seenAt = 1_000, editedAt = null, captured = 60_000, blob = blob("v1"))
        legacy(d, "2", "VERSION", seenAt = 70_000, editedAt = 65_000, captured = 70_000, blob = blob("v2"))
        legacy(d, "2", "VERSION", seenAt = 80_000, editedAt = 75_000, captured = 80_000, blob = blob("v3"))
        // Pre-edited baseline: seen = publication time, edited = the edit it was first seen with.
        legacy(d, "3", "VERSION", seenAt = 1_000, editedAt = 5_000, captured = 90_000, blob = blob("pre"))
        // captured_at_ms = 0: must count as captured now, not as ancient.
        legacy(d, "4", "VERSION", seenAt = 1_000, editedAt = null, captured = 0, blob = blob("zero"))
        // Undecodable blob.
        legacy(d, "6", "VERSION", seenAt = 2_000, editedAt = null, captured = 140_000, blob = byteArrayOf(1, 2, 3))
        // One media file shared by two messages (ref_count 2).
        legacy(d, "7", "VERSION", seenAt = 2_000, editedAt = null, captured = 150_000, blob = blob("a", sha = "shaX"))
        legacy(d, "8", "VERSION", seenAt = 2_000, editedAt = null, captured = 151_000, blob = blob("b", sha = "shaX"))
        // Edits stored out of order (an older edit date written after a newer one).
        legacy(d, "9", "VERSION", seenAt = 30_000, editedAt = 25_000, captured = 30_000, blob = blob("e1"))
        legacy(d, "9", "VERSION", seenAt = 40_000, editedAt = 24_000, captured = 40_000, blob = blob("e0"))
        // Album: two archived members, deleted together with a third that never was;
        // the DELETED marker is anchored at "10" and names all three in JSON.
        legacy(d, "10", "VERSION", seenAt = 2_000, editedAt = null, captured = 100_000, blob = blob(""), album = "alb")
        legacy(d, "11", "VERSION", seenAt = 2_000, editedAt = null, captured = 100_001, blob = blob("cap"), album = "alb")
        legacy(
            d, "10", "DELETED", seenAt = 200_000, editedAt = null, captured = 200_000,
            album = "alb", deletedKeys = """["10","11","12"]""",
        )
        // Deletion of a message the archive never held.
        legacy(d, "99", "DELETED", seenAt = 210_000, editedAt = null, captured = 210_000, deletedKeys = """["99"]""")
        // Guest mode: two replaced versions.
        legacy(d, "5", "VERSION", seenAt = 3_000, editedAt = null, captured = 3_000, sourceKind = "WEB", sourceKey = "demo")
        legacy(d, "5", "VERSION", seenAt = 4_000, editedAt = null, captured = 4_000, sourceKind = "WEB", sourceKey = "demo")
        // A comment edit (first version is an edit, seen > edited) and its composite delete.
        legacy(
            d, "200", "VERSION", seenAt = 120_000, editedAt = 110_000, captured = 120_000,
            blob = blob("comment"), isComment = 1, sourceKey = "99",
        )
        legacy(
            d, "200", "DELETED", seenAt = 130_000, editedAt = null, captured = 130_000,
            deletedKeys = """["200","201"]""", isComment = 1, sourceKey = "99",
        )
        d.execute(null, "INSERT INTO ArchivedMediaFile VALUES ('shaX', '/m/shaX', 10, 'image/jpeg', 5000, 2)", 0)
        d.execute(null, "INSERT INTO ArchivedChannel VALUES ('TDLIB', '42', 'Chan', 'chan', NULL, 1, 9000)", 0)
    }

    private fun migrate(driver: SqlDriver) {
        ArchiveDatabase(driver).transaction {
            ArchiveDatabase.Schema.migrate(driver, 2L, ArchiveDatabase.Schema.version, ArchiveMigrations.afterV2)
        }
    }

    private fun messages(d: SqlDriver): List<MessageRow> = rows(
        d,
        "SELECT source_kind || ':' || source_key || ':' || message_key, album_key, is_comment, published_at_ms, " +
            "first_captured_at_ms, last_event_at_ms, deleted_at_ms FROM ArchivedMessage " +
            "ORDER BY source_kind, source_key, CAST(message_key AS INTEGER)",
    ) { c ->
        MessageRow(c.getString(0)!!, c.getString(1), c.getLong(2)!!, c.getLong(3), c.getLong(4)!!, c.getLong(5)!!, c.getLong(6))
    }

    private fun versions(d: SqlDriver): List<VersionRow> = rows(
        d,
        "SELECT m.source_kind || ':' || m.source_key || ':' || m.message_key, v.sort_at_ms, v.edit_date_ms, " +
            "v.observed_at_ms, v.origin, v.content_kind, v.content_hash, v.hash_version, v.media_sha " +
            "FROM MessageVersion v JOIN ArchivedMessage m ON m.id = v.message_id " +
            "ORDER BY m.source_kind, m.source_key, CAST(m.message_key AS INTEGER), v.sort_at_ms, v.id",
    ) { c ->
        VersionRow(
            c.getString(0)!!, c.getLong(1)!!, c.getLong(2), c.getLong(3)!!, c.getString(4)!!,
            c.getString(5)!!, c.getString(6)!!, c.getLong(7)!!, c.getString(8),
        )
    }

    @Test
    fun v1ArchiveMigratesRowByRow_andThePostStepIsIdempotent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1Fixture(driver)
        val before = System.currentTimeMillis()
        migrate(driver)

        val msgs = messages(driver)
        // captured_at_ms = 0 → stamped with the migration time, never treated as ancient.
        val zero = msgs.single { it.key == "TDLIB:42:4" }
        assertTrue(zero.lastEvent >= before - 1_000L, "lastEvent=${zero.lastEvent} before=$before")
        assertEquals(
            listOf(
                MessageRow("TDLIB:42:1", null, 0, 1_000, 50_000, 50_000, null),
                MessageRow("TDLIB:42:2", null, 0, 1_000, 60_000, 80_000, null),
                MessageRow("TDLIB:42:3", null, 0, 1_000, 90_000, 90_000, null),
                MessageRow("TDLIB:42:4", null, 0, 1_000, 1_000, zero.lastEvent, null),
                MessageRow("TDLIB:42:6", null, 0, 2_000, 140_000, 140_000, null),
                MessageRow("TDLIB:42:7", null, 0, 2_000, 150_000, 150_000, null),
                MessageRow("TDLIB:42:8", null, 0, 2_000, 151_000, 151_000, null),
                // First row is an edit (seen after edited): no publication time.
                MessageRow("TDLIB:42:9", null, 0, null, 30_000, 40_000, null),
                MessageRow("TDLIB:42:10", "alb", 0, 2_000, 100_000, 200_000, 200_000),
                MessageRow("TDLIB:42:11", "alb", 0, 2_000, 100_001, 200_000, 200_000),
                // "12" and "99" were only ever named by deletions: no message.
                MessageRow("TDLIB:99:200", null, 1, null, 120_000, 130_000, 130_000),
                MessageRow("WEB:demo:5", null, 0, null, 3_000, 4_000, null),
            ),
            msgs,
        )

        fun h(key: String, seen: Long) = "h-$key-$seen"
        assertEquals(
            listOf(
                VersionRow("TDLIB:42:1", 1_000, null, 50_000, "PUBLISHED", "tdlib", h("1", 1_000), 1, null),
                VersionRow("TDLIB:42:2", 1_000, null, 60_000, "PUBLISHED", "tdlib", h("2", 1_000), 1, null),
                VersionRow("TDLIB:42:2", 65_000, 65_000, 70_000, "LIVE_EDIT", "tdlib", h("2", 70_000), 1, null),
                VersionRow("TDLIB:42:2", 75_000, 75_000, 80_000, "LIVE_EDIT", "tdlib", h("2", 80_000), 1, null),
                VersionRow("TDLIB:42:3", 5_000, 5_000, 90_000, "FIRST_SEEN_EDITED", "tdlib", h("3", 1_000), 1, null),
                // captured_at_ms = 0: observed falls back to seen_at_ms.
                VersionRow("TDLIB:42:4", 1_000, null, 1_000, "PUBLISHED", "tdlib", h("4", 1_000), 1, null),
                VersionRow("TDLIB:42:6", 2_000, null, 140_000, "PUBLISHED", "tdlib", h("6", 2_000), 1, null),
                VersionRow("TDLIB:42:7", 2_000, null, 150_000, "PUBLISHED", "tdlib", h("7", 2_000), 1, "shaX"),
                VersionRow("TDLIB:42:8", 2_000, null, 151_000, "PUBLISHED", "tdlib", h("8", 2_000), 1, "shaX"),
                VersionRow("TDLIB:42:9", 25_000, 25_000, 30_000, "FIRST_SEEN_EDITED", "tdlib", h("9", 30_000), 1, null),
                // Out-of-order edit: lifted to the running maximum, kept after the newer one.
                VersionRow("TDLIB:42:9", 25_000, 24_000, 40_000, "LIVE_EDIT", "tdlib", h("9", 40_000), 1, null),
                VersionRow("TDLIB:42:10", 2_000, null, 100_000, "PUBLISHED", "tdlib", h("10", 2_000), 1, null),
                VersionRow("TDLIB:42:11", 2_000, null, 100_001, "PUBLISHED", "tdlib", h("11", 2_000), 1, null),
                VersionRow("TDLIB:99:200", 110_000, 110_000, 120_000, "FIRST_SEEN_EDITED", "tdlib", h("200", 120_000), 1, null),
                VersionRow("WEB:demo:5", 3_000, null, 3_000, "WEB", "web", h("5", 3_000), 1, null),
                VersionRow("WEB:demo:5", 4_000, null, 4_000, "WEB", "web", h("5", 4_000), 1, null),
            ),
            versions(driver),
        )

        // Media: ref_count dropped, the file claimed at its creation time.
        assertEquals(
            listOf("shaX|/m/shaX|10|5000|5000"),
            rows(driver, "SELECT sha || '|' || path || '|' || size_bytes || '|' || created_at_ms || '|' || claimed_at_ms FROM ArchivedMediaFile") {
                it.getString(0)!!
            },
        )
        assertEquals(1L, count(driver, "SELECT COUNT(*) FROM ArchivedChannel"))
        // v1 rows kept untouched for one release, without their indexes.
        assertEquals(19L, count(driver, "SELECT COUNT(*) FROM PostSnapshotLegacy"))
        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_PostSnapshot%'"))

        // The Kotlin post-step again: nothing changes.
        val snapshot = messages(driver) to versions(driver)
        ArchiveMigrations.extractMediaShas(driver)
        ArchiveMigrations.extractMediaShas(driver)
        assertEquals(snapshot, messages(driver) to versions(driver))
    }

    /** The migrated schema is the one a fresh install creates (plus the legacy table). */
    @Test
    fun migratedSchemaMatchesAFreshOne() {
        val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { v1Fixture(it); migrate(it) }
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ArchiveDatabase.Schema.create(it) }
        for (table in listOf("ArchivedMessage", "MessageVersion", "ArchivedMediaFile", "ArchivedChannel")) {
            assertEquals(columns(fresh, table), columns(migrated, table), table)
        }
        assertEquals(indexes(fresh), indexes(migrated))
    }

    /** The migrated archive works through the app's own read paths. */
    @Test
    fun migratedArchiveReadsThroughTheRepository() = kotlinx.coroutines.test.runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { v1Fixture(it); migrate(it) }
        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(
            db, kotlinx.coroutines.flow.MutableStateFlow(ArchiveSettings(enabled = true)), clock = { 300_000L },
        )
        val chat = ChatRef.tdlib(42)

        val history = repo.observeRevisions(chat, "2").first()
        assertEquals(listOf("v1", "v2", "v3"), history.map { (it.content as ArchivedContent.Tdlib).meta.text })
        assertEquals(VersionOrigin.PUBLISHED, history.first().origin)

        val tombstone = repo.observeTdlibTombstones().first().single()
        assertEquals(10L, tombstone.primaryMessageId)
        assertEquals("cap", tombstone.text)

        val entries = repo.observeEntries().first()
        // Deletions (the album as one entry) and messages with history; baselines are not listed.
        assertEquals(
            setOf("TDLIB:42:10", "TDLIB:99:200", "TDLIB:42:2", "TDLIB:42:9", "WEB:demo:5"),
            entries.map { it.key }.toSet(),
        )
        assertEquals(5, entries.size)
        // A capture after the upgrade: hash_version 1 rows are re-hashed, not mistaken for an edit.
        val same = repo.captureTdlibEdit(chat, "1", null, editedAtMs = 400_000L, meta = testMeta("one"))
        assertEquals(ArchiveDecision.DUP_HASH, same.decision)
        assertEquals(DETAIL_DUP_REHASH, same.detail)
    }

    /** "Delete archive" / logout wipes the v1 rows 2.sqm kept, not only the v3 tables. */
    @Test
    fun clearDropsTheLegacyTableOfAMigratedArchive() = kotlinx.coroutines.test.runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { v1Fixture(it); migrate(it) }
        val repo = ArchiveRepository(
            ArchiveDatabase(driver), kotlinx.coroutines.flow.MutableStateFlow(ArchiveSettings(enabled = true)),
        )

        repo.clear()

        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'PostSnapshotLegacy'"))
        for (table in listOf("ArchivedMessage", "MessageVersion", "ArchivedChannel")) {
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM $table"), table)
        }
        // A second wipe (no legacy table any more) still succeeds.
        repo.clear()
    }

    @Test
    fun clearOnAFreshArchiveWithoutTheLegacyTable() = kotlinx.coroutines.test.runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ArchiveDatabase.Schema.create(it) }
        val repo = ArchiveRepository(
            ArchiveDatabase(driver), kotlinx.coroutines.flow.MutableStateFlow(ArchiveSettings(enabled = true)),
        )
        repo.captureTdlibBaseline(ChatRef.tdlib(42), "1", null, testMeta("one"), originalDateMs = 1L)

        repo.clear()

        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM ArchivedMessage"))
        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'PostSnapshotLegacy'"))
    }

    /** The migration-only legacy index never survives into the migrated schema. */
    @Test
    fun migrationDropsItsTemporaryLegacyIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { v1Fixture(it); migrate(it) }
        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE tbl_name = 'PostSnapshotLegacy' AND type = 'index'"))
    }

    @Test
    fun jdbcSqliteVersion() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val version = rows(driver, "SELECT sqlite_version()") { it.getString(0)!! }.single()
        println("JDBC SQLite version: $version")
        assertTrue(version.isNotEmpty())
    }

    private fun <T> rows(d: SqlDriver, sql: String, map: (SqlCursor) -> T): List<T> =
        d.executeQuery(null, sql, { c ->
            val out = ArrayList<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        }, 0).value

    private fun count(d: SqlDriver, sql: String): Long = rows(d, sql) { it.getLong(0)!! }.single()

    private fun columns(d: SqlDriver, table: String): List<String> =
        rows(d, "PRAGMA table_info($table)") { c ->
            "${c.getString(1)}:${c.getString(2)}:${c.getLong(3)}:${c.getLong(5)}"
        }.sorted()

    private fun indexes(d: SqlDriver): List<String> =
        rows(
            d,
            "SELECT tbl_name || '.' || name FROM sqlite_master WHERE type = 'index' " +
                "AND name NOT LIKE 'sqlite_autoindex%' AND tbl_name <> 'PostSnapshotLegacy'",
        ) { it.getString(0)!! }.sorted()

    private companion object {
        /** Schema 2: 0.12.0's PostSnapshot.sq after 1.sqm, ArchivedMediaFile.sq, ArchivedChannel.sq. */
        val V2_DDL = listOf(
            """
            CREATE TABLE PostSnapshot (
                id INTEGER PRIMARY KEY AUTOINCREMENT, source_kind TEXT NOT NULL, source_key TEXT NOT NULL,
                message_key TEXT NOT NULL, album_key TEXT, kind TEXT NOT NULL, seen_at_ms INTEGER NOT NULL,
                edited_at_ms INTEGER, content_kind TEXT NOT NULL, content_blob BLOB NOT NULL,
                content_hash TEXT NOT NULL, text_preview TEXT NOT NULL DEFAULT '', media_minithumb BLOB,
                deleted_msg_keys TEXT, is_comment INTEGER NOT NULL DEFAULT 0,
                captured_at_ms INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX idx_PostSnapshot_msg ON PostSnapshot(source_kind, source_key, message_key, seen_at_ms DESC)",
            "CREATE INDEX idx_PostSnapshot_seen ON PostSnapshot(seen_at_ms DESC)",
            "CREATE INDEX idx_PostSnapshot_id ON PostSnapshot(id)",
            "CREATE INDEX idx_PostSnapshot_captured ON PostSnapshot(captured_at_ms)",
            """
            CREATE TABLE ArchivedMediaFile (
                sha TEXT PRIMARY KEY, path TEXT NOT NULL, size_bytes INTEGER NOT NULL, mime_type TEXT,
                created_at_ms INTEGER NOT NULL, ref_count INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX idx_ArchivedMediaFile_refCount ON ArchivedMediaFile(ref_count)",
            """
            CREATE TABLE ArchivedChannel (
                source_kind TEXT NOT NULL, source_key TEXT NOT NULL, title TEXT NOT NULL, handle TEXT,
                photo_minithumb BLOB, is_verified INTEGER NOT NULL DEFAULT 0,
                last_snapshot_at_ms INTEGER NOT NULL, PRIMARY KEY (source_kind, source_key)
            )
            """,
        )
    }
}
