package dev.lyo.hortay.data.archive

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.archive.db.ArchivedMessage
import dev.lyo.hortay.data.archive.db.SelectForMessageKey

/** An in-memory archive at the current schema, with its driver for raw SQL. */
internal class TestArchive {
    val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ArchiveDatabase.Schema.create(it) }
    val db: ArchiveDatabase = ArchiveDatabase(driver)

    fun count(table: String): Long = longQuery("SELECT COUNT(*) FROM $table")

    fun longQuery(sql: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value
}

/** A fresh in-memory archive at the current schema. */
internal fun newArchiveDb(): ArchiveDatabase = TestArchive().db

internal fun testMeta(text: String) = TdlibContentMeta(
    text = text, entitiesJson = "[]",
    mediaSummaryJson = null, pollJson = null,
    forwardJson = null, replyJson = null,
)

/** A message's stored versions in history order (sort_at_ms, id). */
internal fun ArchiveDatabase.versionsOf(chat: ChatRef, messageKey: String): List<SelectForMessageKey> =
    messageVersionQueries.selectForMessageKey(chat.kind.name, chat.key, messageKey).executeAsList()

internal fun SelectForMessageKey.text(): String = ContentBlobCodec.decode(content_blob).text

/** Texts of a message's stored TDLib versions in history order. */
internal fun ArchiveDatabase.textsOf(chat: ChatRef, messageKey: String): List<String> =
    versionsOf(chat, messageKey).map { it.text() }

internal fun ArchiveDatabase.messageOf(chat: ChatRef, messageKey: String): ArchivedMessage? =
    archivedMessageQueries.selectByKey(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()

internal fun ArchiveDatabase.countMessages(): Long = archivedMessageQueries.countAll().executeAsOne()

/**
 * Inserts a version row directly, bypassing capture (creates the message when absent) —
 * for states capture no longer produces: older hash versions, corrupt blobs, phantoms.
 */
@Suppress("LongParameterList")
internal fun ArchiveDatabase.insertRawVersion(
    chat: ChatRef,
    messageKey: String,
    blob: ByteArray,
    hash: String,
    sortAtMs: Long,
    hashVersion: Long = ContentNormalizer.HASH_VERSION,
    origin: VersionOrigin = VersionOrigin.LIVE_EDIT,
    editDateMs: Long? = sortAtMs,
    observedAtMs: Long = sortAtMs,
    mediaSha: String? = null,
    textPreview: String = "",
    albumKey: String? = null,
    eventAtMs: Long = observedAtMs,
): Long {
    archivedMessageQueries.insertIfAbsent(
        chat.kind.name, chat.key, messageKey, albumKey, 0L, null, eventAtMs, eventAtMs,
    )
    val message = messageOf(chat, messageKey)!!
    messageVersionQueries.insert(
        message.id, sortAtMs, editDateMs, observedAtMs, origin.name, CONTENT_KIND_TDLIB,
        blob, hash, hashVersion, textPreview, mediaSha, null,
    )
    return message.id
}
