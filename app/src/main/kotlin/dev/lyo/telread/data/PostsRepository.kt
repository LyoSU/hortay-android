package dev.lyo.telread.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi

/**
 * Twitter-style chronological feed merged from every channel chat the user follows.
 *
 * Pull model:
 *   1. [TdApi.LoadChats] hints the daemon to fetch chat list pages.
 *   2. [TdApi.GetChats] returns cached chat IDs (local-only, fast).
 *   3. For each *channel* chat, [TdApi.GetChatHistory] fetches the latest N messages.
 *   4. Raw messages → [MessageMapper] → [PostFilterStrategy] → [posts].
 *
 * Concurrency: a single [Mutex] guards refreshes so that pull-to-refresh + incremental
 * updates from TDLib never interleave and produce phantom duplicates.
 */
class PostsRepository(
    private val td: TdClient,
    @Suppress("unused") scope: CoroutineScope,
) {

    private val refreshMutex = Mutex()

    private val _posts = MutableStateFlow<List<TimelinePost>>(emptyList())
    val posts: StateFlow<List<TimelinePost>> = _posts.asStateFlow()

    suspend fun refresh(limitPerChannel: Int = 20): Result<Unit> = refreshMutex.withLock {
        runCatching { refreshLocked(limitPerChannel) }.onFailure {
            Log.w(TAG, "refresh failed", it)
        }
    }

    private suspend fun refreshLocked(limitPerChannel: Int) {
        runCatching { td.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_LIST_HINT)) }

        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_LIST_LIMIT)).chatIds

        val raw = mutableListOf<TimelinePost>()
        for (chatId in chatIds) {
            val chat = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull() ?: continue
            if (!chat.isChannel()) continue

            val history = runCatching {
                td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limitPerChannel, false))
            }.getOrNull() ?: continue

            history.messages?.forEach { message ->
                raw += MessageMapper.toTimelinePost(message, chat)
            }
        }

        _posts.value = PostFilterStrategy.apply(raw)
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val CHAT_LIST_LIMIT = 500
    }
}

private fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}
