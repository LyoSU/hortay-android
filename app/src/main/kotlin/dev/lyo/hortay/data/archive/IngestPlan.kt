package dev.lyo.hortay.data.archive

import android.util.JsonWriter
import dev.lyo.hortay.data.archive.db.PostSnapshotQueries
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What [planTdlibIngest] decided an ingested message needs. */
internal sealed interface IngestPlan {
    /** No VERSION at-or-before publication: write the first-observed baseline. */
    data object Baseline : IngestPlan

    /** Archived, and the ingest carries a newer edit date: compare hashes, maybe write. */
    data class Reconcile(val latestHash: String) : IngestPlan

    /** Nothing to write; decided without hashing the content. */
    data class Skip(
        val decision: ArchiveDecision,
        val latestHash: String? = null,
        val detail: String? = null,
    ) : IngestPlan
}

/**
 * Decide, without hashing anything, what an ingested TDLib message needs: a baseline,
 * a reconcile check, or nothing. Two indexed lookups, no writes, so the per-message
 * cold-start cost of an already-archived post stays at two SELECTs — the content
 * extraction + hash run only for [IngestPlan.Baseline] / [IngestPlan.Reconcile].
 *
 * The baseline rule is [ArchiveRepository.captureTdlibBaseline]'s own idempotency rule
 * (a VERSION row at-or-before `originalDateMs` already exists → no baseline), so a plan
 * of [IngestPlan.Baseline] is re-validated there under `writeMutex`; a [IngestPlan.Reconcile]
 * is re-validated by [ArchiveRepository.captureTdlibReconcile].
 *
 * @param gate [ArchiveRepository.versionGate] for the chat; non-null short-circuits.
 * @param editedAtMs the message's own `editDate` in ms, null when never edited.
 * @param allowBaseline false for re-ingests of messages the feed already held: those
 *   only reconcile, so a message the archive never baselined (an album sibling, a
 *   post first seen before the archive was enabled) is not adopted mid-life.
 * @return null when there is nothing to decide (no rows and no baseline allowed).
 */
@Suppress("LongParameterList")
internal fun planTdlibIngest(
    queries: PostSnapshotQueries,
    gate: ArchiveDecision?,
    chat: ChatRef,
    messageKey: String,
    originalDateMs: Long,
    editedAtMs: Long?,
    allowBaseline: Boolean,
): IngestPlan? {
    if (gate != null) return IngestPlan.Skip(gate)
    val first = queries.selectFirstSeenForMessage(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()
    val latest = if (first == null || first.seen_at_ms > originalDateMs) {
        null
    } else {
        queries.latestForMessage(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()
    }
    if (latest == null) return if (allowBaseline) IngestPlan.Baseline else null
    if (latest.kind == SnapshotKind.DELETED.name) {
        return IngestPlan.Skip(ArchiveDecision.STALE, latest.content_hash, DETAIL_DELETED)
    }
    // Never edited and already baselined: the common cold-start re-ingest. Labelled a
    // baseline duplicate, so a journal RECONCILE line always means an edit date was compared.
    if (editedAtMs == null) return IngestPlan.Skip(ArchiveDecision.DUP_HASH, latest.content_hash, DETAIL_BASELINE_PRESENT)
    val newer = latest.edited_at_ms == null || editedAtMs > latest.edited_at_ms
    if (!newer) return IngestPlan.Skip(ArchiveDecision.STALE, latest.content_hash, DETAIL_NO_NEWER_EDIT)
    return IngestPlan.Reconcile(latest.content_hash)
}

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
