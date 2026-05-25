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
 * Reference-counted permanent storage for archived media files.
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
 * Single-writer mutex serialises copy/ref-bump/delete so refcount can't drift
 * under concurrent capture+sweep.
 */
class ArchivedMediaStore(
    private val context: Context,
    private val db: ArchiveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    /** Lazily created. Survives across launches; cleared by [clearAll]. */
    private val rootDir: File by lazy {
        File(context.filesDir, "archive_media").apply { mkdirs() }
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
     *  - I/O failure during read.
     *
     * Idempotent: a second call for the same bytes only bumps refcount.
     */
    suspend fun copyIfAvailable(file: TdApi.File?): String? = withContext(Dispatchers.IO) {
        if (file?.local == null) return@withContext null
        val path = file.local.path
        if (path.isNullOrEmpty() || !file.local.isDownloadingCompleted) return@withContext null
        val src = File(path)
        if (!src.exists()) return@withContext null

        val sha = sha256OfFile(src) ?: return@withContext null
        mutex.withLock {
            val existing = db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()
            if (existing != null) {
                db.archivedMediaFileQueries.incrementRefCount(sha)
                return@withLock sha
            }
            val dst = File(rootDir, "$sha.bin")
            if (!dst.exists()) {
                runCatching { src.copyTo(dst, overwrite = false) }
                    .getOrElse { return@withLock null }
            }
            db.archivedMediaFileQueries.insert(
                sha = sha,
                path = dst.absolutePath,
                size_bytes = dst.length(),
                mime_type = null,
                created_at_ms = clock(),
                ref_count = 1L,
            )
            sha
        }
    }

    /**
     * Path on disk for [sha], or null if not stored. Cheap read — used by the
     * revision sheet to feed Coil / ExoPlayer directly without re-downloading.
     */
    suspend fun pathFor(sha: String): String? = withContext(Dispatchers.IO) {
        db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()?.path
    }

    /**
     * Decrement refcount for [sha]; physically delete the file when refcount
     * reaches 0. Called by [ArchiveSweep] after evicting a snapshot row that
     * references this SHA.
     */
    suspend fun releaseRef(sha: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.archivedMediaFileQueries.decrementRefCount(sha)
            val row = db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()
                ?: return@withLock
            if (row.ref_count <= 0L) {
                runCatching { File(row.path).delete() }
                db.archivedMediaFileQueries.deleteBySha(sha)
            }
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
    }
}
