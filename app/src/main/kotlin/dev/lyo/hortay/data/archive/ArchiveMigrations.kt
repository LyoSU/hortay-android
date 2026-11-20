package dev.lyo.hortay.data.archive

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Kotlin steps of the archive schema migrations: the parts SQLite 3.18 cannot do.
 *
 * They run as SQLDelight [AfterVersion] callbacks, which the Android driver's open-helper
 * callback invokes inside `onUpgrade` — the same transaction as the `.sqm` statements
 * (`SQLiteOpenHelper` wraps the whole upgrade in one), so a failure rolls back the schema
 * change too and the next launch retries from the old version. Each step uses raw SQL
 * against the schema as of its own migration, never the generated queries (a later schema
 * change must not change what an old migration does), and is idempotent.
 */
internal object ArchiveMigrations {

    /**
     * After 2.sqm (schema 2 → 3): fills `MessageVersion.media_sha` from the SHA v1 kept
     * inside each TDLib content blob ([ArchivedMediaRef.localArchiveSha]) — the column is
     * the only reference the orphan media sweep honours. Pages of [PAGE] rows by id, so at
     * most one page of blobs is in memory. Rows already holding a sha are skipped; an
     * undecodable blob or one without archived media leaves the column null.
     */
    val afterV2 = AfterVersion(2) { driver -> extractMediaShas(driver) }

    val callbacks: Array<AfterVersion> = arrayOf(afterV2)

    private const val PAGE = 200L

    internal fun extractMediaShas(driver: SqlDriver) {
        var afterId = 0L
        while (true) {
            val page = driver.executeQuery(
                identifier = null,
                sql = "SELECT id, content_blob FROM MessageVersion " +
                    "WHERE content_kind = 'tdlib' AND media_sha IS NULL AND id > ? ORDER BY id LIMIT ?",
                mapper = { cursor -> QueryResult.Value(readIdBlobPairs(cursor)) },
                parameters = 2,
            ) {
                bindLong(0, afterId)
                bindLong(1, PAGE)
            }.value
            if (page.isEmpty()) break
            for ((id, blob) in page) {
                val sha = runCatching { ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                driver.execute(null, "UPDATE MessageVersion SET media_sha = ? WHERE id = ?", 2) {
                    bindString(0, sha)
                    bindLong(1, id)
                }
            }
            afterId = page.last().first
        }
    }

    private fun readIdBlobPairs(cursor: SqlCursor): List<Pair<Long, ByteArray>> {
        val out = ArrayList<Pair<Long, ByteArray>>()
        while (cursor.next().value) {
            val id = cursor.getLong(0) ?: continue
            out += id to (cursor.getBytes(1) ?: ByteArray(0))
        }
        return out
    }
}

/**
 * The archive's [SqlDriver]: opens `archive.db` on first use and, if opening or migrating
 * it throws, logs once, leaves the file as it is and serves an empty in-memory database
 * instead ([isAvailable] = false), so a broken archive cannot stop the app from starting.
 *
 * What it replaces: the driver opened the file lazily on the first query, so a failing
 * migration (or a downgrade: the open helper refuses one) threw out of whatever ran that
 * query first — the feed's tombstone / edit-count flows, killing the feed's combine
 * (logged by the app scope's last-resort handler), the archive screens' ViewModel flows
 * (an uncaught crash), the nightly sweep — on every launch; and a corrupt file was deleted
 * by the open helper's default corruption handler ([ArchiveOpenCallback] now keeps it).
 *
 * Opening is deferred to the first statement, as before (never on the main thread at
 * construction). Listeners are kept here, not in the delegate, so registering a query
 * listener does not force the open; the transacter calls [notifyListeners] on this driver.
 */
class GuardedSqlDriver(
    private val openPrimary: () -> SqlDriver,
    private val openFallback: () -> SqlDriver,
    private val onFailure: (Throwable) -> Unit,
) : SqlDriver {

    private val lock = Any()

    @Volatile
    private var resolved: SqlDriver? = null

    @Volatile
    private var primaryOpen = false

    private val listeners = LinkedHashMap<String, MutableSet<Query.Listener>>()

    /** True when `archive.db` itself is open; false when serving the fallback. Opens on first call. */
    val isAvailable: Boolean
        get() {
            driver()
            return primaryOpen
        }

    private fun driver(): SqlDriver {
        resolved?.let { return it }
        synchronized(lock) {
            resolved?.let { return it }
            val primary = runCatching(openPrimary).onFailure(onFailure).getOrNull()
            val opened = primary != null && runCatching { probe(primary) }
                .onFailure { e ->
                    onFailure(e)
                    runCatching { primary.close() }
                }
                .isSuccess
            val chosen = if (opened) primary!! else openFallback()
            primaryOpen = opened
            resolved = chosen
            return chosen
        }
    }

    /** First statement: makes the open helper open (and migrate) the file now. */
    private fun probe(driver: SqlDriver) {
        driver.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master", { c -> QueryResult.Value(c.next().value) }, 0).value
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = driver().executeQuery(identifier, sql, mapper, parameters, binders)

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = driver().execute(identifier, sql, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = driver().newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = resolved?.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { LinkedHashSet() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { key -> listeners[key]?.let(toNotify::addAll) }
        }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() {
        resolved?.close()
    }
}
