package dev.lyo.hortay.data.archive

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The upgrade every 0.12.0 user runs: an `archive.db` at schema 1 — exactly as v0.8.0 to
 * v0.12.0 shipped it (no `.sqm` existed, so `user_version` = 1) — opened by the current
 * build, which runs 1.sqm, 2.sqm and [ArchiveMigrations.afterV2] to reach schema 3.
 *
 * [ArchiveMigrationTest] covers 2.sqm in depth from a schema-2 fixture; schema 2 itself
 * never shipped, so this class starts one step earlier, from the DDL copied verbatim from
 * the `v0.12.0` tag ([V012_DDL]), with rows shaped as 0.12.0's ArchiveRepository wrote them.
 *
 * The database is a real file opened the way `SupportSQLiteOpenHelper` opens it ([openAsDevice]:
 * read `user_version`, run the migration and set the new version in ONE transaction), behind
 * the production [GuardedSqlDriver]. The JVM driver's bundled SQLite is newer than Android
 * 8's 3.18; [migrationSqlUsesNothingNewerThanSqlite318] guards the syntax side of that gap.
 */
class ArchiveUpgradeFrom012Test {

    @TempDir
    lateinit var dir: File

    private val dbFile: File get() = File(dir, ARCHIVE_DB_NAME)

    // --- The upgrade path ---

