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
    // refresh. We cache the full resolved context (author, thumbnail, kind, plus a
    // fallback caption/text) so a second mapping never round-trips. The single GetMessage
    // we'd already pay for the author also fills in thumbnail and kind, which TDLib's
    // [MessageReplyToMessage.content] snapshot leaves null in many channel-post pathways.
    private val replyContextCache = boundedLru<Pair<Long, Long>, ReplyContext>(MAX_RESOLVER_CACHE)

    private data class ReplyContext(
        val authorName: String,
        val mediaThumb: TdMedia?,
        val mediaKind: ReplyMediaKind,
        val fallbackExcerpt: String,
    )

    /**
     * Map a channel feed post.
     *
     * **Sender resolution**: TDLib distinguishes three cases via [TdApi.Message.senderId]:
     *   1. `MessageSenderChat(chatId == chat.id)` — the channel itself posts. Standard path:
     *      sender = channel name + handle + photo, `channelContext = null`.
     *   2. `MessageSenderUser(userId)` — TDLib's "personal-author" channel mode: an admin
     *      explicitly posted under their own identity. Sender = user's display name +
     *      `@username` + avatar. The channel attribution is preserved in [channelContext]
     *      so the reader still sees "in &lt;ChannelName&gt;".
     *   3. `MessageSenderChat(chatId != chat.id)` — rare: an admin posting on behalf of a
     *      different chat (e.g., posting as one of the admin's other channels). Treated the
     *      same as case 2 so the foreign chat's identity is shown, with the host channel
     *      preserved in [channelContext].
     *
     * This is NOT the same as `authorSignature` — that field is a free-text caption set on
     * channel-as-sender posts ("Олег Петренко" appears as "ChannelName · Олег Петренко" in
     * the header). Personal-author mode replaces the WHOLE identity row, including avatar.
     */
    suspend fun toChannelPost(message: TdApi.Message, chat: TdApi.Chat): TimelinePost {
        val sender = message.senderId
        val isChannelAsSender = sender is TdApi.MessageSenderChat && sender.chatId == chat.id
        val channelHandle = resolveChannelHandle(chat)
        val channelThumb = chat.photo?.minithumbnail?.data
        val channelFileId = chat.photo?.small?.id

        val displayName: String
        val displayHandle: String?
        val displayThumb: ByteArray?
        val displayFileId: Int?
        val channelContext: ChannelContext?

        if (isChannelAsSender) {
            displayName = chat.title.orEmpty()
            displayHandle = channelHandle
            displayThumb = channelThumb
            displayFileId = channelFileId
            channelContext = null
        } else {
            val resolved = resolveSender(sender)
            displayName = resolved.name
            displayHandle = resolved.handle
            displayThumb = resolved.avatarThumb
            displayFileId = resolved.avatarFileId
            channelContext = ChannelContext(
                name = chat.title.orEmpty(),
                handle = channelHandle,
                avatarThumb = channelThumb,
                avatarFileId = channelFileId,
            )
        }

        return TimelinePost(
            id = message.id,
            chatId = message.chatId,
            mediaAlbumId = message.mediaAlbumId,
            senderName = displayName,
            senderHandle = displayHandle,
            avatarThumb = displayThumb,
            avatarFileId = displayFileId,
            content = MessageContentMapper.map(message.content, res),
            views = message.interactionInfo?.viewCount ?: 0,
            date = message.date.toLong() * 1000L,
            editDate = message.editDate.toLong() * 1000L,
            forwardOrigin = message.forwardInfo?.origin?.let { mapForwardOrigin(it) },
            // authorSignature is the custom-title channel admins set on channel-as-sender
            // posts ("ChannelName · CustomTitle"). It's noise for personal-author mode —
            // the actual admin's name is already in senderName, and TDLib sometimes echoes
            // the same name into authorSignature, producing "Author · Author" duplicates.
            authorSignature = if (isChannelAsSender) {
                message.authorSignature.takeUnless { it.isNullOrBlank() }
            } else null,
            reply = mapReply(message.replyTo, message.chatId),
            reactions = MessageContentMapper.mapReactions(message.interactionInfo?.reactions),
            commentCount = message.interactionInfo?.replyInfo?.replyCount,
            // Filled in by PostFilterStrategy.mergeAlbumMembers — per-message mapping has
            // no idea which siblings exist yet.
            albumMessageIds = emptyList(),
            parentId = null,
            isPinned = message.isPinned,
            verification = resolveChannelVerification(chat),
            channelContext = channelContext,
        )
    }

    /**
     * Map a discussion-thread comment: sender info comes from [TdApi.Message.senderId]
     * (a user, or a chat posting on behalf of a channel). Channel-specific fields
     * (views, commentCount, authorSignature) are zero/null because they don't apply to
     * conversation messages. [TimelinePost.channelContext] is also intentionally left
     * null — comments already live inside a thread surface, so the "in &lt;Channel&gt;"
     * subtitle would be redundant noise stacked on top of the thread header.
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

    /**
     * Wipe every resolver cache. Called from [AppGraph]'s logout handler so
     * author identities resolved for account A's view don't surface in
     * account B's feed after a re-sign-in. Cheap — caches re-warm on the
     * first refresh against the new account.
     */
    fun clear() {
        userCache.clear()
        chatCache.clear()
        supergroupUsernameCache.clear()
        supergroupVerificationCache.clear()
        replyContextCache.clear()
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
        val key = reply.chatId to reply.messageId

        // Resolve author + thumbnail + kind + caption-fallback once and cache the bundle.
        //
        // Round-trip policy:
        //   • TDLib gave us a non-null content snapshot → we have everything we need
        //     (kind, thumb, caption text); skip the fetch and use the chat-name cache for
        //     author. Fast path; covers most public channel posts.
        //   • Snapshot is null → fall back to GetMessage to get content + author. This is
        //     the path where TDLib doesn't preload reply content (most channel-side
        //     reply-to-channel-post pathways), and is the only case that costs a server
        //     round-trip.
        // Either way the result is cached per (chatId, messageId), so a busy feed with
        // many posts referencing the same parent pays the cost at most once.
        val ctx = replyContextCache[key] ?: run {
            val needsFetch = reply.content == null
            val refMsg = if (needsFetch) {
                runCatching { td.send(TdApi.GetMessage(reply.chatId, reply.messageId)) }
                    .getOrNull()
            } else null
            val author = refMsg?.let { resolveSender(it.senderId).name }
                ?: resolveCachedChat(reply.chatId).name
            val effectiveContent: TdApi.MessageContent? = refMsg?.content ?: reply.content
            val (thumb, kind) = extractReplyMedia(effectiveContent)
            val fallback = extractTextOrCaption(effectiveContent)
            val resolved = ReplyContext(author, thumb, kind, fallback)
            replyContextCache.putIfAbsent(key, resolved) ?: resolved
        }

        // Excerpt priority:
        //   1. Explicit user-selected quote (Telegram's quote-text feature).
        //   2. The parent's own text or caption (resolved via GetMessage above).
        //   3. Empty — the kind label takes over in the UI.
        val excerpt = reply.quote?.text?.text.orEmpty().ifBlank { ctx.fallbackExcerpt }

        return ReplyPreview(
            authorName = ctx.authorName,
            excerpt = excerpt,
            isQuote = reply.quote != null,
            replyToChatId = reply.chatId,
            replyToMessageId = reply.messageId,
            mediaThumb = ctx.mediaThumb,
            mediaKind = ctx.mediaKind,
        )
    }

    /**
     * Coarse classification + thumbnail from a parent message's content. Stickers, video
     * notes, animations and documents all expose a `thumbnail` field; photos/videos expose
     * the largest size. Audio / voice / polls don't have stills, so the UI falls back to a
     * kind icon. When [content] is null entirely we degrade to None.
     */
    private fun extractReplyMedia(content: TdApi.MessageContent?): Pair<TdMedia?, ReplyMediaKind> = when (content) {
        is TdApi.MessagePhoto -> content.photo.toMedia() to ReplyMediaKind.Photo
        is TdApi.MessageVideo -> content.video.toThumbMedia() to ReplyMediaKind.Video
        is TdApi.MessageAnimation -> content.animation.toThumbMedia() to ReplyMediaKind.Animation
        is TdApi.MessageDocument -> (content.document.thumbnail?.toMedia()) to ReplyMediaKind.Document
        is TdApi.MessageAudio -> null to ReplyMediaKind.Audio
        is TdApi.MessageVoiceNote -> null to ReplyMediaKind.VoiceNote
        is TdApi.MessageVideoNote -> content.videoNote.thumbnail?.toMedia() to ReplyMediaKind.VideoNote
        is TdApi.MessageSticker -> content.sticker.thumbnail?.toMedia() to ReplyMediaKind.Sticker
        is TdApi.MessagePoll -> null to ReplyMediaKind.Poll
        // Paid-media posts (channel-monetisation feature): pull the first photo / video
        // thumb so the quote card still shows a preview. Kind=Photo is the visually
        // closest fit — quote cards don't have a "paid" affordance and the user knows
        // it's gated content from the original post anyway.
        is TdApi.MessagePaidMedia -> {
            val thumb = content.media.firstNotNullOfOrNull { piece ->
                when (piece) {
                    is TdApi.PaidMediaPhoto -> piece.photo.toMedia()
                    is TdApi.PaidMediaVideo -> piece.video.toThumbMedia()
                    else -> null
                }
            }
            thumb to ReplyMediaKind.Photo
        }
        // Invoices have no thumbnail surface; "Document" is the closest icon we already
        // have. Avoids the empty-author-only-strip render the user complained about.
        is TdApi.MessageInvoice -> null to ReplyMediaKind.Document
        else -> null to ReplyMediaKind.None
    }

    /**
     * First useful line of text from a parent message — the message's own text, or its
     * caption when it's a media post. Used as the quote card's body when the user did NOT
     * pin an explicit quote selection. Whitespace-only text is treated as missing so the
     * UI's `ifBlank` fallback to a kind label kicks in cleanly.
     */
    private fun extractTextOrCaption(content: TdApi.MessageContent?): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption?.text.orEmpty()
        is TdApi.MessageVideo -> content.caption?.text.orEmpty()
        is TdApi.MessageAnimation -> content.caption?.text.orEmpty()
        is TdApi.MessageDocument -> content.caption?.text.orEmpty()
        is TdApi.MessageAudio -> content.caption?.text.orEmpty()
        is TdApi.MessageVoiceNote -> content.caption?.text.orEmpty()
        is TdApi.MessagePaidMedia -> content.caption?.text.orEmpty()
        is TdApi.MessageInvoice -> content.productInfo?.title.orEmpty()
        else -> ""
    }.trim()

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
