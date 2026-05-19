package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirror of the user's Telegram chat folders ("filters"). Both the folder *list*
 * ([folders], lightweight `ChatFolderInfo`) and the per-folder *rules* ([fullFolders],
 * the heavier `ChatFolder` with included/excluded chat ids) are published as
 * [StateFlow]s so the top-bar tabs can reflect changes without a manual refresh AND
 * the UI can pre-filter folders that would land on an empty tab (no subscribed
 * channels match, or the rule is equivalent to the "All" scope) without paying a
 * per-tap RPC.
 *
 * Eager resolution of full rules is keyed on [TdApi.UpdateChatFolders]: that update
 * is rare (only when the user creates / renames / reorders folders in any client),
 * so doing a parallel `GetChatFolder × N` once per emission is well within the
 * FLOOD_WAIT budget — TDLib serves from local state when it can.
 *
 * Per-folder *membership* (which concrete chat ids match the folder rule right now)
 * is delegated to TDLib via `LoadChats(ChatListFolder)` + `GetChats(ChatListFolder)`:
 * TDLib documents these as offline-when-not-a-bot and already applies every
 * include/exclude/pinned/excludeArchived/excludeMuted/excludeRead rule itself.
 * Reimplementing the rule evaluator in-process would silently drift if Telegram
 * ever adds a new exclude_* flag (it has shipped several in TDLib history) — by
 * round-tripping the resolved id list we always agree with the official client.
 *
 * The id-set cache is maintained by:
 *   • [TdApi.UpdateChatFolders] — folder structure or rule changed; every cached
 *     set is now potentially stale.
 *   • [TdApi.UpdateChatAddedToList] / [TdApi.UpdateChatRemovedFromList] targeting
 *     a [TdApi.ChatListFolder] — a single membership flipped; the chat id is
 *     added to / removed from the cached set INCREMENTALLY via [MutableStateFlow.update].
 *     Eviction here used to race with the eager warm-up: TDLib fires a burst of
 *     `UpdateChatAddedToList` while `LoadChats(ChatListFolder)` drains, and an
 *     eviction landing *after* the warm-up wrote `(id → set)` left the entry
 *     `null`. The tab-visibility predicate in [TimelineScreen] treats `null` as
 *     "not yet resolved → keep the chip", so empty / out-of-scope folders stayed
 *     visible until the user tapped them — the tap path called [folderChatIds]
 *     under the mutex and finally let the chip drop. Incremental updates kill
 *     the race: the cache is monotonically maintained from the warm-up snapshot
 *     onwards. Updates that arrive before the entry exists are ignored (warm-up
 *     is the source of truth for initial population).
 */
