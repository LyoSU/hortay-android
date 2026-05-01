package dev.lyo.hortay.data

import dev.lyo.hortay.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import java.util.Collections

/**
 * Async layer over [MessageContentMapper] that resolves real channel handles, forward
 * author names, reply preview authors, and discussion-comment sender info. Holds
 * session-scoped caches so each user / supergroup is fetched at most once per repository
 * instance.
 *
 * Single mapper per session: PostsRepository (channel feed) and CommentsRepository
 * (discussion threads) share an instance — that means the same author resolved once for
 * a feed post is reused when their reply shows up in comments, and vice versa.
 *
 * Bounded LRU caches: a long session can otherwise accumulate thousands of resolved
 * users/chats and never evict — particularly when the user browses many channels with
 * lots of unique commenters. LinkedHashMap with accessOrder=true + removeEldestEntry
 * gives true LRU semantics; wrapped in `Collections.synchronizedMap` because the maps
 * are touched from several coroutines (refreshLocked, handle* update collectors,
 * Update* invalidations).
 */
class MessageMapper(private val td: TdSender, private val res: StringResolver) {

    private val defaultUserName: String get() = res.getString(R.string.user_default_name)
    private val defaultChannelName: String get() = res.getString(R.string.channel_default_name)


    private val userCache = boundedLru<Long, ResolvedSender>(MAX_RESOLVER_CACHE)
    private val chatCache = boundedLru<Long, ResolvedSender>(MAX_RESOLVER_CACHE)
    private val supergroupUsernameCache = boundedLru<Long, String>(MAX_RESOLVER_CACHE) // username, "" = none
    // Verification mark per supergroup. We cache the *string* of the enum (or "") so the
    // map can serve "definitely no badge" without materialising a Kotlin null.
    private val supergroupVerificationCache = boundedLru<Long, String>(MAX_RESOLVER_CACHE)

    // Reply previews sometimes hit the same source post repeatedly — same channel
    // referenced by multiple posts, or the same conversation thread visible across a
    // refresh. Caching the resolved author name skips a td.send(GetMessage) per probe.
    private val replyAuthorCache = boundedLru<Pair<Long, Long>, String>(MAX_RESOLVER_CACHE)

    /**
     * Map a channel feed post: sender info comes from the channel chat itself (its title
     * and supergroup `@handle`). Used by [PostsRepository] for the top-level feed.
     */
    suspend fun toChannelPost(message: TdApi.Message, chat: TdApi.Chat): TimelinePost = TimelinePost(
        id = message.id,
        chatId = message.chatId,
        mediaAlbumId = message.mediaAlbumId,
        senderName = chat.title.orEmpty(),
        senderHandle = resolveChannelHandle(chat),
        avatarThumb = chat.photo?.minithumbnail?.data,
        avatarFileId = chat.photo?.small?.id,
        content = MessageContentMapper.map(message.content, res),
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
        parentId = null,
        isPinned = message.isPinned,
        verification = resolveChannelVerification(chat),
    )

    /**
     * Map a discussion-thread comment: sender info comes from [TdApi.Message.senderId]
     * (a user, or a chat posting on behalf of a channel). Channel-specific fields
     * (views, commentCount, authorSignature) are zero/null because they don't apply to
     * conversation messages.
     *
     * Reply previews still flow through [mapReply] — comment replies that quote another
     * comment in the same thread render with author + excerpt, identical to feed posts.
     */
    suspend fun toThreadComment(message: TdApi.Message): TimelinePost {
        val sender = resolveSender(message.senderId)
        val parent = (message.replyTo as? TdApi.MessageReplyToMessage)?.messageId
        return TimelinePost(
            id = message.id,
            chatId = message.chatId,
            mediaAlbumId = message.mediaAlbumId,
            senderName = sender.name,
            senderHandle = sender.handle,
            avatarThumb = sender.avatarThumb,
            avatarFileId = sender.avatarFileId,
            content = MessageContentMapper.map(message.content, res),
            views = 0,
            date = message.date.toLong() * 1000L,
            editDate = message.editDate.toLong() * 1000L,
            forwardOrigin = message.forwardInfo?.origin?.let { mapForwardOrigin(it) },
            authorSignature = null,
            reply = mapReply(message.replyTo, message.chatId),
            reactions = MessageContentMapper.mapReactions(message.interactionInfo?.reactions),
            commentCount = null,
            albumMessageIds = emptyList(),
            parentId = parent,
        )
    }

