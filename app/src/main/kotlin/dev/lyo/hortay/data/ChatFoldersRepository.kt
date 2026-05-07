package dev.lyo.hortay.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirror of the user's Telegram chat folders ("filters"). The folder *list* is published as a
 * [StateFlow] so the top-bar tabs reflect changes without a manual refresh; full per-folder
 * rules ([TdApi.ChatFolder]) are fetched lazily and cached because they're only needed when
 * the user actually selects a folder.
 *
 * We deliberately do NOT cache "is this chat in folder X" — folder membership is computed
 * from the cached [TdApi.ChatFolder] on demand. The folder is a tiny struct, so the
 * computation is cheap and always fresh against the latest TDLib state.
 */
class ChatFoldersRepository(
    private val td: TdSender,
    scope: CoroutineScope,
) {
    private val _folders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
    val folders: StateFlow<List<TdApi.ChatFolderInfo>> = _folders.asStateFlow()

    private val fullFolders = ConcurrentHashMap<Int, TdApi.ChatFolder>()

    init {
        // TDLib emits an UpdateChatFolders shortly after auth, then again whenever the user
        // creates / renames / reorders folders in any client. Keep our state mirror in sync;
        // also evict the per-folder cache since the included/excluded ids may have changed.
        td.updates
            .filterIsInstance<TdApi.UpdateChatFolders>()
            .onEach { upd ->
                _folders.value = upd.chatFolders.orEmpty().toList()
                fullFolders.clear()
            }
            .launchIn(scope)
    }

    /**
     * Resolve the full per-folder rules. Cached after the first hit; cleared whenever
     * [TdApi.UpdateChatFolders] arrives. Returns null when TDLib rejects (e.g. the folder
     * was deleted between the list emit and the user selecting it).
     */
    suspend fun fullFolder(folderId: Int): TdApi.ChatFolder? {
        fullFolders[folderId]?.let { return it }
        val full = runCatching { td.send(TdApi.GetChatFolder(folderId)) }
            .warnUnlessCancelled(TAG, "fullFolder($folderId)")
            .getOrNull() ?: return null
        fullFolders[folderId] = full
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
     * Wipe the folder list + per-folder rule cache. Called from [AppGraph]
     * on logout — folder ids and chat-id membership are account-scoped, and
     * TDLib will fire fresh [TdApi.UpdateChatFolders] for the new account
     * after re-sign-in to repopulate.
     */
    fun clear() {
        _folders.value = emptyList()
        fullFolders.clear()
    }

    private companion object { const val TAG = "ChatFoldersRepository" }
}
