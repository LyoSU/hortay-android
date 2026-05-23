package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.flow.StateFlow

/**
 * TTL and cap enforcement for the archive DB.
 *
 * Cap eviction uses `DELETE FROM PostSnapshot WHERE id <= (SELECT MAX(id) FROM PostSnapshot) - :cap`
 * (cheap; uses `idx_PostSnapshot_id`, no `COUNT()` scan). Run nightly from
 * [dev.lyo.hortay.data.StorageOptimizer] and immediately on retention/cap setting changes.
 */
class ArchiveSweep(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val s = settings.value
        val retentionMs = if (s.retentionDays == Int.MAX_VALUE) Long.MAX_VALUE
                          else s.retentionDays.toLong() * 86_400_000L
        if (retentionMs != Long.MAX_VALUE) {
            db.postSnapshotQueries.deleteOlderThan(clock() - retentionMs)
        }
        if (s.maxRecords != Int.MAX_VALUE) {
            db.postSnapshotQueries.deleteByCap(s.maxRecords.toLong())
        }
    }
}
