package dev.lyo.hortay.data.archive

import android.util.JsonWriter
import dev.lyo.hortay.data.archive.db.ArchivedMessageQueries
import dev.lyo.hortay.data.archive.db.MessageVersionQueries
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What [planTdlibIngest] decided an ingested message needs. */
internal sealed interface IngestPlan {
    /** The archive holds no version at-or-before this message's state: write the first-observed one. */
    data object Baseline : IngestPlan

    /**
     * Archived, and the ingest carries a newer edit date: compare hashes, maybe write.
     * [latestLacksMedia]: the latest version has no archived media copy (`media_sha` NULL).
     */
    data class Reconcile(val latestHash: String, val latestLacksMedia: Boolean = false) : IngestPlan

    /**
     * Nothing to write; decided without hashing the content. [latestLacksMedia]: the latest
     * version has no archived media copy, so a skip whose media is on disk now may still
     * attach one ([attachMediaIfSame]).
     */
    data class Skip(
        val decision: ArchiveDecision,
        val latestHash: String? = null,
        val detail: String? = null,
        val latestLacksMedia: Boolean = false,
    ) : IngestPlan
}

/**
 * Decide, without hashing anything, what an ingested TDLib message needs: a baseline,
 * a reconcile check, or nothing. Indexed lookups only (the message by its unique key,
 * then its earliest and latest version), no writes, so the per-message cold-start cost of
 * an already-archived post stays a few SELECTs — the content extraction + hash run only
 * for [IngestPlan.Baseline] / [IngestPlan.Reconcile].
 *
 * The baseline rule is [ArchiveRepository.captureTdlibBaseline]'s own idempotency rule
 * (a version at-or-before the ingested state's place on the timeline already exists → no
 * baseline), so a plan of [IngestPlan.Baseline] is re-validated there under `writeMutex`;
 * a [IngestPlan.Reconcile] is re-validated by [ArchiveRepository.captureTdlibReconcile].
 *
 * @param gate [ArchiveRepository.versionGate] for the chat; non-null short-circuits.
 * @param editedAtMs the message's own `editDate` in ms, null when never edited.
 * @param allowBaseline false for re-ingests of messages the feed already held: those
 *   only reconcile, so a message the archive never baselined (an album sibling, a
 *   post first seen before the archive was enabled) is not adopted mid-life.
 * @return null when there is nothing to decide (no versions and no baseline allowed).
 */
@Suppress("LongParameterList")
internal fun planTdlibIngest(
    messages: ArchivedMessageQueries,
    versions: MessageVersionQueries,
    gate: ArchiveDecision?,
    chat: ChatRef,
    messageKey: String,
    originalDateMs: Long,
    editedAtMs: Long?,
    allowBaseline: Boolean,
): IngestPlan? {
    if (gate != null) return IngestPlan.Skip(gate)
    val message = messages.selectByKey(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()
    val minSort = message?.let { versions.minSortForMessage(it.id).executeAsOne().min_sort }
    val latest = if (message == null || minSort == null || minSort > baselineSortOf(originalDateMs, editedAtMs)) {
        null
    } else {
        versions.latestForMessage(message.id).executeAsOneOrNull()
    }
    if (message == null || latest == null) return if (allowBaseline) IngestPlan.Baseline else null
    if (message.deleted_at_ms != null) {
        return IngestPlan.Skip(ArchiveDecision.STALE, latest.content_hash, DETAIL_DELETED)
    }
    // Never edited and already baselined: the common cold-start re-ingest. Labelled a
    // baseline duplicate, so a journal RECONCILE line always means an edit date was compared.
    val lacksMedia = latest.media_sha == null
    if (editedAtMs == null) {
        return IngestPlan.Skip(ArchiveDecision.DUP_HASH, latest.content_hash, DETAIL_BASELINE_PRESENT, lacksMedia)
    }
    val newer = latest.edit_date_ms == null || editedAtMs > latest.edit_date_ms
    if (!newer) return IngestPlan.Skip(ArchiveDecision.STALE, latest.content_hash, DETAIL_NO_NEWER_EDIT, lacksMedia)
    return IngestPlan.Reconcile(latest.content_hash, lacksMedia)
}

/**
 * Where an ingested state sits on a message's timeline (`sort_at_ms`): its edit date, or
 * its publication time when never edited. A version at-or-before it means the archive
 * already holds this state or an earlier one — see [ArchiveRepository.captureTdlibBaseline].
 */
internal fun baselineSortOf(originalDateMs: Long, editedAtMs: Long?): Long = editedAtMs ?: originalDateMs

/** Writes the journal [entries] as a JSON array of flat objects (the export's `captureJournal`). */
internal fun writeJournalArray(writer: JsonWriter, entries: List<JsonObject>) {
    writer.beginArray()
    for (entry in entries) {
        writer.beginObject()
        for ((key, value) in entry) {
            val p = value as? JsonPrimitive ?: continue
            writer.name(key)
            when {
                p.isString -> writer.value(p.content)
                p.content == "true" || p.content == "false" -> writer.value(p.content.toBoolean())
                else -> p.content.toLongOrNull()?.let { writer.value(it) } ?: writer.value(p.content)
            }
        }
        writer.endObject()
    }
    writer.endArray()
}
