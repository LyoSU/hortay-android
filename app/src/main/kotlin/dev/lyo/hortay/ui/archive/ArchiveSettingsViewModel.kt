package dev.lyo.hortay.ui.archive

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // The stateIn copy above may not have caught the write yet; sweep with the stored value.
            sweep.run(store.flow.first())
        }
    }

    fun setMaxRecords(n: Int) {
        viewModelScope.launch {
            store.setMaxRecords(n)
            sweep.run(store.flow.first())
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
        // Read-modify-write inside one DataStore edit: building the set from [settings] here
        // let two quick toggles each overwrite the other's change.
        viewModelScope.launch { store.setChatExcluded(chat, excluded) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clear() }
    }

    private val _exporting = MutableStateFlow(false)

    /** True while an export is being written; a second request is ignored until it ends. */
    val exporting: StateFlow<Boolean> = _exporting

    private val _exportResults = Channel<ExportResult>(Channel.BUFFERED)

    /** One outcome per finished export, kept until the screen collects it. */
    val exportResults: Flow<ExportResult> = _exportResults.receiveAsFlow()

    /**
     * Writes the archive to the document at [uri] off the main thread.
     *
     * Runs in [viewModelScope], not the screen's composition scope, so moving on to another
     * screen does not stop it; the write itself is [NonCancellable], so even closing this
     * page lets the file finish instead of leaving it cut off. A failed export deletes the
     * partial document when the provider allows it.
     */
    fun export(resolver: ContentResolver, uri: Uri) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            val result = try {
                val count = withContext(NonCancellable + Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri)
                        ?: throw java.io.IOException("No output stream for the export document")
                    out.use { repo.exportTo(it) }
                }
                ExportResult.Done(count)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Archive export failed", e)
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
                ExportResult.Failed
            } finally {
                _exporting.value = false
            }
            _exportResults.trySend(result)
        }
    }

    private companion object {
        const val TAG = "ArchiveSettings"
        const val COUNTS_DEBOUNCE_MS = 500L
        const val STORAGE_BYTES_DEBOUNCE_MS = 2000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** How an archive export ended. */
sealed interface ExportResult {
    data class Done(val records: Int) : ExportResult
    data object Failed : ExportResult
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
