package dev.lyo.telread.data

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.drinkless.tdlib.TdApi

/**
 * Live discussion-thread feed for a channel post. Emits a [ThreadState] that updates as new
 * comments arrive, reactions/views change, or messages get deleted upstream — without ever
 * requiring the consumer to refetch.
 */
class CommentsRepository(private val td: TdClient) {

    sealed interface ThreadState {
        data object Loading : ThreadState
        data class Ready(val rows: List<CommentRow>, val threadChatId: Long) : ThreadState
        data class Error(val message: String) : ThreadState
    }

    /**
     * Cold flow: starts loading when collected, stops everything when collection ends.
     * Subscribes to [TdApi.UpdateNewMessage], [TdApi.UpdateMessageInteractionInfo],
     * [TdApi.UpdateDeleteMessages] and [TdApi.UpdateMessageContent] limited to the linked
     * discussion chat so the displayed thread stays in sync without a manual refresh.
     */
    fun observeThread(chatId: Long, messageId: Long, limit: Int = 200): Flow<ThreadState> = callbackFlow {
        trySend(ThreadState.Loading)

        val info = runCatching { td.send(TdApi.GetMessageThread(chatId, messageId)) }
            .getOrElse {
                trySend(ThreadState.Error("Обговорення недоступне."))
                close()
                return@callbackFlow
            }

        val threadChatId = info.chatId
        val rootId = info.messageThreadId

        // NOTE: do NOT issue OpenChat here. OpenChat is a "user is actively viewing this
        // chat right now" signal — it's the UI's job to pair it with CloseChat when the
        // screen leaves composition (see CommentsScreen). Read APIs like
        // GetMessageThreadHistory don't require it.

        val raw = mutableListOf<TdApi.Message>()
        var fromId = 0L
        while (raw.size < limit) {
            val batch = runCatching {
                td.send(TdApi.GetMessageThreadHistory(threadChatId, rootId, fromId, 0, BATCH_SIZE))
            }.warnUnlessCancelled(TAG, "threadHistory($threadChatId)").getOrNull() ?: break
            val msgs = batch.messages.orEmpty()
            if (msgs.isEmpty()) break
            raw += msgs
            fromId = msgs.last().id
        }

        // Drop the channel-post mirror; only conversation messages remain.
        val live = raw.filter { it.id != rootId }.toMutableList()
        suspend fun pushSnapshot() {
            trySend(ThreadState.Ready(buildTree(live, rootId), threadChatId))
        }
        pushSnapshot()

        // Real-time fan-in. We share the same updates flow as the rest of the app; filter
        // strictly to threadChatId so we never touch unrelated chats.
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>()
            .onEach { upd ->
                if (upd.message.chatId != threadChatId) return@onEach
                if (upd.message.id == rootId) return@onEach
                if (live.any { it.id == upd.message.id }) return@onEach
                live += upd.message
                pushSnapshot()
            }
            .launchIn(this)

        td.updates.filterIsInstance<TdApi.UpdateMessageInteractionInfo>()
            .onEach { upd ->
                if (upd.chatId != threadChatId) return@onEach
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) return@onEach
                live[idx].interactionInfo = upd.interactionInfo
                pushSnapshot()
            }
            .launchIn(this)

