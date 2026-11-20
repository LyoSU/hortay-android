package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Archive housekeeping, run from [dev.lyo.hortay.data.StorageOptimizer] when the app comes
 * to the foreground and from the archive settings when retention or the cap change.
 *
 * In order:
 *  1. [dedupeVersions] — folds phantom versions stored under an older canonical form and
 *     upgrades their hashes (a no-op once every version carries the current hash version).
 *  2. Retention — removes whole messages whose newest event (`last_event_at_ms`: a capture
 *     or the deletion) is older than the retention period. v1 dropped single rows by their
 *     capture time, which could strip an edited post of its original version.
 *  3. Cap — keeps at most `maxRecords` messages; see [evictToCap] for who goes first.
 *  4. [ArchivedMediaStore.sweepOrphans] — removes media files no version references any more
 *     (past a one-hour grace), which is how steps 1–3 and purges free disk space.
 *
 * Steps 1–3 run under [writeLock] (the repository's capture lock), so a capture never
 * interleaves with a removal. The orphan sweep runs outside it: its grace window covers a
 * file copied for a version that is not inserted yet.
 */
class ArchiveSweep(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val mediaStore: ArchivedMediaStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writeLock: Mutex = Mutex(),
    private val available: () -> Boolean = { true },
) {
    private val runLock = Mutex()

    suspend fun run() = runLock.withLock {
        withContext(Dispatchers.IO) {
            if (!available()) return@withContext
            val s = settings.value
            writeLock.withLock {
                db.dedupeVersions()
                if (s.retentionDays != Int.MAX_VALUE) {
                    db.dropMessagesOlderThan(clock() - s.retentionDays.toLong() * DAY_MS)
                }
                if (s.maxRecords != Int.MAX_VALUE) db.evictToCap(s.maxRecords)
            }
            mediaStore?.sweepOrphans()
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
