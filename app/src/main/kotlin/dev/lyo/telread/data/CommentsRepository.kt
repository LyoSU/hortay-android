package dev.lyo.telread.data

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
 *
 * Mapping is delegated to a shared [MessageMapper] so author / forward / reply preview
 * resolution + caches are common with [PostsRepository]. Each comment is mapped to a
 * [TimelinePost] (the same model channel posts use) — channel posts and discussion
 * comments are technically the same Telegram message kind with a small set of contextual
 * extras, and one mapper / one model means we don't duplicate content rendering, sender
 * resolution or update plumbing for what is essentially the same thing.
 */
class CommentsRepository(
    private val td: TdClient,
    private val mapper: MessageMapper,
) {

    sealed interface ThreadState {
        data object Loading : ThreadState
        data class Ready(val rows: List<ThreadRow>, val threadChatId: Long) : ThreadState
        data class Error(val message: String) : ThreadState
    }

    // Posts whose discussion thread we've already fetched once. TDLib caches the result
    // server-side per session, so a repeat GetMessageThread is fast (~200ms vs ~2s cold).
    // We prefetch in the background for posts that linger in the viewport so a tap is
    // instant. The set is bounded — entries are never removed; thread metadata is small,
    // and within a single session the user is unlikely to view more than a few hundred
    // unique posts.
    private val prefetchedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    /**
     * Background warm-up: probe canGetMessageThread + GetMessageThread once per anchor.
     * Idempotent — repeat calls for the same (chatId, anchorId) are no-ops. Failures are
     * silently absorbed (cold thread that returns 400 still gets cached as "tried").
     */
    suspend fun prefetchThread(chatId: Long, candidateMessageIds: List<Long>) {
        val key = chatId to (candidateMessageIds.firstOrNull() ?: return)
        if (!prefetchedThreads.add(key)) return
        val anchorId = candidateMessageIds.firstOrNull { id ->
            runCatching { td.send(TdApi.GetMessageProperties(chatId, id)) }
                .getOrNull()
                ?.canGetMessageThread == true
        } ?: return
        runCatching { td.send(TdApi.GetMessageThread(chatId, anchorId)) }
    }

    /**
     * Cold flow: starts loading when collected, stops everything when collection ends.
     * Subscribes to [TdApi.UpdateNewMessage], [TdApi.UpdateMessageInteractionInfo],
     * [TdApi.UpdateDeleteMessages] and [TdApi.UpdateMessageContent] limited to the linked
     * discussion chat so the displayed thread stays in sync without a manual refresh.
     *
     * [candidateMessageIds] — every id worth probing. For a standalone post that's a
     * single-element list; for an album it's all sibling ids. Telegram pins the
     * discussion thread to a single album member (typically the one with the caption),
     * and [TdApi.GetMessageThread] on any other returns "Message has no thread". We
     * resolve the carrier through [TdApi.GetMessageProperties] (a local capability
     * lookup, no server round-trip) before issuing GetMessageThread.
     */
    fun observeThread(
        chatId: Long,
        candidateMessageIds: List<Long>,
        limit: Int = 200,
    ): Flow<ThreadState> = callbackFlow {
        trySend(ThreadState.Loading)

        // Telegram pins the discussion thread to a single member of an album (typically
        // the one with the caption); GetMessageThread on any other member returns 400.
        // GetMessageProperties is a local capability lookup with no server round-trip,
        // so we probe candidates in order and pick the carrier.
        val anchorId = candidateMessageIds.firstOrNull { id ->
            runCatching { td.send(TdApi.GetMessageProperties(chatId, id)) }
                .warnUnlessCancelled(TAG, "messageProperties($chatId,$id)")
                .getOrNull()
                ?.canGetMessageThread == true
        }
        if (anchorId == null) {
            trySend(ThreadState.Error("Обговорення недоступне."))
            close()
            return@callbackFlow
        }

        val info = runCatching { td.send(TdApi.GetMessageThread(chatId, anchorId)) }
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

    private suspend fun buildTree(messages: List<TdApi.Message>, rootMessageId: Long): List<ThreadRow> {
        if (messages.isEmpty()) return emptyList()

        // Map every message via the shared mapper FIRST — same caching layer as channel
        // posts, so users that already appeared in the feed don't trigger a fresh GetUser.
        val mapped: Map<Long, TimelinePost> = messages.associate { it.id to mapper.toThreadComment(it) }

        // Group by parent. The conversation root (the channel-post mirror) becomes the
        // virtual depth-0 parent; ids that no longer exist in the thread (deleted /
        // out-of-window) collapse under it too.
        val children: Map<Long, List<TimelinePost>> = messages.groupBy { msg ->
            val replyId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
            if (replyId != null && replyId != rootMessageId && replyId in mapped) replyId else 0L
        }.mapValues { entry -> entry.value.mapNotNull { mapped[it.id] } }

        val rows = mutableListOf<ThreadRow>()
        fun walk(parentId: Long, depth: Int) {
            val siblings = children[parentId].orEmpty().sortedBy { it.date }
            siblings.forEachIndexed { idx, msg ->
                rows += ThreadRow(
                    message = msg,
                    depth = depth.coerceAtMost(MAX_DEPTH),
                    isLastSibling = idx == siblings.lastIndex,
                )
                walk(msg.id, depth + 1)
            }
        }
        walk(0L, 0)

        return rows
    }

    private companion object {
        const val TAG = "CommentsRepository"
        const val MAX_DEPTH = 3
        const val BATCH_SIZE = 50
    }
}
