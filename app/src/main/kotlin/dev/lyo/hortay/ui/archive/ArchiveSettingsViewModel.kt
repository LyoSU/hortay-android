package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.ChatRef
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArchiveSettingsViewModel(
    private val store: ArchiveSettingsStore,
    private val repo: ArchiveRepository,
    private val sweep: ArchiveSweep,
) : ViewModel() {

    val settings: StateFlow<ArchiveSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveSettings.DEFAULT)

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

    fun setExcludedChats(refs: Collection<ChatRef>) {
        viewModelScope.launch { store.setExcludedChats(refs) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clear() }
    }
}
