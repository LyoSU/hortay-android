package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Async layer over [MessageContentMapper]: resolves real channel handles, forward author
 * names, reply preview authors. Holds session-scoped caches so each user / supergroup is
 * fetched at most once per [PostsRepository] instance.
 *
 * Caches are [ConcurrentHashMap] because they're touched from multiple coroutines:
 *   • PostsRepository.refreshLocked (under refreshMutex)
 *   • PostsRepository.handleNewMessage / handle* update collectors (no mutex)
 *   • UpdateUser / UpdateSupergroup invalidations (yet another collector)
 * A plain HashMap would race on concurrent put + remove, which can corrupt buckets and
 * surface as ConcurrentModificationException or "lost" cached avatars.
 */
internal class MessageMapper(private val td: TdClient) {

    private val userCache = ConcurrentHashMap<Long, ResolvedSender>()
    private val chatCache = ConcurrentHashMap<Long, ResolvedSender>()
    private val supergroupUsernameCache = ConcurrentHashMap<Long, String>() // username (sentinel "" = no username)

    suspend fun toTimelinePost(message: TdApi.Message, chat: TdApi.Chat): TimelinePost = TimelinePost(
        id = message.id,
        chatId = message.chatId,
        mediaAlbumId = message.mediaAlbumId,
        channelTitle = chat.title.orEmpty(),
        channelHandle = resolveChannelHandle(chat),
        avatarThumb = chat.photo?.minithumbnail?.data,
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
        // Filled in by PostFilterStrategy.mergeAlbumMembers — per-message mapping has
        // no idea which siblings exist yet.
        albumMessageIds = emptyList(),
    )

    suspend fun resolveSender(senderId: TdApi.MessageSender): ResolvedSender = when (senderId) {
        is TdApi.MessageSenderUser -> resolveCachedUser(senderId.userId)
        is TdApi.MessageSenderChat -> resolveCachedChat(senderId.chatId)
        else -> ResolvedSender("—", null, null)
    }

    /** Drop a stale entry — call when TDLib emits UpdateUser / UpdateSupergroup. */
    fun invalidateUser(userId: Long) { userCache.remove(userId) }
    fun invalidateChat(chatId: Long) { chatCache.remove(chatId) }
    fun invalidateSupergroup(supergroupId: Long) { supergroupUsernameCache.remove(supergroupId) }

    // ConcurrentHashMap.getOrPut from Kotlin stdlib is NOT atomic. computeIfAbsent IS, but
    // its lambda can't be suspend. Pattern: optimistic read → fetch outside the map →
    // putIfAbsent and return whichever value won the race. Slightly redundant fetch on
    // first-touch contention, but no race + suspend-friendly.
    private suspend fun resolveCachedUser(userId: Long): ResolvedSender {
        userCache[userId]?.let { return it }
        val resolved = fetchUser(userId)
        return userCache.putIfAbsent(userId, resolved) ?: resolved
    }

    private suspend fun resolveCachedChat(chatId: Long): ResolvedSender {
        chatCache[chatId]?.let { return it }
        val resolved = fetchChat(chatId)
        return chatCache.putIfAbsent(chatId, resolved) ?: resolved
    }

    private suspend fun resolveChannelHandle(chat: TdApi.Chat): String? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        supergroupUsernameCache[supergroupId]?.let { return it.takeUnless { s -> s.isEmpty() } }
        val handle = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
            ?.usernames?.activeUsernames?.firstOrNull()
            ?.let { "@$it" }
        // ConcurrentHashMap forbids null values, so use "" as the "no username" sentinel.
        supergroupUsernameCache.putIfAbsent(supergroupId, handle.orEmpty())
        return handle
    }

    private suspend fun fetchUser(userId: Long): ResolvedSender {
        val u = runCatching { td.send(TdApi.GetUser(userId)) }.getOrNull()
            ?: return ResolvedSender("Користувач", null, null)
        val name = listOfNotNull(
            u.firstName?.takeUnless { it.isBlank() },
            u.lastName?.takeUnless { it.isBlank() },
        ).joinToString(" ").ifBlank {
            u.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Користувач"
        }
        return ResolvedSender(
            name = name,
            avatarThumb = u.profilePhoto?.minithumbnail?.data,
            avatarFileId = u.profilePhoto?.small?.id,
        )
    }

    private suspend fun fetchChat(chatId: Long): ResolvedSender {
        val c = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
            ?: return ResolvedSender("Канал", null, null)
        return ResolvedSender(
            name = c.title.orEmpty().ifBlank { "Канал" },
            avatarThumb = c.photo?.minithumbnail?.data,
            avatarFileId = c.photo?.small?.id,
        )
    }

    private suspend fun mapForwardOrigin(origin: TdApi.MessageOrigin): ForwardOrigin = when (origin) {
        is TdApi.MessageOriginUser -> ForwardOrigin.User(
            userName = resolveCachedUser(origin.senderUserId).name,
        )
        is TdApi.MessageOriginChat -> ForwardOrigin.Chat(
            chatName = resolveCachedChat(origin.senderChatId).name,
            authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
        )
        is TdApi.MessageOriginHiddenUser -> ForwardOrigin.HiddenUser(origin.senderName.orEmpty())
        is TdApi.MessageOriginChannel -> ForwardOrigin.Channel(
            channelName = resolveCachedChat(origin.chatId).name,
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
        // Cross-chat reply (Quote / reply-to-channel-post): fall back to the SOURCE chat's
        // name, not the post's own chat — otherwise a quoted post from channel A appearing
        // in channel B gets misattributed to channel B.
        val author = runCatching {
            val refMsg = td.send(TdApi.GetMessage(reply.chatId, reply.messageId))
            resolveSender(refMsg.senderId).name
        }.getOrNull() ?: resolveCachedChat(reply.chatId).name
        return ReplyPreview(authorName = author, excerpt = excerpt, isQuote = reply.quote != null)
    }

    data class ResolvedSender(
        val name: String,
        val avatarThumb: ByteArray?,
        val avatarFileId: Int?,
    )
}
