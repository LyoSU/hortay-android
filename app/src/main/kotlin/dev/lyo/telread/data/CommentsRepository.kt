package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi

/**
 * Fetches a channel post's discussion thread (the linked group). The thread originates as
 * a single root message (the channel post auto-mirrored into the discussion group); every
 * subsequent reply chains off it.
 */
class CommentsRepository(private val td: TdClient) {

    suspend fun fetchThread(chatId: Long, messageId: Long, limit: Int = 100): Result<List<Comment>> = runCatching {
        val info = td.send(TdApi.GetMessageThread(chatId, messageId))
        val threadChatId = info.chatId
        val rootId = info.messageThreadId

        val raw = mutableListOf<TdApi.Message>()
        var fromId = 0L
        while (raw.size < limit) {
            val batch = td.send(
                TdApi.GetMessageThreadHistory(threadChatId, rootId, fromId, 0, 50),
            )
            val msgs = batch.messages.orEmpty()
            if (msgs.isEmpty()) break
            raw += msgs
            fromId = msgs.last().id
        }

        // TDLib returns newest-first; comments read better oldest-first.
        val sorted = raw.sortedBy { it.date }

        // Index by id so we can resolve replyToMessageId → excerpt.
        val byId = sorted.associateBy { it.id }
        val authorCache = mutableMapOf<Long, String>()

        sorted.mapNotNull { msg ->
            val text = textOf(msg.content) ?: return@mapNotNull null
            val authorName = authorNameFor(msg, authorCache)
            val replyId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
            val replyTarget = replyId?.let(byId::get)
            Comment(
                id = msg.id,
                chatId = msg.chatId,
                authorName = authorName,
                avatarFileId = null,
                text = text,
                date = msg.date.toLong() * 1000L,
                replyingToAuthor = replyTarget?.let { authorNameFor(it, authorCache) },
                replyingToExcerpt = replyTarget?.let { textOf(it.content)?.text?.take(80) },
                reactions = MessageMapperReactions.from(msg.interactionInfo?.reactions),
            )
        }
    }

    private suspend fun authorNameFor(msg: TdApi.Message, cache: MutableMap<Long, String>): String {
        val sender = msg.senderId
        return when (sender) {
            is TdApi.MessageSenderUser -> {
                cache.getOrPut(sender.userId) {
                    runCatching { td.send(TdApi.GetUser(sender.userId)) }
                        .getOrNull()
                        ?.let { user ->
                            listOfNotNull(
                                user.firstName?.takeUnless { it.isBlank() },
                                user.lastName?.takeUnless { it.isBlank() },
                            ).joinToString(" ").ifBlank { "@${user.usernames?.activeUsernames?.firstOrNull().orEmpty()}" }
                        }
                        ?: "Користувач"
                }
            }
            is TdApi.MessageSenderChat -> {
                cache.getOrPut(sender.chatId) {
                    runCatching { td.send(TdApi.GetChat(sender.chatId)) }
                        .getOrNull()
                        ?.title
                        ?: "Канал"
                }
            }
            else -> "—"
        }
    }

    private fun textOf(content: TdApi.MessageContent): FormattedText? = when (content) {
        is TdApi.MessageText -> mapFormatted(content.text)
        is TdApi.MessagePhoto -> mapFormatted(content.caption)
        is TdApi.MessageVideo -> mapFormatted(content.caption)
        is TdApi.MessageAnimation -> mapFormatted(content.caption)
        is TdApi.MessageDocument -> mapFormatted(content.caption)
        else -> null
    }

    private fun mapFormatted(t: TdApi.FormattedText?): FormattedText {
        val plain = t?.text.orEmpty()
        if (plain.isEmpty()) return FormattedText.Empty
        return FormattedText.plain(plain)
    }
}

/** Tiny façade over [MessageMapper.mapReactions] kept private. */
private object MessageMapperReactions {
    fun from(reactions: TdApi.MessageReactions?): Reactions {
        val list = reactions?.reactions.orEmpty()
        if (list.isEmpty()) return Reactions(0, emptyList())
        val total = list.sumOf { it.totalCount }
        val emojis = list.take(3).mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
        return Reactions(total, emojis)
    }
}
