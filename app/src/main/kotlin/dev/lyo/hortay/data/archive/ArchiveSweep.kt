package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.StateFlow

/**
 * TTL and cap enforcement for the archive DB.
 *
 * Cap eviction uses `DELETE FROM PostSnapshot WHERE id <= (SELECT MAX(id) FROM PostSnapshot) - :cap`
 * (cheap; uses `idx_PostSnapshot_id`, no `COUNT()` scan). Run nightly from
 * [dev.lyo.hortay.data.StorageOptimizer] and immediately on retention/cap setting changes.
 *
 * Media refcount: when a snapshot row carrying a [TdlibContentMeta.mediaRef] with a
 * non-null [ArchivedMediaRef.localArchiveSha] is evicted, the underlying file in
 * [ArchivedMediaStore] needs its refcount decremented — otherwise orphaned media
 * accumulates indefinitely under `filesDir/archive_media/`. The sweep grabs blobs
 * of soon-to-be-evicted rows, decodes them to recover the SHAs, then releases the
 * refs after the SQL DELETE.
 */
class ArchiveSweep(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val mediaStore: ArchivedMediaStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val s = settings.value
        val retentionMs = if (s.retentionDays == Int.MAX_VALUE) Long.MAX_VALUE
                          else s.retentionDays.toLong() * 86_400_000L
        if (retentionMs != Long.MAX_VALUE) {
            val cutoff = clock() - retentionMs
            val shasToRelease = collectMediaShas(
                db.postSnapshotQueries.selectBlobsOlderThan(cutoff).executeAsList(),
            )
            db.postSnapshotQueries.deleteOlderThan(cutoff)
            releaseAll(shasToRelease)
        }
        if (s.maxRecords != Int.MAX_VALUE) {
            val shasToRelease = collectMediaShas(
                db.postSnapshotQueries.selectBlobsByCap(s.maxRecords.toLong()).executeAsList(),
            )
            db.postSnapshotQueries.deleteByCap(s.maxRecords.toLong())
            releaseAll(shasToRelease)
        }
    }

    private fun collectMediaShas(blobs: List<ByteArray>): List<String> {
        val out = ArrayList<String>(blobs.size)
        for (blob in blobs) {
            val sha = runCatching { ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha }
                .getOrNull()
            if (!sha.isNullOrEmpty()) out += sha
        }
        return out
    }

    private suspend fun releaseAll(shas: List<String>) {
        val store = mediaStore ?: return
        if (shas.isEmpty()) return
        for (sha in shas) {
            runCatching { store.releaseRef(sha) }
        }
    }
}
