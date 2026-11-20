package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase

/*
 * Whole-message storage operations shared by ArchiveRepository (captures, purge, the
 * hot-path cap check) and ArchiveSweep (retention, cap, dedupe). Every removal takes a
 * message together with all of its versions: v1 evicted the oldest ROWS, so an edited post
 * could lose its original version and keep the later ones. Media files are not touched
 * here — a file no version references any more is removed by the orphan sweep
 * (ArchivedMediaStore.sweepOrphans).
 */

/** Ids per `IN (?)` batch: well under SQLite's 999-variable limit on Android 8. */
internal const val SQL_VARIABLE_CHUNK = 500

/** Removes the messages [ids] with all their versions, in one transaction. */
internal fun ArchiveDatabase.deleteMessages(ids: Collection<Long>) {
    if (ids.isEmpty()) return
    transaction {
        ids.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            messageVersionQueries.deleteByMessageIds(chunk)
            archivedMessageQueries.deleteByIds(chunk)
        }
    }
}

/** Retention: removes every message whose newest event is older than [cutoffMs]. Returns how many. */
internal fun ArchiveDatabase.dropMessagesOlderThan(cutoffMs: Long): Int = transactionWithResult {
    val ids = archivedMessageQueries.selectIdsOlderThan(cutoffMs).executeAsList()
    deleteMessages(ids)
    ids.size
}

/**
 * Record cap: keeps at most [cap] messages (each with its whole history). Past it, the
 * messages that carry no history go first — one TDLib version, not deleted: a post that
 * never changed, which the archive browser never lists — oldest first; then the oldest
 * histories. Returns how many messages were removed.
 */
internal fun ArchiveDatabase.evictToCap(cap: Int): Int = transactionWithResult {
    val excess = archivedMessageQueries.countAll().executeAsOne() - cap
    if (excess <= 0) return@transactionWithResult 0
    val ids = archivedMessageQueries.selectEvictionOrder(excess).executeAsList()
    deleteMessages(ids)
    ids.size
}

/**
 * Folds the phantom versions older canonical forms let through, and upgrades every TDLib
 * version still hashed with an older form ([ContentNormalizer.LEGACY_HASH_VERSION]) to
 * [ContentNormalizer.HASH_VERSION]. Idempotent: a message is visited only while it holds
 * an outdated hash, and leaves with none.
 *
 * Per message, in history order: a version canonically equal to the readable version
 * before it — same canonical hash, and the same media file identity when both know one —
 * is removed, keeping the earlier one (the moment that state first appeared). If the
 * removed one held the only archived copy of the media (`media_sha`), the earlier one
 * takes it over in the same transaction, so the file is never unreferenced. An unreadable
 * version is kept and breaks the run, like the revision sheet does.
 *
 * Memory: one message's versions at a time, [pageSize] message ids per page.
 *
 * @return the number of versions removed.
 */
internal fun ArchiveDatabase.dedupeVersions(pageSize: Long = DEDUPE_PAGE): Int {
    var removed = 0
    var afterId = 0L
    while (true) {
        val ids = messageVersionQueries
            .selectMessagesWithStaleHash(ContentNormalizer.HASH_VERSION, afterId, pageSize)
            .executeAsList()
        if (ids.isEmpty()) break
        for (messageId in ids) removed += transactionWithResult<Int> { dedupeMessage(messageId) }
        afterId = ids.last()
    }
    return removed
}

private class Kept(val id: Long, val hash: String, val mediaIdentity: String?, var mediaSha: String?)

private fun ArchiveDatabase.dedupeMessage(messageId: Long): Int {
    val q = messageVersionQueries
    var removed = 0
    var prev: Kept? = null
    for (v in q.selectForMessage(messageId).executeAsList()) {
        val meta = decodeTdlibOrNull(v.content_kind, v.content_blob)
        val hash = meta?.let(ContentNormalizer::canonicalHash)
        val identity = meta?.mediaRef?.uniqueId?.takeIf { it.isNotEmpty() }
        val p = prev
        when {
            hash == null -> {
                prev = null
                if (v.content_kind == CONTENT_KIND_TDLIB && v.hash_version < ContentNormalizer.HASH_VERSION) {
                    // Unreadable: keep its stored hash, but stop selecting the message for this step.
                    q.updateHash(v.content_hash, ContentNormalizer.HASH_VERSION, v.id)
                }
            }
            p != null && p.hash == hash && sameMedia(p.mediaIdentity, identity) -> {
                if (p.mediaSha == null && v.media_sha != null) {
                    q.setMediaSha(v.media_sha, p.id)
                    p.mediaSha = v.media_sha
                }
                q.deleteById(v.id)
                removed++
            }
            else -> {
                if (v.content_hash != hash || v.hash_version < ContentNormalizer.HASH_VERSION) {
                    q.updateHash(hash, ContentNormalizer.HASH_VERSION, v.id)
                }
                prev = Kept(v.id, hash, identity, v.media_sha)
            }
        }
    }
    return removed
}

private const val DEDUPE_PAGE = 100L
