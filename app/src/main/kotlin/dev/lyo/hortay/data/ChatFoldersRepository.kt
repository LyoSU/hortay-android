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
import kotlinx.coroutines.launch
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
                scope.launch { resolveAll(list) }
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
        _fullFolders.value = _fullFolders.value + (folderId to full)
        return full
    }

    /**
     * Membership predicate for a channel post's chat. We only show channels in this app, so
     * the include* booleans collapse to a single check on `includeChannels`. A chat is in
     * the folder when it is explicitly pinned / included, OR the folder includes channels
     * by rule — minus anything explicitly excluded. Mirrors what the official Telegram
     * client does for the chat list.
     */
    fun isChannelInFolder(folder: TdApi.ChatFolder, chatId: Long): Boolean {
        if (folder.excludedChatIds?.let { chatId in it } == true) return false
        val pinned = folder.pinnedChatIds?.let { chatId in it } == true
        val included = folder.includedChatIds?.let { chatId in it } == true
        return pinned || included || folder.includeChannels
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
    }

    private companion object { const val TAG = "ChatFoldersRepository" }
}
