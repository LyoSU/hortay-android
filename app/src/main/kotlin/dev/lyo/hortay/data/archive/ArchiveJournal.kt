package dev.lyo.hortay.data.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** What a capture decision was about. */
enum class ArchiveEventKind { BASELINE, EDIT, RECONCILE, DELETE, WEB }

/** Outcome of one capture decision. */
enum class ArchiveDecision { WRITTEN, DUP_HASH, STALE, DISABLED, EXCLUDED, NO_CONTENT, ERROR }

/** Where the content of a TDLib capture came from. */
enum class ArchiveContentSource {
    /** The `UpdateMessageContent` buffered by [PendingEditBuffer]. */
    BUFFERED,

    /** `GetMessageLocally` inside the recorder's consumer. */
    LOCAL_READ,

    /** The message object the feed ingested (baseline / reconcile). */
    INGEST,
}

/**
 * Result of one archive write attempt. [hash] / [latestHash] are full content hashes;
 * the journal keeps only their first 8 hex chars.
 */
data class CaptureOutcome(
    val decision: ArchiveDecision,
    val hash: String? = null,
    val latestHash: String? = null,
    val detail: String? = null,
    /** How the capture differs from the latest stored version, when they were compared on content. */
    val diff: ContentDiff? = null,
)

/** One composite DELETED write of [ArchiveRepository.captureTdlibDeleteSmart]: its keys and outcome. */
data class DeleteOutcome(val messageKeys: List<String>, val outcome: CaptureOutcome)

/** [CaptureOutcome.detail] / [ArchiveJournalRecord.detail] codes. */
internal const val DETAIL_NO_NEWER_EDIT = "no_newer_edit"
internal const val DETAIL_DELETED = "deleted"
internal const val DETAIL_NOT_ARCHIVED = "not_archived"
internal const val DETAIL_BASELINE_PRESENT = "baseline_present"
internal const val DETAIL_NEVER_ARCHIVED = "never_archived"
internal const val DETAIL_DUP_REHASH = "dup_rehash"
internal const val DETAIL_SAME_EDIT_DATE = "same_edit_date"

/**
 * Appended (comma-separated) to a skip's detail when the skipped capture gave the latest
 * version its archived media copy — see [attachMediaIfSame].
 */
internal const val DETAIL_MEDIA_ATTACHED = "media_attached"

/**
 * One structured line of the capture decision journal. Carries ids, hashes and codes
 * only — **never** post text, captions, titles or file paths: the journal is exported
 * with the archive and logged to logcat in debuggable / beta builds.
 */
data class ArchiveJournalRecord(
    val wallTimeMs: Long,
    val sourceKind: SourceKind,
    val chatKey: String,
    val messageKey: String,
    val event: ArchiveEventKind,
    val decision: ArchiveDecision,
    val isComment: Boolean = false,
    /** The edit date the decision was taken against, in ms; null for none / as-published. */
    val editDateMs: Long? = null,
    val newHash: String? = null,
    val latestHash: String? = null,
    val contentSource: ArchiveContentSource? = null,
    /** Short machine-readable reason (`no_newer_edit`, an exception class name, `keys=5`). */
    val detail: String? = null,
    /** Content-free comparison with the latest stored version — see [ContentDiff]. */
    val diff: ContentDiff? = null,
    /**
     * False for the per-launch no-op outcomes of re-ingest (same hash, nothing newer):
     * every cold start re-ingests hundreds of already-archived posts, and persisting
     * those would rotate the useful edit / delete records out of the bounded ring within
     * a few launches. Still logged to logcat.
     */
    val persist: Boolean = true,
) {
    fun toJsonLine(): String = toJson().toString()

    fun toJson(): JsonObject = buildJsonObject {
        put("ts", wallTimeMs)
        put("source", sourceKind.name)
        put("chat", chatKey)
        put("msg", messageKey)
        put("event", event.name)
        put("decision", decision.name)
        if (isComment) put("comment", true)
        editDateMs?.let { put("editDateMs", it) }
        newHash?.let { put("hash", it.take(HASH_PREFIX)) }
        latestHash?.let { put("latestHash", it.take(HASH_PREFIX)) }
        contentSource?.let { put("contentSource", it.name) }
        detail?.let { put("detail", it.take(DETAIL_MAX)) }
        diff?.writeTo(this)
    }

    companion object {
        const val HASH_PREFIX = 8
        const val DETAIL_MAX = 64
    }
}

/**
 * Bounded on-device log of archive capture decisions, so "why does this post have 8
 * versions" can be answered from the device (Settings → export, or logcat on a debuggable
 * / beta build). Implementations must be thread-safe and must never throw into a capture.
 */
interface ArchiveJournal {
    fun record(record: ArchiveJournalRecord)

    /** Persisted records, oldest first, as parsed JSON objects (one per line). */
    fun readAll(): List<JsonObject>

    fun clear()
}

/** Bounded in-memory journal — the test double, and a fallback with no files dir. */
class InMemoryArchiveJournal(private val capacity: Int = DEFAULT_CAPACITY) : ArchiveJournal {
    private val lock = Any()
    private val records = ArrayDeque<ArchiveJournalRecord>()

    override fun record(record: ArchiveJournalRecord) {
        synchronized(lock) {
            records.addLast(record)
            while (records.size > capacity) records.removeFirst()
        }
    }

    /** Every recorded record, persist-flagged or not (tests assert on the full stream). */
    fun records(): List<ArchiveJournalRecord> = synchronized(lock) { records.toList() }

    override fun readAll(): List<JsonObject> = synchronized(lock) {
        records.filter { it.persist }.map { it.toJson() }
    }

    override fun clear() {
        synchronized(lock) { records.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}

/**
 * Two rotating JSONL files under [dir] (`journal.0.jsonl` current, `journal.1.jsonl`
 * previous), [perFile] lines each, so at most `2 × perFile` records survive. Rotation
 * drops the previous file and renames the current one — no rewrite of a big file per
 * append. Every record goes to [logcat] when set; only `persist` records hit the disk.
 * I/O failures are swallowed: the journal is diagnostics and must never break a capture.
 */
class FileArchiveJournal(
    private val dir: File,
    private val perFile: Int = InMemoryArchiveJournal.DEFAULT_CAPACITY / 2,
    private val logcat: ((String) -> Unit)? = null,
) : ArchiveJournal {
    private val lock = Any()
    private val current = File(dir, "journal.0.jsonl")
    private val previous = File(dir, "journal.1.jsonl")

    /** Lines in [current]; -1 until counted once on the first append. */
    private var currentLines = -1

    override fun record(record: ArchiveJournalRecord) {
        val line = record.toJsonLine()
        logcat?.let { log -> runCatching { log(line) } }
        if (!record.persist) return
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                if (currentLines < 0) currentLines = countLines(current)
                if (currentLines >= perFile) {
                    previous.delete()
                    current.renameTo(previous)
                    currentLines = 0
                }
                current.appendText(line + "\n", Charsets.UTF_8)
                currentLines++
            }
        }
    }

    override fun readAll(): List<JsonObject> = synchronized(lock) {
        (readLines(previous) + readLines(current)).mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        }
    }

    override fun clear() {
        synchronized(lock) {
            runCatching { current.delete() }
            runCatching { previous.delete() }
            currentLines = 0
        }
    }

    private fun countLines(file: File): Int =
        if (file.exists()) runCatching { file.useLines { it.count() } }.getOrDefault(0) else 0

    private fun readLines(file: File): List<String> =
        if (file.exists()) runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()) else emptyList()
}
