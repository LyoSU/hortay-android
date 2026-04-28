package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi

/**
 * Fetches a channel post's discussion thread (the linked group) and returns a flat,
 * pre-ordered list of [CommentRow]s ready for a LazyColumn:
 *
 *   ─ Top-level reply A
 *     ─ Reply to A
 *       ─ Reply to that reply
 *     ─ Another reply to A
 *   ─ Top-level reply B
 *
 * Depth is clamped so very deep chains stay legible on phones.
 */
class CommentsRepository(private val td: TdClient) {

    suspend fun fetchThread(chatId: Long, messageId: Long, limit: Int = 200): Result<List<CommentRow>> = runCatching {
        val info = td.send(TdApi.GetMessageThread(chatId, messageId))
        val threadChatId = info.chatId
        val rootId = info.messageThreadId

        val raw = mutableListOf<TdApi.Message>()
        var fromId = 0L
        while (raw.size < limit) {
            val batch = td.send(TdApi.GetMessageThreadHistory(threadChatId, rootId, fromId, 0, BATCH_SIZE))
            val msgs = batch.messages.orEmpty()
            if (msgs.isEmpty()) break
            raw += msgs
            fromId = msgs.last().id
        }

        // Drop the root mirror of the channel post itself (it is already pinned at the top of
        // the screen by [CommentsScreen]); only conversation messages remain.
        val replies = raw.filter { it.id != rootId }

        buildTree(replies, rootMessageId = rootId)
    }

    private suspend fun buildTree(messages: List<TdApi.Message>, rootMessageId: Long): List<CommentRow> {
        if (messages.isEmpty()) return emptyList()

        val byId: Map<Long, TdApi.Message> = messages.associateBy { it.id }
        val children: Map<Long, List<TdApi.Message>> = messages.groupBy { msg ->
            val replyId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
            // Anything that replies to the root mirror — or to a message we did not load — is a
            // top-level comment, sorted under the synthetic key 0L.
            if (replyId != null && replyId != rootMessageId && replyId in byId) replyId else 0L
        }

        val authorCache = mutableMapOf<Long, String>()
        val rows = mutableListOf<CommentRow>()

        suspend fun walk(parentId: Long, depth: Int) {
            val siblings = children[parentId].orEmpty().sortedBy { it.date }
            siblings.forEachIndexed { idx, msg ->
                val comment = toComment(msg, authorCache)
                rows += CommentRow(
                    comment = comment,
                    depth = depth.coerceAtMost(MAX_DEPTH),
                    isLastSibling = idx == siblings.lastIndex,
                )
                walk(msg.id, depth + 1)
            }
        }
        walk(0L, 0)

        return rows
    }

    private suspend fun toComment(msg: TdApi.Message, authorCache: MutableMap<Long, String>): Comment {
        val text = textOf(msg.content)
        val parentId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
        return Comment(
            id = msg.id,
            parentId = parentId,
            chatId = msg.chatId,
            authorName = authorNameFor(msg, authorCache),
            avatarFileId = null,
            text = text,
            date = msg.date.toLong() * 1000L,
            reactions = reactionsFrom(msg.interactionInfo?.reactions),
        )
    }

    private suspend fun authorNameFor(msg: TdApi.Message, cache: MutableMap<Long, String>): String {
        return when (val sender = msg.senderId) {
            is TdApi.MessageSenderUser -> cache.getOrPut(sender.userId) {
                runCatching { td.send(TdApi.GetUser(sender.userId)) }.getOrNull()?.let { user ->
                    listOfNotNull(
                        user.firstName?.takeUnless { it.isBlank() },
                        user.lastName?.takeUnless { it.isBlank() },
                    ).joinToString(" ").ifBlank {
                        user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Користувач"
                    }
                } ?: "Користувач"
            }
            is TdApi.MessageSenderChat -> cache.getOrPut(sender.chatId) {
                runCatching { td.send(TdApi.GetChat(sender.chatId)) }.getOrNull()?.title ?: "Канал"
            }
            else -> "—"
        }
    }

    private fun textOf(content: TdApi.MessageContent): FormattedText {
        val text = when (content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageDocument -> content.caption
            is TdApi.MessageSticker -> null
            else -> null
        }
        val plain = text?.text.orEmpty()
        return if (plain.isBlank()) FormattedText.Empty else FormattedText.plain(plain)
    }

    private fun reactionsFrom(reactions: TdApi.MessageReactions?): Reactions {
        val list = reactions?.reactions.orEmpty()
        if (list.isEmpty()) return Reactions(0, emptyList())
        val total = list.sumOf { it.totalCount }
        val emojis = list.take(3).mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
        return Reactions(total, emojis)
    }

    private companion object {
        const val MAX_DEPTH = 3
        const val BATCH_SIZE = 50
    }
}
