package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.ArchivedChannelEntry
import dev.lyo.hortay.data.archive.ChatRef
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArchiveSettingsViewModel(
    private val store: ArchiveSettingsStore,
    private val repo: ArchiveRepository,
    private val sweep: ArchiveSweep,
) : ViewModel() {

    val settings: StateFlow<ArchiveSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveSettings.DEFAULT)

    /**
     * Deleted / edited message counts for the "Open archive" row — messages as the browser
     * lists them, not stored rows. Null until the first read. Re-reads are debounced so a
     * burst of captures while the page is open doesn't rebuild the entry list per insert.
     */
    val counts: StateFlow<ArchiveCounts?> =
        repo.observeEntries()
            .debounceAfterFirst(COUNTS_DEBOUNCE_MS)
            .map { archiveCounts(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Bytes on disk, snapshot rows plus copied media files. Null until the first read. */
    val storageBytes: StateFlow<Long?> =
        repo.observeStorageBytes()
            .debounceAfterFirst(STORAGE_BYTES_DEBOUNCE_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Channels the archive has seen — the candidates for exclusion. */
    val channels: StateFlow<ImmutableList<ArchivedChannelEntry>> =
        repo.observeChannelIndex()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentListOf())

    fun confirmEnableFromOnboarding() {
        viewModelScope.launch {
            store.setOnboardingSeen(true)
            store.setEnabled(true)
        }
    }

    fun disable(deleteArchive: Boolean) {
        viewModelScope.launch {
            store.setEnabled(false)
            if (deleteArchive) repo.clear()
        }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            store.setRetentionDays(days)
            sweep.run()
        }
    }

    fun setMaxRecords(n: Int) {
        viewModelScope.launch {
            store.setMaxRecords(n)
            sweep.run()
        }
    }

    fun setCaptureEdits(v: Boolean) {
        viewModelScope.launch { store.setCaptureEdits(v) }
    }

    fun setCaptureDeletes(v: Boolean) {
        viewModelScope.launch { store.setCaptureDeletes(v) }
    }

    /** Adds [chat] to, or removes it from, the channels the archive skips. */
    fun setExcluded(chat: ChatRef, excluded: Boolean) {
        val current = settings.value.excludedChats
        val next = if (excluded) current.add(chat) else current.remove(chat)
        if (next === current) return
        viewModelScope.launch { store.setExcludedChats(next) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clear() }
    }

    /**
     * Streams the archive into [out] and returns the number of records written.
     * The caller is responsible for opening / closing the stream — we don't
     * `.use {}` here because some callers (Storage Access Framework Uri) close
     * the stream themselves on close.
     */
    suspend fun exportTo(out: java.io.OutputStream): Int = repo.exportTo(out)

    private companion object {
        const val COUNTS_DEBOUNCE_MS = 500L
        const val STORAGE_BYTES_DEBOUNCE_MS = 2000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The first value passes at once (no placeholder on open); later bursts settle for [ms]. */
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.debounceAfterFirst(ms: Long): Flow<T> = flow {
    var first = true
    emitAll(
        this@debounceAfterFirst.debounce {
            if (first) {
                first = false
                0L
            } else {
                ms
            }
        },
    )
}