    suspend fun resolveSender(senderId: TdApi.MessageSender): ResolvedSender = when (senderId) {
        is TdApi.MessageSenderUser -> resolveCachedUser(senderId.userId)
        is TdApi.MessageSenderChat -> resolveCachedChat(senderId.chatId)
        else -> ResolvedSender("—", null, null, null)
    }

    /**
     * Pre-warm the supergroup handle / verification caches for a batch of channel
     * chats. During [PostsRepository.refreshLocked] every per-channel mapping path
     * touches [resolveChannelHandle], which fires a [TdApi.GetSupergroup] on cache
     * miss; serialised through the per-channel concurrency semaphore that's
     * already saturated by [TdApi.GetChatHistory] requests, those handle lookups
     * stack at the tail of the refresh and add hundreds of milliseconds to "first
     * post is fully resolved".
     *
     * Firing them in parallel here, with their own concurrency budget, means the
     * caches are warm by the time the per-channel mappers run — they hit the
     * `userCache` / `supergroupUsernameCache` fast path and never round-trip.
     *
     * Idempotent: skips supergroups already cached, so repeated calls (e.g.
     * pull-to-refresh) cost a few HashMap probes and nothing else.
     */
    suspend fun prewarmChannels(chats: List<TdApi.Chat>) {
        val needsFetch = chats.mapNotNull { chat ->
            val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return@mapNotNull null
            if (supergroupUsernameCache[sgId] != null) null else sgId
        }
        if (needsFetch.isEmpty()) return
        val semaphore = Semaphore(PREWARM_CONCURRENCY)
        coroutineScope {
            needsFetch.map { sgId ->
                async {
                    semaphore.withPermit {
                        val sg = runCatching { td.send(TdApi.GetSupergroup(sgId)) }.getOrNull() ?: return@withPermit
                        val handle = sg.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }
                        supergroupUsernameCache.putIfAbsent(sgId, handle.orEmpty())
                        supergroupVerificationCache.putIfAbsent(
                            sgId,
                            sg.verificationStatus?.toMark()?.name.orEmpty(),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /** Drop a stale entry — call when TDLib emits UpdateUser / UpdateSupergroup. */
    fun invalidateUser(userId: Long) { userCache.remove(userId) }
    fun invalidateChat(chatId: Long) { chatCache.remove(chatId) }
    fun invalidateSupergroup(supergroupId: Long) {
        supergroupUsernameCache.remove(supergroupId)
        supergroupVerificationCache.remove(supergroupId)
    }

    // computeIfAbsent on a synchronizedMap is atomic but blocks the bucket, and our
    // fetch lambdas are suspend. Pattern: optimistic read → fetch outside the map →
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
        // Single GetSupergroup call seeds BOTH the username and verification caches —
        // avoids a second round-trip when the post is then asked for its badge.
        val sg = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
        val handle = sg?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }
        // "" = "no username" sentinel so the cache hit path can distinguish unfetched
        // from definitely-no-username without nullability gymnastics.
        supergroupUsernameCache.putIfAbsent(supergroupId, handle.orEmpty())
        supergroupVerificationCache.putIfAbsent(supergroupId, sg?.verificationStatus?.toMark()?.name.orEmpty())
        return handle
    }

    private suspend fun resolveChannelVerification(chat: TdApi.Chat): SenderVerification? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        // Hit the username path first — it primes the verification cache as a side effect.
        // Most posts already trigger handle resolution before we land here, so this is
        // usually a free read.
        if (supergroupId !in supergroupVerificationCache) resolveChannelHandle(chat)
        return supergroupVerificationCache[supergroupId]
            ?.takeUnless { it.isEmpty() }
            ?.let { runCatching { SenderVerification.valueOf(it) }.getOrNull() }
    }

    private suspend fun fetchUser(userId: Long): ResolvedSender {
        val u = runCatching { td.send(TdApi.GetUser(userId)) }.getOrNull()
            ?: return ResolvedSender(defaultUserName, null, null, null)
        val username = u.usernames?.activeUsernames?.firstOrNull()
        val name = listOfNotNull(
            u.firstName?.takeUnless { it.isBlank() },
            u.lastName?.takeUnless { it.isBlank() },
        ).joinToString(" ").ifBlank {
            username?.let { "@$it" } ?: defaultUserName
        }
        return ResolvedSender(
            name = name,
            handle = username?.let { "@$it" },
            avatarThumb = u.profilePhoto?.minithumbnail?.data,
            avatarFileId = u.profilePhoto?.small?.id,
        )
    }

    private suspend fun fetchChat(chatId: Long): ResolvedSender {
        val c = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
            ?: return ResolvedSender(defaultChannelName, null, null, null)
        return ResolvedSender(
            name = c.title.orEmpty().ifBlank { defaultChannelName },
            handle = resolveChannelHandle(c),
            avatarThumb = c.photo?.minithumbnail?.data,
            avatarFileId = c.photo?.small?.id,
        )
    }

    private suspend fun mapForwardOrigin(origin: TdApi.MessageOrigin): ForwardOrigin = when (origin) {
        is TdApi.MessageOriginUser -> ForwardOrigin.User(
            userName = resolveCachedUser(origin.senderUserId).name,
        )
        is TdApi.MessageOriginChat -> {
            val resolved = resolveCachedChat(origin.senderChatId)
            ForwardOrigin.Chat(
                chatName = resolved.name,
                authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
                sourceChatId = origin.senderChatId,
                sourceHandle = resolved.handle,
            )
        }
        is TdApi.MessageOriginHiddenUser -> ForwardOrigin.HiddenUser(origin.senderName.orEmpty())
        is TdApi.MessageOriginChannel -> {
            val resolved = resolveCachedChat(origin.chatId)
            ForwardOrigin.Channel(
                channelName = resolved.name,
                authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
                sourceChatId = origin.chatId,
                sourceHandle = resolved.handle,
            )
        }
        else -> ForwardOrigin.HiddenUser("")
    }

    private suspend fun mapReply(replyTo: TdApi.MessageReplyTo?, chatId: Long): ReplyPreview? {
        val reply = replyTo as? TdApi.MessageReplyToMessage ?: return null
        val excerpt = reply.quote?.text?.text.orEmpty().ifBlank {
            (reply.content as? TdApi.MessageText)?.text?.text.orEmpty()
        }
        if (excerpt.isBlank()) return null

        // Cache reply-author resolution per source message — many timeline posts reply
        // to the same channel post (especially in news feeds), and a stale GetMessage
        // round-trip per probe is expensive enough to feel on a 200-post refresh.
        val key = reply.chatId to reply.messageId
        val author = replyAuthorCache[key] ?: run {
            // Cross-chat reply (Quote / reply-to-channel-post): fall back to the SOURCE
            // chat's name, not the post's own chat — otherwise a quoted post from channel
            // A appearing in channel B gets misattributed to channel B.
            val resolved = runCatching {
                val refMsg = td.send(TdApi.GetMessage(reply.chatId, reply.messageId))
                resolveSender(refMsg.senderId).name
            }.getOrNull() ?: resolveCachedChat(reply.chatId).name
            replyAuthorCache.putIfAbsent(key, resolved) ?: resolved
        }
        return ReplyPreview(authorName = author, excerpt = excerpt, isQuote = reply.quote != null)
    }

    data class ResolvedSender(
        val name: String,
        val handle: String?,
        val avatarThumb: ByteArray?,
        val avatarFileId: Int?,
    )

    private companion object {
        const val MAX_RESOLVER_CACHE = 512
        // Bigger than refresh's per-channel concurrency (4) because GetSupergroup is
        // strictly local-cache-or-API-cache fetches, much cheaper than GetChatHistory.
        // 16 saturates the local DB without spawning more than TDLib comfortably handles.
        const val PREWARM_CONCURRENCY = 16
    }
}

private fun TdApi.VerificationStatus.toMark(): SenderVerification? = when {
    isVerified -> SenderVerification.Verified
    isScam -> SenderVerification.Scam
    isFake -> SenderVerification.Fake
    else -> null
}

private fun <K, V> boundedLru(maxSize: Int): MutableMap<K, V> =
    Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean = size > maxSize
    })
