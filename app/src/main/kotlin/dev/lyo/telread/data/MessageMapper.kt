package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi

/**
 * Async layer over [MessageContentMapper]: resolves real channel handles, forward author
 * names, reply preview authors. Holds session-scoped caches so each user / supergroup is
 * fetched at most once per [PostsRepository] instance.
 */
internal class MessageMapper(private val td: TdClient) {

    private val userCache = mutableMapOf<Long, ResolvedSender>()
    private val chatCache = mutableMapOf<Long, ResolvedSender>()
    private val supergroupCache = mutableMapOf<Long, String?>() // username

    suspend fun toTimelinePost(message: TdApi.Message, chat: TdApi.Chat): TimelinePost = TimelinePost(
        id = message.id,
        chatId = message.chatId,
        mediaAlbumId = message.mediaAlbumId,
        channelTitle = chat.title.orEmpty(),
        channelHandle = resolveChannelHandle(chat),
        avatarFileId = chat.photo?.small?.id,
        content = MessageContentMapper.map(message.content),
        views = message.interactionInfo?.viewCount ?: 0,
        date = message.date.toLong() * 1000L,
        editDate = message.editDate.toLong() * 1000L,
        forwardOrigin = message.forwardInfo?.origin?.let { mapForwardOrigin(it) },
        authorSignature = message.authorSignature.takeUnless { it.isNullOrBlank() },
        reply = mapReply(message.replyTo, message.chatId),
        reactions = MessageContentMapper.mapReactions(message.interactionInfo?.reactions),
        commentCount = message.interactionInfo?.replyInfo?.replyCount,
    )

    suspend fun resolveSender(senderId: TdApi.MessageSender): ResolvedSender = when (senderId) {
        is TdApi.MessageSenderUser -> userCache.getOrPut(senderId.userId) { fetchUser(senderId.userId) }
        is TdApi.MessageSenderChat -> chatCache.getOrPut(senderId.chatId) { fetchChat(senderId.chatId) }
        else -> ResolvedSender("—", null)
    }

    private suspend fun resolveChannelHandle(chat: TdApi.Chat): String? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        return supergroupCache.getOrPut(supergroupId) {
            runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
                ?.usernames?.activeUsernames?.firstOrNull()
                ?.let { "@$it" }
        }
    }

    private suspend fun fetchUser(userId: Long): ResolvedSender {
        val u = runCatching { td.send(TdApi.GetUser(userId)) }.getOrNull()
            ?: return ResolvedSender("Користувач", null)
        val name = listOfNotNull(
            u.firstName?.takeUnless { it.isBlank() },
            u.lastName?.takeUnless { it.isBlank() },
        ).joinToString(" ").ifBlank {
            u.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Користувач"
        }
        return ResolvedSender(name, u.profilePhoto?.small?.id)
    }

    private suspend fun fetchChat(chatId: Long): ResolvedSender {
        val c = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
            ?: return ResolvedSender("Канал", null)
        return ResolvedSender(c.title.orEmpty().ifBlank { "Канал" }, c.photo?.small?.id)
    }

    private suspend fun mapForwardOrigin(origin: TdApi.MessageOrigin): ForwardOrigin = when (origin) {
        is TdApi.MessageOriginUser -> ForwardOrigin.User(
            userName = userCache.getOrPut(origin.senderUserId) { fetchUser(origin.senderUserId) }.name,
        )
        is TdApi.MessageOriginChat -> ForwardOrigin.Chat(
            chatName = chatCache.getOrPut(origin.senderChatId) { fetchChat(origin.senderChatId) }.name,
            authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
        )
        is TdApi.MessageOriginHiddenUser -> ForwardOrigin.HiddenUser(origin.senderName.orEmpty())
        is TdApi.MessageOriginChannel -> ForwardOrigin.Channel(
            channelName = chatCache.getOrPut(origin.chatId) { fetchChat(origin.chatId) }.name,
            authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
        )
        else -> ForwardOrigin.HiddenUser("")
    }

    private suspend fun mapReply(replyTo: TdApi.MessageReplyTo?, chatId: Long): ReplyPreview? {
        val reply = replyTo as? TdApi.MessageReplyToMessage ?: return null
        val excerpt = reply.quote?.text?.text.orEmpty().ifBlank {
            (reply.content as? TdApi.MessageText)?.text?.text.orEmpty()
        }
        if (excerpt.isBlank()) return null
        val author = runCatching {
            val refMsg = td.send(TdApi.GetMessage(reply.chatId, reply.messageId))
            resolveSender(refMsg.senderId).name
        }.getOrNull() ?: chatCache.getOrPut(chatId) { fetchChat(chatId) }.name
        return ReplyPreview(authorName = author, excerpt = excerpt, isQuote = reply.quote != null)
    }

    data class ResolvedSender(val name: String, val avatarFileId: Int?)
}
