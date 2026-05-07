package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import org.drinkless.tdlib.TdApi

/**
 * Surface TDLib's network + storage statistics for the Settings UI. Standard transparency
 * surface that Telegram clients expose so the user knows how much data the app moves and
 * how much disk it has accumulated, with a "clear cache" escape hatch.
 */
class StatsRepository(private val td: TdSender) {

    /** Aggregated cellular + Wi-Fi + roaming traffic since TDLib started recording. */
    suspend fun networkUsage(): NetworkUsage {
        val stats = runCatching { td.send(TdApi.GetNetworkStatistics(/* onlyCurrent */ false)) }
            .warnUnlessCancelled(TAG, "networkUsage")
            .getOrNull() ?: return NetworkUsage(0L, 0L, 0L)
        var rx = 0L
        var tx = 0L
        for (entry in stats.entries.orEmpty()) {
            when (entry) {
                is TdApi.NetworkStatisticsEntryFile -> {
                    rx += entry.receivedBytes
                    tx += entry.sentBytes
                }
                is TdApi.NetworkStatisticsEntryCall -> {
                    rx += entry.receivedBytes
                    tx += entry.sentBytes
                }
            }
        }
        return NetworkUsage(rxBytes = rx, txBytes = tx, sinceMs = stats.sinceDate.toLong() * 1000L)
    }

    /** Light-weight storage probe — does not enumerate every file like the full variant. */
    suspend fun storageUsage(): StorageUsage {
        val stats = runCatching { td.send(TdApi.GetStorageStatisticsFast()) }
            .warnUnlessCancelled(TAG, "storageUsage")
            .getOrNull() ?: return StorageUsage(0L, 0L)
        return StorageUsage(
            totalFilesBytes = stats.filesSize,
            databaseSizeBytes = stats.databaseSize + stats.languagePackDatabaseSize + stats.logSize,
        )
    }

    /**
     * Aggressive cache wipe: size=0 + ttl=0 + immunityDelay=0 drives every
     * eviction-driving cap to its strictest setting, so TDLib evicts every file
     * not held by an active in-flight transfer or open file handle. Currently-
     * rendered media survives via TDLib's internal ref-count regardless of these
     * limits. The chat/database is NOT touched — that would log the user out
     * and is surfaced as a separate destructive action.
     *
     * `fileTypes = null` is intentional: per [TdApi.OptimizeStorage]'s javadoc
     * default it leaves thumbnails / profile photos / stickers / wallpapers
     * intact. Telegram's own "Clear local cache" UI does the same — those are
     * tiny files (< 500 KB each) that re-fetch annoyingly on the next session
     * without freeing meaningful disk. A user tapping *"Очистити кеш"* expects
     * the photo / video bulk to disappear, not their sticker packs and
     * contacts' profile photos.
     */
    suspend fun clearCache(): Result<Unit> = runCatching {
        td.send(
            TdApi.OptimizeStorage(
                /* size */ 0L,
                /* ttl */ 0,
                /* count */ 0,
                /* immunityDelay */ 0,
                /* fileTypes */ null,
                /* chatIds */ null,
                /* excludeChatIds */ null,
                /* returnDeletedFileStatistics */ false,
                /* chatLimit */ 0,
            ),
        )
        Unit
    }.warnUnlessCancelled(TAG, "clearCache")

    /** Zero out the lifetime traffic counters. Storage is unaffected. */
    suspend fun resetTrafficStats(): Result<Unit> = runCatching {
        td.send(TdApi.ResetNetworkStatistics())
        Unit
    }.warnUnlessCancelled(TAG, "resetTrafficStats")

    private companion object {
        const val TAG = "StatsRepository"
    }
}

@Immutable
data class NetworkUsage(val rxBytes: Long, val txBytes: Long, val sinceMs: Long)

@Immutable
data class StorageUsage(val totalFilesBytes: Long, val databaseSizeBytes: Long)