class ChatFoldersRepository(
    private val td: TdSender,
    private val scope: CoroutineScope,
) {
    private val _folders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
    val folders: StateFlow<List<TdApi.ChatFolderInfo>> = _folders.asStateFlow()

    private val _fullFolders = MutableStateFlow<Map<Int, TdApi.ChatFolder>>(emptyMap())
    val fullFolders: StateFlow<Map<Int, TdApi.ChatFolder>> = _fullFolders.asStateFlow()

    private val fullFoldersCache = ConcurrentHashMap<Int, TdApi.ChatFolder>()

    private val _folderChatIds = MutableStateFlow<Map<Int, Set<Long>>>(emptyMap())
    val folderChatIds: StateFlow<Map<Int, Set<Long>>> = _folderChatIds.asStateFlow()

    // Serialise the drain+get pair per folder so two concurrent callers don't both
    // pay the LoadChats round-trip when the answer is already in flight.
    private val folderChatIdsMutex = ConcurrentHashMap<Int, Mutex>()

    init {
        // TDLib emits an UpdateChatFolders shortly after auth, then again whenever the user
        // creates / renames / reorders folders in any client. Keep our state mirror in sync;
        // evict the per-folder cache (ids may have moved) and re-resolve every folder so the
        // [fullFolders] map matches the new [folders] list.
        td.updates
            .filterIsInstance<TdApi.UpdateChatFolders>()
            .onEach { upd ->
                val list = upd.chatFolders.orEmpty().toList()
                _folders.value = list
                fullFoldersCache.clear()
                _fullFolders.value = emptyMap()
                _folderChatIds.value = emptyMap()
                scope.launch { resolveAll(list) }
            }
            .launchIn(scope)

        // Live membership flips: TDLib fires Update{Added,Removed}ToList both as part
        // of the LoadChats drain that the warm-up triggers AND for runtime changes
        // (user pins/excludes a chat in any client). Update incrementally rather than
        // evicting the whole entry — an eviction landing after the warm-up wrote
        // `(id → set)` would silently re-null the cache and keep an empty folder
        // chip visible until the user tapped it. CAS via [MutableStateFlow.update]
        // serialises concurrent writes from this collector and the warm-up. Updates
        // for folders that haven't been warmed up yet are ignored — the warm-up's
        // `GetChats` snapshot is the source of truth for initial population.
        td.updates
            .filterIsInstance<TdApi.UpdateChatAddedToList>()
            .onEach { update ->
                val folderId = (update.chatList as? TdApi.ChatListFolder)?.chatFolderId ?: return@onEach
                _folderChatIds.update { current ->
                    val existing = current[folderId] ?: return@update current
                    if (update.chatId in existing) current
                    else current + (folderId to (existing + update.chatId))
                }
            }
            .launchIn(scope)

        td.updates
            .filterIsInstance<TdApi.UpdateChatRemovedFromList>()
            .onEach { update ->
                val folderId = (update.chatList as? TdApi.ChatListFolder)?.chatFolderId ?: return@onEach
                _folderChatIds.update { current ->
                    val existing = current[folderId] ?: return@update current
                    if (update.chatId !in existing) current
                    else current + (folderId to (existing - update.chatId))
                }
            }
            .launchIn(scope)
    }

    private suspend fun resolveAll(list: List<TdApi.ChatFolderInfo>) {
        if (list.isEmpty()) return
        val resolved = coroutineScope {
            list.map { info ->
                async {
                    val full = runCatching { td.send(TdApi.GetChatFolder(info.id)) }
                        .warnUnlessCancelled(TAG, "resolveAll(${info.id})")
                        .getOrNull()
                    if (full != null) fullFoldersCache[info.id] = full
                    info.id to full
                }
            }.awaitAll()
        }
        _fullFolders.value = resolved
            .mapNotNull { (id, full) -> full?.let { id to it } }
            .toMap()
        // Warm the membership cache so the tab bar can hide empty folders on the
        // first composition that sees [fullFolders] populated — without this the
        // visibility check has nothing to test against and falls back to "keep".
        list.forEach { info ->
            scope.launch { runCatching { folderChatIds(info.id) } }
        }
    }

    /**
     * Resolve the full per-folder rules. Cached after the first hit; cleared whenever
     * [TdApi.UpdateChatFolders] arrives. Returns null when TDLib rejects (e.g. the folder
     * was deleted between the list emit and the user selecting it). Prefer [fullFolders]
     * for reactive use — this remains for one-shot suspending callers.
     */
    suspend fun fullFolder(folderId: Int): TdApi.ChatFolder? {
        fullFoldersCache[folderId]?.let { return it }
        val full = runCatching { td.send(TdApi.GetChatFolder(folderId)) }
            .warnUnlessCancelled(TAG, "fullFolder($folderId)")
            .getOrNull() ?: return null
        fullFoldersCache[folderId] = full
        _fullFolders.update { it + (folderId to full) }
        return full
    }

    /**
     * Resolve the chat-id set that belongs to [folderId] right now, delegating the rule
     * evaluation (include/exclude/pinned/excludeArchived/excludeMuted/excludeRead, plus
     * the include* category flags) to TDLib. Pattern mirrors `PostsRepository.refreshLocked`
     * for [TdApi.ChatListMain]: drain via [TdApi.LoadChats] until TDLib stops paging,
     * then snapshot via [TdApi.GetChats]. Cached and evicted as documented on the class.
     */
    suspend fun folderChatIds(folderId: Int): Set<Long> {
        _folderChatIds.value[folderId]?.let { return it }
        val mutex = folderChatIdsMutex.getOrPut(folderId) { Mutex() }
        return mutex.withLock {
            _folderChatIds.value[folderId]?.let { return@withLock it }
            val list = TdApi.ChatListFolder(folderId)
            drainChatList(list)
            val ids: Set<Long> = runCatching { td.send(TdApi.GetChats(list, Int.MAX_VALUE)) }
                .warnUnlessCancelled(TAG, "folderChatIds($folderId)")
                .getOrNull()
                ?.chatIds
                ?.toHashSet()
                ?: emptySet()
            _folderChatIds.update { it + (folderId to ids) }
            ids
        }
    }

    /**
     * Drain `LoadChats(ChatListFolder)` until TDLib runs out of pages — same shape as
     * `PostsRepository.drainChatList`. 404 → no more chats, terminate. Other errors get
     * retried for a bounded number of iterations.
     */
    private suspend fun drainChatList(list: TdApi.ChatList) {
        repeat(MAX_LOAD_CHATS_PAGES) {
            val res = runCatching { td.send(TdApi.LoadChats(list, CHAT_LIST_HINT)) }
            val err = res.exceptionOrNull()
            if (err is TdClient.TdException && err.code == 404) return
        }
    }

    /**
     * True when the folder's effective channel-membership is indistinguishable from the
     * "All" scope: it pulls in every channel by rule with nothing pinned, nothing excluded,
     * and no archive/mute/read filter that would narrow the set. Hortay only renders
     * channels, so other include* flags (groups/contacts/bots) don't change what the user
     * sees — a folder with `includeChannels=true` and no narrowing rules duplicates the
     * default tab and is hidden.
     */
    fun isEquivalentToAll(folder: TdApi.ChatFolder): Boolean {
        if (!folder.includeChannels) return false
        if ((folder.pinnedChatIds?.size ?: 0) > 0) return false
        if ((folder.includedChatIds?.size ?: 0) > 0) return false
        if ((folder.excludedChatIds?.size ?: 0) > 0) return false
        if (folder.excludeArchived) return false
        if (folder.excludeMuted) return false
        if (folder.excludeRead) return false
        return true
    }

    /**
     * Wipe the folder list + per-folder rule cache. Called from [AppGraph]
     * on logout — folder ids and chat-id membership are account-scoped, and
     * TDLib will fire fresh [TdApi.UpdateChatFolders] for the new account
     * after re-sign-in to repopulate.
     */
    fun clear() {
        _folders.value = emptyList()
        _fullFolders.value = emptyMap()
        fullFoldersCache.clear()
        _folderChatIds.value = emptyMap()
        folderChatIdsMutex.clear()
    }

    private companion object {
        const val TAG = "ChatFoldersRepository"
        const val CHAT_LIST_HINT = 200
        const val MAX_LOAD_CHATS_PAGES = 10
    }
}
