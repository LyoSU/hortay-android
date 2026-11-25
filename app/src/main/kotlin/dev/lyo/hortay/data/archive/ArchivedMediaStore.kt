package dev.lyo.hortay.data.archive

import android.content.Context
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.MessageDigest

/**
 * Permanent storage for archived media files.
 *
 * Why this exists: TDLib's file cache is LRU (controlled by the
 * `message_unload_delay` option). A snapshot row that pointed at TDLib's
 * `local.path` would lose its media file silently when the cache evicts. We
 * copy the bytes once into [Context.filesDir]/archive_media/ at capture time
 * and own them ourselves.
 *
 * Per Lev Lam in tdlib/td#3493: even `getRemoteFile` works "only if the file
 * is still accessible to the user and known to TDLib" — deleted-message media
 * isn't recoverable through TDLib. The local copy is the only durable answer.
 *
 * Storage key: SHA-256 of the file's bytes. Two captures of the same image
 * (same channel, same post, different revisions) share one underlying file.
 *
 * No reference counting. A file is referenced by the `MessageVersion.media_sha` of the
 * versions showing it, and [sweepOrphans] removes one nothing references once its last
 * claim is older than [ORPHAN_GRACE_MS]. v1 kept a manual `ref_count` that six call sites
 * released by decoding blobs (purge, retention, cap, skipped captures …); every missed
 * release leaked a file. Now a skipped capture, a purge or an eviction simply leaves the
 * file unreferenced.
 *
 * Single mutex serialises copy / claim / sweep, so a sweep can never take a file between
 * a capture's copy and its version write: the copy re-stamps `claimed_at_ms` first.
 */
class ArchivedMediaStore(
    private val context: Context,
    private val db: ArchiveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    /** Lazily created. Survives across launches; cleared by [clearAll]. */
    private val rootDir: File by lazy {
        mediaDir(context).apply { mkdirs() }
    }

    /**
     * Copy a TDLib-downloaded file into archive storage, if it's available.
     *
     * Returns the SHA-256 (hex) of the file bytes when copy succeeded — caller
     * should stamp this into [ArchivedMediaRef.localArchiveSha].
     *
     * Returns `null` when:
     *  - the file has no local copy (`local.path` empty);
     *  - the download isn't complete (would copy a partial / corrupt file);
     *  - source file is missing on disk (TDLib path lies, very rare);
     *  - the file is larger than [MAX_ARCHIVED_FILE_BYTES] (the revision then
     *    shows the inline preview only);
     *  - I/O failure during read.
     *
     * Idempotent: a second call for the same bytes only re-stamps the file's claim, which
     * keeps [sweepOrphans] off it until the capture's version row is written.
     */
    suspend fun copyIfAvailable(file: TdApi.File?): String? = withContext(Dispatchers.IO) {
        if (file?.local == null) return@withContext null
        val path = file.local.path
        if (path.isNullOrEmpty() || !file.local.isDownloadingCompleted) return@withContext null
        val src = File(path)
        if (!src.exists() || src.length() > MAX_ARCHIVED_FILE_BYTES) return@withContext null

        val sha = sha256OfFile(src) ?: return@withContext null
        mutex.withLock {
            val now = clock()
            val existing = db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()
            if (existing != null && File(existing.path).let { it.exists() && it.length() == src.length() }) {
                db.archivedMediaFileQueries.claim(now, sha)
                return@withLock sha
            }
            val dst = File(rootDir, "$sha.bin")
            // The name is the content hash, so a file of another length is a leftover of an
            // interrupted copy from before copies went through a temp file: replace it.
            if (!dst.exists() || dst.length() != src.length()) {
                if (!copyAtomically(src, dst)) return@withLock null
            }
            db.archivedMediaFileQueries.insert(
                sha = sha,
                path = dst.absolutePath,
                size_bytes = dst.length(),
                mime_type = null,
                created_at_ms = now,
                claimed_at_ms = now,
            )
            db.archivedMediaFileQueries.claim(now, sha)
            sha
        }
    }

    /**
     * Copies [src] to a temp file next to [dst] and renames it into place, so `<sha>.bin`
     * only ever exists complete: a copy cut off by an I/O error, a full disk or the process
     * dying leaves at most a temp file, which this call (or [sweepOrphans]) deletes. A later
     * capture of the same bytes would otherwise adopt a truncated file as the archived copy.
     */
    private fun copyAtomically(src: File, dst: File): Boolean {
        val tmp = File(dst.parentFile, dst.name + TEMP_SUFFIX)
        val ok = runCatching {
            src.copyTo(tmp, overwrite = true)
            tmp.length() == src.length() && (tmp.renameTo(dst) || (dst.delete() && tmp.renameTo(dst)))
        }.getOrDefault(false)
        if (!ok) runCatching { tmp.delete() }
        return ok
    }

    /**
     * Path on disk for [sha], or null if not stored. Cheap read — used by the
     * revision sheet to feed Coil / ExoPlayer directly without re-downloading.
     */
    suspend fun pathFor(sha: String): String? = withContext(Dispatchers.IO) {
        db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()?.path
    }

    /**
     * Removes every file no `MessageVersion` references whose last claim is older than
     * [ORPHAN_GRACE_MS] — what a purge, a retention / cap eviction, the dedupe step or a
     * skipped capture left behind. A file that can't be deleted keeps its row and is
     * retried on the next sweep. Also deletes temp files of copies that never finished
     * (no copy is in flight while the lock is held). Returns the number of files removed.
     */
    suspend fun sweepOrphans(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                rootDir.listFiles { f -> f.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
            }
            val orphans = db.archivedMediaFileQueries.selectOrphans(clock() - ORPHAN_GRACE_MS).executeAsList()
            var removed = 0
            for (o in orphans) {
                val file = File(o.path)
                if (runCatching { !file.exists() || file.delete() }.getOrDefault(false)) {
                    db.archivedMediaFileQueries.deleteBySha(o.sha)
                    removed++
                }
            }
            removed
        }
    }

    /**
     * Drop every archived media file from disk and from the index.
     * Called from [ArchiveRepository.clear].
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { rootDir.listFiles()?.forEach { it.delete() } }
            db.archivedMediaFileQueries.clearAll()
        }
    }

    /** Aggregate disk bytes for the storage panel. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        db.archivedMediaFileQueries.storageBytes().executeAsOne()
    }

    private fun sha256OfFile(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        private const val BUFFER_SIZE = 8 * 1024

        /** Suffix of a copy in progress; see [copyAtomically]. */
        private const val TEMP_SUFFIX = ".tmp"

        /** Where the copies live: `filesDir/archive_media/`. */
        fun mediaDir(context: Context): File = File(context.filesDir, "archive_media")

        /**
         * Per-file cap for archived media copies. Any fully-downloaded file is a
         * candidate, including multi-GB videos and documents; hashing and
         * duplicating those would burn minutes of IO and fill app storage for a
         * feature that mostly needs a revision preview. Larger files are skipped
         * and the revision falls back to the inline preview.
         */
        private const val MAX_ARCHIVED_FILE_BYTES = 64L * 1024 * 1024

        /**
         * How long a copied file is safe from [sweepOrphans] after its last claim. A capture
         * copies the file before the recorder writes the version that references it (the
         * single consumer, seconds at most); an hour leaves room for a queue backed up by a
         * slow TDLib read without letting skipped copies linger for long.
         */
        const val ORPHAN_GRACE_MS = 60L * 60 * 1000
    }
}
