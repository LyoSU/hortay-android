package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class ArchiveSettings(
    val enabled: Boolean = false,
    val onboardingSeen: Boolean = false,
    val retentionDays: Int = 30,
    val maxRecords: Int = 5000,
    val excludedChats: PersistentSet<ChatRef> = persistentSetOf(),
    val captureEdits: Boolean = true,
    val captureDeletes: Boolean = true,
) {
    /**
     * Whether a deletion in [chat] is recorded under these settings: the archive is on, the
     * "Deletions" capture switch is on, and the chat is not in "Channels without archive".
     * The same gate `ArchiveRepository.captureTdlibDelete` applies before it marks anything.
     */
    fun keepsDeletionsFor(chat: ChatRef): Boolean = enabled && captureDeletes && chat !in excludedChats

    companion object {
        val DEFAULT = ArchiveSettings()
        val RETENTION_OPTIONS: PersistentList<Int> = persistentListOf(7, 30, 90, Int.MAX_VALUE)
        val MAX_RECORDS_OPTIONS: PersistentList<Int> = persistentListOf(1000, 5000, 10_000, Int.MAX_VALUE)
    }
}
