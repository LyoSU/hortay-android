// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReportLogEntry(
    val timestamp: Long,
    /** "auth" for authenticated TDLib mode, "guest" for anonymous web mode. */
    val mode: String,
    val channelUsername: String?,
    val chatId: Long?,
    val messageId: Long?,
    /** "tdlib" | "deep_link" | "web" | "email" */
    val deliveryMethod: String,
    /** "ok" | "failed" | "delegated" */
    val deliveryStatus: String,
)

/**
 * Append-only JSONL audit log for child-safety reporting events.
 *
 * Storage: [Context.filesDir]/report_log.jsonl — one JSON object per line.
 * Rotation: when the file exceeds [MAX_ENTRIES] records, rewrites with the
 * most-recent 200 lines. All I/O on [Dispatchers.IO]; all mutations serialised
 * via [mutex] to avoid partial-line writes.
 *
 * Why JSONL instead of Room: project forbids Room (see ARCHITECTURE.md). JSONL is
 * the lightest format that is both append-only fast and human-readable for a
 * compliance audit, and 200 × ~200 B lines = ~40 KB max — trivially fits in a
 * single file read. Nothing here is queried or joined, only appended and
 * occasionally streamed.
 */
class ReportLogStore(private val context: Context) {

    private val file = File(context.filesDir, "report_log.jsonl")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun log(entry: ReportLogEntry): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.appendText(json.encodeToString(entry) + "\n")
            rotateIfNeeded()
        }
    }

    suspend fun snapshot(): List<ReportLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<ReportLogEntry>(line) }.getOrNull()
                }
        }
    }

    private fun rotateIfNeeded() {
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size > ROTATE_THRESHOLD) {
            val trimmed = lines.takeLast(MAX_ENTRIES)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(trimmed.joinToString("\n") + "\n")
            if (!tmp.renameTo(file)) {
                // Fallback for platforms where rename fails when target exists.
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val ROTATE_THRESHOLD = MAX_ENTRIES * 11 / 10
    }
}
