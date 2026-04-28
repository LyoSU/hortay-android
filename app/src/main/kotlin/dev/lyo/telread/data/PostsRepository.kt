package dev.lyo.telread.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
    private val scope: CoroutineScope,
) {

    private val refreshMutex = Mutex()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    private val _posts = MutableStateFlow<List<TimelinePost>>(emptyList())
    val posts: StateFlow<List<TimelinePost>> = _posts.asStateFlow()

    init {
        // Live feed: any new channel post arrives via UpdateNewMessage and is folded in.
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>()
            .onEach { update -> handleNewMessage(update.message) }
            .launchIn(scope)

        // Server-side counter sync: views, reactions, comment counts.
        td.updates.filterIsInstance<TdApi.UpdateMessageInteractionInfo>()
            .onEach { update -> handleInteractionInfo(update) }
            .launchIn(scope)

        // Edits surface as a new editDate; we just stamp it onto the post.
        td.updates.filterIsInstance<TdApi.UpdateMessageEdited>()
            .onEach { update -> handleEdited(update) }
            .launchIn(scope)

        // Mods can delete posts; drop them from the timeline immediately.
        td.updates.filterIsInstance<TdApi.UpdateDeleteMessages>()
            .onEach { update -> handleDeleted(update) }
            .launchIn(scope)
    }

    suspend fun refresh(limitPerChannel: Int = 20): Result<Unit> = refreshMutex.withLock {
        runCatching { refreshLocked(limitPerChannel) }.onFailure {
            Log.w(TAG, "refresh failed", it)
        }
    }

    private fun handleNewMessage(message: TdApi.Message) {
        scope.launch {
            val chat = chatCache[message.chatId] ?: runCatching { td.send(TdApi.GetChat(message.chatId)) }
                .getOrNull()
                ?.also { chatCache[it.id] = it }
                ?: return@launch
            if (!chat.isChannel()) return@launch

            val post = MessageMapper.toTimelinePost(message, chat)
            if (post.content is PostContent.Unsupported) return@launch

            _posts.update { current ->
                val merged = current + post
                PostFilterStrategy.apply(merged).take(MAX_FEED_SIZE)
            }
        }
    }

    private fun handleInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        val info = update.interactionInfo
        _posts.update { current ->
            current.map { post ->
                if (post.chatId != update.chatId || post.id != update.messageId) return@map post
                post.copy(
                    views = info?.viewCount ?: post.views,
                    reactions = info?.reactions?.let(::reactionsFromUpdate) ?: post.reactions,
                    commentCount = info?.replyInfo?.replyCount ?: post.commentCount,
                    // ^ keep current value when interaction info arrives without replyInfo;
                    //   fresh `null` only happens at first map() in MessageMapper.
                )
            }
        }
    }

    private fun handleEdited(update: TdApi.UpdateMessageEdited) {
        _posts.update { current ->
            current.map { post ->
                if (post.chatId == update.chatId && post.id == update.messageId) {
                    post.copy(editDate = update.editDate.toLong() * 1000L)
                } else post
            }
        }
    }

    private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
        if (!update.isPermanent) return
        val ids = update.messageIds.toHashSet()
        _posts.update { current ->
            current.filterNot { it.chatId == update.chatId && it.id in ids }
        }
    }

    private fun reactionsFromUpdate(reactions: TdApi.MessageReactions): Reactions {
        val list = reactions.reactions.orEmpty()
        if (list.isEmpty()) return Reactions(0, emptyList())
        val total = list.sumOf { it.totalCount }
        val emojis = list.take(3).mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
        return Reactions(total, emojis)
    }

    private suspend fun refreshLocked(limitPerChannel: Int) {
        runCatching { td.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_LIST_HINT)) }

        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_LIST_LIMIT)).chatIds

        val raw = mutableListOf<TimelinePost>()
        for (chatId in chatIds) {
            val chat = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull() ?: continue
            chatCache[chatId] = chat
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
        const val MAX_FEED_SIZE = 1_000
    }
}

private fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}