        td.updates.filterIsInstance<TdApi.UpdateMessageContent>()
            .onEach { upd ->
                if (upd.chatId != threadChatId) return@onEach
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) return@onEach
                live[idx].content = upd.newContent
                pushSnapshot()
            }
            .launchIn(this)

        td.updates.filterIsInstance<TdApi.UpdateDeleteMessages>()
            .onEach { upd ->
                if (upd.chatId != threadChatId || !upd.isPermanent) return@onEach
                val ids = upd.messageIds.toHashSet()
                val before = live.size
                live.removeAll { it.id in ids }
                if (live.size != before) pushSnapshot()
            }
            .launchIn(this)

        awaitClose { /* OpenChat/CloseChat is paired in the UI layer (CommentsScreen). */ }
    }

    suspend fun openThread(threadChatId: Long) {
        runCatching { td.send(TdApi.OpenChat(threadChatId)) }
            .warnUnlessCancelled(TAG, "openThread($threadChatId)")
    }

    suspend fun closeThread(threadChatId: Long) {
        runCatching { td.send(TdApi.CloseChat(threadChatId)) }
            .warnUnlessCancelled(TAG, "closeThread($threadChatId)")
    }

    suspend fun viewMessages(threadChatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        runCatching {
            td.send(
                TdApi.ViewMessages(threadChatId, messageIds.toLongArray(), null, /* forceRead */ true),
            )
        }.warnUnlessCancelled(TAG, "viewMessages($threadChatId)")
    }

    private suspend fun buildTree(messages: List<TdApi.Message>, rootMessageId: Long): List<CommentRow> {
        if (messages.isEmpty()) return emptyList()

        val byId: Map<Long, TdApi.Message> = messages.associateBy { it.id }
        val children: Map<Long, List<TdApi.Message>> = messages.groupBy { msg ->
            val replyId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
            if (replyId != null && replyId != rootMessageId && replyId in byId) replyId else 0L
        }

        val authorCache = mutableMapOf<Long, AuthorInfo>()
        val rows = mutableListOf<CommentRow>()

        suspend fun walk(parentId: Long, depth: Int) {
            val siblings = children[parentId].orEmpty().sortedBy { it.date }
            siblings.forEachIndexed { idx, msg ->
                rows += CommentRow(
                    comment = toComment(msg, authorCache),
                    depth = depth.coerceAtMost(MAX_DEPTH),
                    isLastSibling = idx == siblings.lastIndex,
                )
                walk(msg.id, depth + 1)
            }
        }
        walk(0L, 0)

        return rows
    }

    private suspend fun toComment(msg: TdApi.Message, cache: MutableMap<Long, AuthorInfo>): Comment {
        val author = authorInfoFor(msg, cache)
        val parentId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
        return Comment(
            id = msg.id,
            parentId = parentId,
            chatId = msg.chatId,
            authorName = author.name,
            avatarThumb = author.avatarThumb,
            avatarFileId = author.avatarFileId,
            content = MessageContentMapper.map(msg.content),
            date = msg.date.toLong() * 1000L,
            reactions = MessageContentMapper.mapReactions(msg.interactionInfo?.reactions),
        )
    }

    private suspend fun authorInfoFor(msg: TdApi.Message, cache: MutableMap<Long, AuthorInfo>): AuthorInfo {
        return when (val sender = msg.senderId) {
            is TdApi.MessageSenderUser -> cache.getOrPut(sender.userId) { resolveUser(sender.userId) }
            is TdApi.MessageSenderChat -> cache.getOrPut(sender.chatId) { resolveChat(sender.chatId) }
            else -> AuthorInfo("—", null, null)
        }
    }

    private suspend fun resolveUser(userId: Long): AuthorInfo {
        val user = runCatching { td.send(TdApi.GetUser(userId)) }.getOrNull()
            ?: return AuthorInfo("Користувач", null, null)
        val name = listOfNotNull(
            user.firstName?.takeUnless { it.isBlank() },
            user.lastName?.takeUnless { it.isBlank() },
        ).joinToString(" ").ifBlank {
            user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Користувач"
        }
        return AuthorInfo(
            name = name,
            avatarThumb = user.profilePhoto?.minithumbnail?.data,
            avatarFileId = user.profilePhoto?.small?.id,
        )
    }

    private suspend fun resolveChat(chatId: Long): AuthorInfo {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
            ?: return AuthorInfo("Канал", null, null)
        return AuthorInfo(
            name = chat.title.orEmpty().ifBlank { "Канал" },
            avatarThumb = chat.photo?.minithumbnail?.data,
            avatarFileId = chat.photo?.small?.id,
        )
    }

    private data class AuthorInfo(
        val name: String,
        val avatarThumb: ByteArray?,
        val avatarFileId: Int?,
    )

    private companion object {
        const val TAG = "CommentsRepository"
        const val MAX_DEPTH = 3
        const val BATCH_SIZE = 50
    }
}
