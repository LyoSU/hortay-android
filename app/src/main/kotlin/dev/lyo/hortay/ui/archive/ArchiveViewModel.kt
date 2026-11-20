package dev.lyo.hortay.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.archive.ArchiveEntry
import dev.lyo.hortay.data.archive.ArchiveEntryStatus
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.data.archive.SourceKind
import dev.lyo.hortay.data.archive.PostSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * @param chatAvatar the live avatar of a TDLib chat, `(smallFileId, minithumb)`, or null when
 *   the chat is unknown (`ChannelDirectory.chatAvatar`: registry, then TDLib's offline
 *   `GetChat`). Entries of an unknown chat keep the minithumb the archive stored.
 */
class ArchiveViewModel(
    private val repo: ArchiveRepository,
    settings: StateFlow<ArchiveSettings>,
    private val chatAvatar: suspend (chatId: Long) -> Pair<Int?, ByteArray?>? = { null },
) : ViewModel() {

    private val _filter = MutableStateFlow(ArchiveListFilter())
    val filter: StateFlow<ArchiveListFilter> = _filter

    private val _avatars = MutableStateFlow<PersistentMap<ChatRef, ArchiveAvatar>>(persistentMapOf())

    /** Live channel avatars of the listed TDLib chats, filled in as they resolve. */
    val avatars: StateFlow<ImmutableMap<ChatRef, ArchiveAvatar>> = _avatars

    /** Chats already looked up (found or not), so each is resolved once per screen. */
    private val avatarLookups = HashSet<ChatRef>()

    /** Null until the first archive read lands. */
    val state: StateFlow<ArchiveListState?> =
        combine(
            repo.observeEntries(),
            _filter,
            settings.map { it.enabled }.distinctUntilChanged(),
        ) { entries, filter, enabled ->
            buildArchiveListState(entries, filter, enabled, TimeZone.getDefault())
        }
            .flowOn(Dispatchers.Default)
            .onEach { s -> s?.let(::resolveAvatars) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun resolveAvatars(state: ArchiveListState) {
        val chats = state.items.mapNotNullTo(LinkedHashSet()) { item ->
            (item as? ArchiveListItem.Entry)?.entry?.chat?.takeIf { it.kind == SourceKind.TDLIB }
        }
        val fresh = chats.filter(avatarLookups::add)
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            for (chat in fresh) {
                val avatar = chat.key.toLongOrNull()?.let { chatAvatar(it) }
                    ?.takeIf { (fileId, thumb) -> fileId != null || thumb != null }
                    ?.let { (fileId, thumb) -> ArchiveAvatar(fileId, thumb) }
                    ?: continue
                _avatars.update { it.put(chat, avatar) }
            }
        }
    }

    fun setStatus(status: ArchiveEntryStatus?) = _filter.update { it.copy(status = status) }
    fun setScope(scope: ArchiveScope) = _filter.update { it.copy(scope = scope) }
    fun setQuery(query: String) = _filter.update { it.copy(query = query) }

    /** Removes every stored row of [entry] (all album members, all versions). */
    fun remove(entry: ArchiveEntry) {
        viewModelScope.launch { repo.purge(entry.snapshotIds) }
    }

    fun observeRevisions(entry: ArchiveEntry): Flow<ImmutableList<PostSnapshot>> =
        repo.observeRevisions(entry.chat, entry.messageKey)

    private companion object {
        // Same linger as the timeline VMs: survives a rotation / brief recomposition gap
        // without re-running the archive queries, but stops them once the screen is gone.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