    @Test
    fun v012ArchiveUpgradesInPlace_withoutLosingAnything() = runTest {
        val legacyBefore = withFile { d -> writeV012Archive(d); legacyRows(d, "PostSnapshot") }
        val before = System.currentTimeMillis()

        val failures = mutableListOf<Throwable>()
        val driver = guarded(failures)
        assertTrue(driver.isAvailable, "failures=$failures")
        assertTrue(failures.isEmpty())
        val db = ArchiveDatabase(driver)
        assertEquals(ArchiveDatabase.Schema.version, userVersion(driver))

        // Every key with a VERSION row became one message; "999" (named only by a deletion) did not.
        assertEquals(
            listOf("TDLIB:$CHAN:500", "TDLIB:$CHAN:501", "TDLIB:$CHAN:502", "TDLIB:$CHAN:510", "TDLIB:$CHAN:511",
                "TDLIB:$CHAN:512", "TDLIB:$GROUP:900", "WEB:durov:321"),
            strings(driver, "SELECT source_kind || ':' || source_key || ':' || message_key FROM ArchivedMessage ORDER BY id"),
        )
        // One version per v1 VERSION row, none for the DELETED markers.
        assertEquals(11L, long(driver, "SELECT COUNT(*) FROM MessageVersion"))

        // Edited post: versions in their v1 order, origins and sort keys as 2.sqm maps them.
        val edited = db.versionsOf(chanA, "500")
        assertEquals(listOf(TEXT_SPECIAL, "v2 50% off_", "v3 it's \"final\""), edited.map { it.text() })
        assertEquals(listOf("PUBLISHED", "LIVE_EDIT", "LIVE_EDIT"), edited.map { it.origin })
        assertEquals(listOf(PUB, PUB + 3_590_000, PUB + 7_100_000), edited.map { it.sort_at_ms })
        assertEquals(listOf(null, PUB + 3_590_000, PUB + 7_100_000), edited.map { it.edit_date_ms })
        assertEquals(edited.map { it.sort_at_ms }.sorted(), edited.map { it.sort_at_ms })
        // Text with %, _, both quote kinds and a backslash survives byte for byte.
        assertEquals(TEXT_SPECIAL, edited.first().text_preview)
        val m500 = db.messageOf(chanA, "500")!!
        assertEquals(PUB, m500.published_at_ms)
        assertNull(m500.album_key)
        assertNull(m500.deleted_at_ms)
        // 1.sqm stamped every row "captured now": retention counts from the upgrade.
        assertTrue(m500.last_event_at_ms >= before - 1_000, "lastEvent=${m500.last_event_at_ms}")

        // Pre-edited baseline: publication time from seen_at_ms, sorted at its edit date.
        val preEdited = db.versionsOf(chanA, "501").single()
        assertEquals("FIRST_SEEN_EDITED", preEdited.origin)
        assertEquals(PUB + 120_000, preEdited.sort_at_ms)
        assertEquals(PUB + 60_000, db.messageOf(chanA, "501")!!.published_at_ms)

        // Standalone deletion: deleted state kept, the version's nulls and thumbnail intact.
        val m502 = db.messageOf(chanA, "502")!!
        assertEquals(PUB + 9_000_000, m502.deleted_at_ms)
        assertTrue(m502.last_event_at_ms >= m502.deleted_at_ms!!)
        val v502 = db.versionsOf(chanA, "502").single()
        assertNull(v502.edit_date_ms)
        assertNull(v502.media_sha)
        assertArrayEquals(THUMB_502, v502.media_minithumb)

        // Album: every member deleted with the album's marker, album key kept, media sha lifted
        // out of the blob into the column the orphan sweep honours.
        for ((key, sha) in listOf("510" to "shaA", "511" to "shaB", "512" to "shaC")) {
            val m = db.messageOf(chanA, key)!!
            assertEquals(ALBUM, m.album_key, key)
            assertEquals(PUB + 10_000_000, m.deleted_at_ms, key)
            val v = db.versionsOf(chanA, key).single()
            assertEquals(sha, v.media_sha, key)
            assertArrayEquals(byteArrayOf(key.last().code.toByte()), v.media_minithumb, key)
        }

        // Comment whose first row is an edit: no publication time, comment flag kept.
        val comment = db.messageOf(ChatRef.tdlib(GROUP), "900")!!
        assertEquals(1L, comment.is_comment)
        assertNull(comment.published_at_ms)
        assertEquals("FIRST_SEEN_EDITED", db.versionsOf(ChatRef.tdlib(GROUP), "900").single().origin)

        // Guest mode: both versions, WEB origin, sorted by capture time.
        val web = db.versionsOf(webChat, "321")
        assertEquals(listOf("WEB", "WEB"), web.map { it.origin })
        assertEquals(listOf(PUB + 1_000, PUB + 2_000), web.map { it.sort_at_ms })

        // Media index: ref_count gone, every file kept and claimed at its creation time; only the
        // file v1 already held no reference to is an orphan for the sweep.
        assertEquals(
            listOf("shaA|1000", "shaB|1001", "shaC|1002", "shaOrphan|1003"),
            strings(driver, "SELECT sha || '|' || claimed_at_ms FROM ArchivedMediaFile ORDER BY sha"),
        )
        assertEquals(
            listOf("shaOrphan"),
            db.archivedMediaFileQueries.selectOrphans(Long.MAX_VALUE).executeAsList().map { it.sha },
        )
        assertEquals(CHANNEL_TITLE, db.archivedChannelQueries.selectOne("TDLIB", CHAN.toString()).executeAsOne().title)

        // PostSnapshotLegacy holds the 0.12.0 rows untouched (plus 1.sqm's captured_at_ms column).
        assertEquals(legacyBefore, legacyRows(driver, "PostSnapshotLegacy"))
        assertEquals(schemaOf(freshSchema()), schemaOf(driver))

        // The app's own read paths over the migrated file.
        val repo = ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)))
        val tombstones = repo.observeTdlibTombstones().first()
        assertEquals(2, tombstones.size)
        val album = tombstones.single { it.primaryMessageId == 510L }
        assertEquals(listOf(510L, 511L, 512L), album.allMessageIds)
        assertEquals(ALBUM_CAPTION, album.text)
        assertEquals(PUB + 240_000, album.originalSeenAtMs)
        assertEquals(CHANNEL_TITLE, album.channelTitle)
        assertEquals("clip", tombstones.single { it.primaryMessageId == 502L }.text)
        assertEquals(mapOf((CHAN to 500L) to 2), repo.observeTdlibRevisionCounts().first())
        val entries = repo.observeEntries().first()
        assertEquals(4, entries.size, entries.map { it.key }.toString())
        assertTrue(entries.any { it.key == "TDLIB:$CHAN:500" })
        assertTrue(entries.any { it.key == "TDLIB:$CHAN:502" })
        assertTrue(entries.any { it.key == "WEB:durov:321" })
        assertTrue(entries.any { it.messageKey in setOf("510", "511", "512") })
        assertEquals(
            "<b>50%</b> off_now \"x\"",
            (repo.observeRevisions(webChat, "321").first().first().content as ArchivedContent.Web).post.textHtml,
        )

        // First foreground sweep after the upgrade (default 30 days / 5000 records): re-hashes
        // the hash_version 1 rows and removes nothing.
        ArchiveSweep(db, MutableStateFlow(ArchiveSettings(enabled = true))).run()
        assertEquals(8L, db.countMessages())
        assertEquals(11L, long(driver, "SELECT COUNT(*) FROM MessageVersion"))
        assertEquals(
            0L,
            long(driver, "SELECT COUNT(*) FROM MessageVersion WHERE content_kind = 'tdlib' AND hash_version < ${ContentNormalizer.HASH_VERSION}"),
        )

        // A live edit after the upgrade appends after the migrated history.
        val outcome = repo.captureTdlibEdit(chanA, "500", null, editedAtMs = PUB + 8_000_000, meta = testMeta("v4"))
        assertEquals(ArchiveDecision.WRITTEN, outcome.decision)
        assertEquals(listOf(TEXT_SPECIAL, "v2 50% off_", "v3 it's \"final\"", "v4"), db.textsOf(chanA, "500"))
        driver.close()
    }

    @Test
    fun emptyV012ArchiveUpgrades() = runTest {
        withFile { d -> for (stmt in V012_DDL) d.execute(null, stmt, 0) }
        val driver = guarded()
        assertTrue(driver.isAvailable)
        assertEquals(ArchiveDatabase.Schema.version, userVersion(driver))
        for (table in listOf("ArchivedMessage", "MessageVersion", "ArchivedMediaFile", "ArchivedChannel", "PostSnapshotLegacy")) {
            assertEquals(0L, long(driver, "SELECT COUNT(*) FROM $table"), table)
        }
        assertEquals(schemaOf(freshSchema()), schemaOf(driver))

        val db = ArchiveDatabase(driver)
        val repo = ArchiveRepository(db, MutableStateFlow(ArchiveSettings(enabled = true)))
        assertTrue(repo.observeEntries().first().isEmpty())
        assertEquals(
            ArchiveDecision.WRITTEN,
            repo.captureTdlibEdit(chanA, "1", null, editedAtMs = PUB, meta = testMeta("first")).decision,
        )
        repo.clear()
        assertEquals(0L, long(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'PostSnapshotLegacy'"))
        driver.close()
    }

    /** A migration that throws after all its SQL ran: rolled back whole, file kept, archive off. */
    @Test
    fun failingKotlinStep_rollsBackToSchema1_andTheNextLaunchUpgrades() {
        val legacyBefore = withFile { d -> writeV012Archive(d); legacyRows(d, "PostSnapshot") }
        val crash = AfterVersion(2) { error("simulated crash in the Kotlin step") }

        val failures = mutableListOf<Throwable>()
        val broken = guarded(failures, *ArchiveMigrations.callbacks, crash)
        assertFalse(broken.isAvailable)
        assertEquals(listOf("simulated crash in the Kotlin step"), failures.map { it.message })
        // The stand-in serves an empty archive at the current schema.
        assertEquals(0L, ArchiveDatabase(broken).archivedMessageQueries.countAll().executeAsOne())
        broken.close()

        assertUntouchedV012File(legacyBefore)

        val retried = guarded()
        assertTrue(retried.isAvailable)
        assertEquals(8L, ArchiveDatabase(retried).countMessages())
        retried.close()
    }

    /** A statement failing in the middle of 2.sqm (after the rename and the copies). */
    @Test
    fun failingSqlMidMigration_rollsBackToSchema1() {
        val legacyBefore = withFile { d ->
            writeV012Archive(d)
            // 2.sqm's `CREATE TABLE ArchivedMediaFile_v3` now fails, well past its first statements.
            d.execute(null, "CREATE TABLE ArchivedMediaFile_v3 (x INTEGER)", 0)
            legacyRows(d, "PostSnapshot")
        }
        val failures = mutableListOf<Throwable>()
        val driver = guarded(failures)
        assertFalse(driver.isAvailable)
        assertEquals(1, failures.size)
        driver.close()

        assertUntouchedV012File(legacyBefore)
    }

    /**
     * Android 8.0 ships SQLite 3.18. The JVM tests run a newer one, so syntax added after 3.18
     * would pass here and fail on device; reject it in every archive statement.
     */
    @Test
    fun migrationSqlUsesNothingNewerThanSqlite318() {
        val root = File("src/main/sqldelight-archive")
        val files = root.walkTopDown().filter { it.extension == "sqm" || it.extension == "sq" }.toList()
        assertTrue(files.any { it.name == "2.sqm" }, "archive SQL not found under ${root.absolutePath}")
        val forbidden = mapOf(
            "upsert (3.24)" to Regex("""\bON\s+CONFLICT\b""", RegexOption.IGNORE_CASE),
            "RETURNING (3.35)" to Regex("""\bRETURNING\b""", RegexOption.IGNORE_CASE),
            "window functions (3.25)" to Regex("""\bOVER\s*\(|\bWINDOW\s+\w+\s+AS\b""", RegexOption.IGNORE_CASE),
            "FILTER clause (3.30)" to Regex("""\bFILTER\s*\(\s*WHERE\b""", RegexOption.IGNORE_CASE),
            "RENAME COLUMN (3.25)" to Regex("""\bRENAME\s+COLUMN\b""", RegexOption.IGNORE_CASE),
            "DROP COLUMN (3.35)" to Regex("""\bDROP\s+COLUMN\b""", RegexOption.IGNORE_CASE),
            "JSON1" to Regex("""\bjson\w*\s*\(""", RegexOption.IGNORE_CASE),
            "TRUE/FALSE literals (3.23)" to Regex("""\b(TRUE|FALSE)\b""", RegexOption.IGNORE_CASE),
            "NULLS FIRST/LAST (3.30)" to Regex("""\bNULLS\s+(FIRST|LAST)\b""", RegexOption.IGNORE_CASE),
            "generated columns (3.31)" to Regex("""\bGENERATED\s+ALWAYS\b""", RegexOption.IGNORE_CASE),
            "iif (3.32)" to Regex("""\biif\s*\(""", RegexOption.IGNORE_CASE),
            "unixepoch (3.38)" to Regex("""\bunixepoch\s*\(""", RegexOption.IGNORE_CASE),
            "-> / ->> (3.38)" to Regex("""->"""),
            "STRICT tables (3.37)" to Regex("""\)\s*STRICT\b""", RegexOption.IGNORE_CASE),
        )
        val hits = files.flatMap { f ->
            val sql = f.readLines().joinToString("\n") { it.substringBefore("--") }
            forbidden.filterValues { it.containsMatchIn(sql) }.keys.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    // --- Fixture: an archive as 0.12.0 wrote it ---

    private fun writeV012Archive(d: SqlDriver) {
        for (stmt in V012_DDL) d.execute(null, stmt, 0)
        // Edited post: baseline at publication time, two live edits at device time.
        v1(d, CHAN, "500", "VERSION", seenAt = PUB, editedAt = null, meta = meta(TEXT_SPECIAL))
        v1(d, CHAN, "500", "VERSION", seenAt = PUB + 3_600_000, editedAt = PUB + 3_590_000, meta = meta("v2 50% off_"))
        v1(d, CHAN, "500", "VERSION", seenAt = PUB + 7_200_000, editedAt = PUB + 7_100_000, meta = meta("v3 it's \"final\""))
        // Pre-edited baseline (first seen already edited).
        v1(d, CHAN, "501", "VERSION", seenAt = PUB + 60_000, editedAt = PUB + 120_000, meta = meta("pre-edited"))
        // A video post with a thumbnail but no archived copy, then deleted on its own.
        v1(
            d, CHAN, "502", "VERSION", seenAt = PUB + 180_000, editedAt = null, thumb = THUMB_502,
            meta = meta("clip", ArchivedMediaRef(type = "video", uniqueId = "u502", minithumbBytes = THUMB_502)),
        )
        v1Deleted(d, CHAN, listOf("502"), seenAt = PUB + 9_000_000, album = null)
        // A three-photo album, each member with an archived file, deleted together.
        for ((key, sha) in listOf("510" to "shaA", "511" to "shaB", "512" to "shaC")) {
            val thumb = byteArrayOf(key.last().code.toByte())
            v1(
                d, CHAN, key, "VERSION", seenAt = PUB + 240_000, editedAt = null, album = ALBUM, thumb = thumb,
                meta = meta(
                    if (key == "510") ALBUM_CAPTION else "",
                    ArchivedMediaRef(type = "photo", uniqueId = "u$key", minithumbBytes = thumb, localArchiveSha = sha),
                ),
            )
        }
        v1Deleted(d, CHAN, listOf("510", "511", "512"), seenAt = PUB + 10_000_000, album = ALBUM)
        // A deletion of a post the archive never held.
        v1Deleted(d, CHAN, listOf("999"), seenAt = PUB + 11_000_000, album = null)
        // A discussion comment archived on edit only.
        v1(d, GROUP, "900", "VERSION", seenAt = PUB + 500_000, editedAt = PUB + 400_000, meta = meta("comment"), isComment = 1)
        // Guest mode: two replaced versions.
        v1Web(d, "321", seenAt = PUB + 1_000, textHtml = "<b>50%</b> off_now \\\"x\\\"")
        v1Web(d, "321", seenAt = PUB + 2_000, textHtml = "second")

        for ((i, sha) in listOf("shaA", "shaB", "shaC", "shaOrphan").withIndex()) {
            val refs = if (sha == "shaOrphan") 0 else 1
            d.execute(null, "INSERT INTO ArchivedMediaFile VALUES ('$sha', '/files/archive_media/$sha', 2048, 'image/jpeg', ${1_000 + i}, $refs)", 0)
        }
        d.execute(null, "INSERT INTO ArchivedChannel VALUES ('TDLIB', ?, ?, 'chan_a', NULL, 1, ?)", 3) {
            bindString(0, CHAN.toString())
            bindString(1, CHANNEL_TITLE)
            bindLong(2, PUB)
        }
        d.execute(null, "INSERT INTO ArchivedChannel VALUES ('TDLIB', '$GROUP', 'Group', NULL, NULL, 0, $PUB)", 0)
        d.execute(null, "INSERT INTO ArchivedChannel VALUES ('WEB', 'durov', 'Durov', 'durov', NULL, 1, $PUB)", 0)
    }

    /** 0.12.0's `PostSnapshot.insert`. */
    @Suppress("LongParameterList")
    private fun v1Row(
        d: SqlDriver, sourceKind: String, sourceKey: String, key: String, album: String?, kind: String,
        seenAt: Long, editedAt: Long?, contentKind: String, blob: ByteArray, hash: String, preview: String,
        thumb: ByteArray?, deletedKeys: String?, isComment: Long,
    ) {
        d.execute(
            null,
            "INSERT INTO PostSnapshot(source_kind, source_key, message_key, album_key, kind, seen_at_ms, edited_at_ms, " +
                "content_kind, content_blob, content_hash, text_preview, media_minithumb, deleted_msg_keys, is_comment) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            bindString(10, preview)
            bindBytes(11, thumb)
            bindString(12, deletedKeys)
            bindLong(13, isComment)
        }
    }

    @Suppress("LongParameterList")
    private fun v1(
        d: SqlDriver, chat: Long, key: String, kind: String, seenAt: Long, editedAt: Long?, meta: TdlibContentMeta,
        album: String? = null, thumb: ByteArray? = null, isComment: Long = 0,
    ) = v1Row(
        d, "TDLIB", chat.toString(), key, album, kind, seenAt, editedAt, "tdlib", ContentBlobCodec.encode(meta),
        ContentNormalizer.canonicalHash(meta), meta.text.take(200), thumb ?: meta.mediaRef?.minithumbBytes, null, isComment,
    )

    /** 0.12.0's `captureTdlibDelete`: one marker anchored at the first key, all keys as JSON. */
    private fun v1Deleted(d: SqlDriver, chat: Long, keys: List<String>, seenAt: Long, album: String?) {
        val json = keys.joinToString(",", "[", "]") { "\"$it\"" }
        val marker = byteArrayOf(0)
        v1Row(
            d, "TDLIB", chat.toString(), keys.first(), album, "DELETED", seenAt, null, "marker", marker,
            ContentBlobCodec.hash(marker + json.toByteArray()), "", null, json, 0,
        )
    }

    /** 0.12.0's `captureWebVersion`: the replaced WebPost as UTF-8 JSON. */
    private fun v1Web(d: SqlDriver, key: String, seenAt: Long, textHtml: String) {
        val blob = ("{\"id\":\"durov/$key\",\"seq\":$key,\"publishedAt\":\"2026-05-28T10:00:00+00:00\"," +
            "\"textHtml\":\"$textHtml\",\"media\":[],\"reactions\":[]}").toByteArray(Charsets.UTF_8)
        v1Row(
            d, "WEB", "durov", key, null, "VERSION", seenAt, null, "web", blob, ContentBlobCodec.hash(blob),
            textHtml.replace(Regex("<[^>]+>"), ""), null, null, 0,
        )
    }

    private fun meta(text: String, media: ArchivedMediaRef? = null) = testMeta(text).copy(mediaRef = media)

    // --- Opening the file as the device does ---

    /**
     * `SupportSQLiteOpenHelper.getWritableDatabase` + `AndroidSqliteDriver.Callback` (the
     * production [ArchiveOpenCallback] passes [ArchiveMigrations.callbacks]): create or
     * migrate, then set `user_version`, all in one transaction; a throw rolls it all back.
     */
    private fun openAsDevice(vararg callbacks: AfterVersion): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            val target = ArchiveDatabase.Schema.version
            val current = userVersion(driver)
            check(current <= target) { "downgrade $current -> $target" }
            if (current < target) {
                ArchiveDatabase(driver).transaction {
                    if (current == 0L) {
                        ArchiveDatabase.Schema.create(driver)
                    } else {
                        ArchiveDatabase.Schema.migrate(driver, current, target, *callbacks)
                    }
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }
            }
        } catch (e: Throwable) {
            driver.close()
            throw e
        }
        return driver
    }

    /** As `openArchiveDriver`: the file behind [GuardedSqlDriver], an in-memory stand-in on failure. */
    private fun guarded(
        failures: MutableList<Throwable> = mutableListOf(),
        vararg callbacks: AfterVersion = ArchiveMigrations.callbacks,
    ) = GuardedSqlDriver(
        openPrimary = { openAsDevice(*callbacks) },
        openFallback = ::freshSchema,
        onFailure = { failures += it },
    )

    private fun freshSchema(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ArchiveDatabase.Schema.create(it) }

    /** Raw access to the file, closed afterwards; the file starts at `user_version` 1 like 0.12.0's. */
    private fun <T> withFile(block: (SqlDriver) -> T): T {
        val d = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            val out = block(d)
            if (userVersion(d) == 0L && long(d, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'PostSnapshot'") == 1L) {
                d.execute(null, "PRAGMA user_version = 1", 0)
            }
            return out
        } finally {
            d.close()
        }
    }

    private fun assertUntouchedV012File(legacyBefore: List<String>) {
        assertTrue(dbFile.length() > 0)
        withFile { d ->
            assertEquals(1L, userVersion(d))
            assertEquals(legacyBefore, legacyRows(d, "PostSnapshot"))
            // 1.sqm rolled back with 2.sqm; no schema-3 table exists.
            assertTrue(strings(d, "PRAGMA table_info(PostSnapshot)").isNotEmpty())
            assertEquals(0L, long(d, "SELECT COUNT(*) FROM pragma_table_info('PostSnapshot') WHERE name = 'captured_at_ms'"))
            assertEquals(
                0L,
                long(d, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('ArchivedMessage', 'MessageVersion', 'PostSnapshotLegacy')"),
            )
            assertEquals(4L, long(d, "SELECT COUNT(*) FROM ArchivedMediaFile WHERE ref_count IS NOT NULL"))
        }
    }

    // --- SQL helpers ---

    private fun userVersion(d: SqlDriver): Long = long(d, "PRAGMA user_version")

    /** Every 0.12.0 column of every row, blobs as hex, in id order. */
    private fun legacyRows(d: SqlDriver, table: String): List<String> = strings(
        d,
        "SELECT id || '|' || source_kind || '|' || source_key || '|' || message_key || '|' || " +
            "COALESCE(album_key, '<null>') || '|' || kind || '|' || seen_at_ms || '|' || " +
            "COALESCE(edited_at_ms, '<null>') || '|' || content_kind || '|' || hex(content_blob) || '|' || " +
            "content_hash || '|' || text_preview || '|' || COALESCE(hex(media_minithumb), '<null>') || '|' || " +
            "COALESCE(deleted_msg_keys, '<null>') || '|' || is_comment FROM $table ORDER BY id",
    )

    /** Columns of the app's tables and their indexes, as the schema comparison sees them. */
    private fun schemaOf(d: SqlDriver): List<String> {
        val tables = listOf("ArchivedMessage", "MessageVersion", "ArchivedMediaFile", "ArchivedChannel")
        val columns = tables.flatMap { t ->
            rows(d, "PRAGMA table_info($t)") { c -> "$t.${c.getString(1)}:${c.getString(2)}:${c.getLong(3)}:${c.getLong(5)}" }
        }
        val indexes = strings(
            d,
            "SELECT tbl_name || '.' || name FROM sqlite_master WHERE type = 'index' " +
                "AND name NOT LIKE 'sqlite_autoindex%' AND tbl_name <> 'PostSnapshotLegacy'",
        )
        return (columns + indexes).sorted()
    }

    private fun <T> rows(d: SqlDriver, sql: String, map: (SqlCursor) -> T): List<T> =
        d.executeQuery(null, sql, { c ->
            val out = ArrayList<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        }, 0).value

    private fun strings(d: SqlDriver, sql: String): List<String> = rows(d, sql) { it.getString(0)!! }

    private fun long(d: SqlDriver, sql: String): Long = rows(d, sql) { it.getLong(0)!! }.single()

    private companion object {
        const val CHAN = -1001234567890L
        const val GROUP = -1009876543210L
        const val PUB = 1_780_000_000_000L
        const val ALBUM = "13579246801357"
        const val TEXT_SPECIAL = "Sale: 100% off_today — \"quoted\", 'single' and a \\ backslash"
        const val ALBUM_CAPTION = "Album caption 50%_"
        const val CHANNEL_TITLE = "Chan \"A\" 100%_off"
        val THUMB_502 = byteArrayOf(9, 8, 7)
        val chanA = ChatRef.tdlib(CHAN)
        val webChat = ChatRef.web("durov")

        /**
         * Schema 1, verbatim from `git show v0.12.0:app/src/main/sqldelight-archive/.../db/{PostSnapshot,
         * ArchivedMediaFile,ArchivedChannel}.sq` (identical from v0.8.0, where the archive first
         * shipped, to v0.12.0; no `.sqm` existed, so those databases carry `user_version` = 1).
         */
        val V012_DDL = listOf(
            """
            CREATE TABLE ArchivedChannel (
                source_kind         TEXT NOT NULL,
                source_key          TEXT NOT NULL,
                title               TEXT NOT NULL,
                handle              TEXT,
                photo_minithumb     BLOB,
                is_verified         INTEGER NOT NULL DEFAULT 0,
                last_snapshot_at_ms INTEGER NOT NULL,
                PRIMARY KEY (source_kind, source_key)
            )
            """,
            """
            CREATE TABLE ArchivedMediaFile (
                sha             TEXT PRIMARY KEY,
                path            TEXT NOT NULL,
                size_bytes      INTEGER NOT NULL,
                mime_type       TEXT,
                created_at_ms   INTEGER NOT NULL,
                ref_count       INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX idx_ArchivedMediaFile_refCount ON ArchivedMediaFile(ref_count)",
            """
            CREATE TABLE PostSnapshot (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                source_kind     TEXT NOT NULL,
                source_key      TEXT NOT NULL,
                message_key     TEXT NOT NULL,
                album_key       TEXT,
                kind            TEXT NOT NULL,
                seen_at_ms      INTEGER NOT NULL,
                edited_at_ms    INTEGER,
                content_kind    TEXT NOT NULL,
                content_blob    BLOB NOT NULL,
                content_hash    TEXT NOT NULL,
                text_preview    TEXT NOT NULL DEFAULT '',
                media_minithumb BLOB,
                deleted_msg_keys TEXT,
                is_comment      INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX idx_PostSnapshot_msg ON PostSnapshot(source_kind, source_key, message_key, seen_at_ms DESC)",
            "CREATE INDEX idx_PostSnapshot_seen ON PostSnapshot(seen_at_ms DESC)",
            "CREATE INDEX idx_PostSnapshot_id ON PostSnapshot(id)",
        )
    }
}
