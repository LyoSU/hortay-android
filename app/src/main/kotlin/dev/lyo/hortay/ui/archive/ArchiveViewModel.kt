package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveFilter
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.ArchivedChannelEntry
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val repo: ArchiveRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(ArchiveFilter())
    val filter: StateFlow<ArchiveFilter> = _filter

    val snapshots: StateFlow<ImmutableList<PostSnapshot>> =
        _filter.flatMapLatest { repo.observe(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    val channels: StateFlow<ImmutableList<ArchivedChannelEntry>> =
        repo.observeChannelIndex()
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    fun setKind(kind: SnapshotKind?) { _filter.value = _filter.value.copy(kind = kind) }
    fun setScope(scope: ArchiveScope?) { _filter.value = _filter.value.copy(scope = scope) }
    fun setQuery(q: String?) { _filter.value = _filter.value.copy(query = q?.takeIf(String::isNotBlank)) }

    fun purge(ids: List<Long>) {
        viewModelScope.launch { repo.purge(ids) }
    }

    fun observeRevisionsFor(snapshot: PostSnapshot): Flow<ImmutableList<PostSnapshot>> =
        repo.observeRevisions(snapshot.chat, snapshot.messageKey)
}
