package dev.lyo.hortay.data

import dev.lyo.hortay.R
import org.drinkless.tdlib.TdApi

/**
 * Async layer over [MessageContentMapper] that resolves real channel handles, forward
 * author names, reply preview authors, and discussion-comment sender info.
 *
 * Single mapper per session: PostsRepository (channel feed) and CommentsRepository
 * (discussion threads) share an instance — the resolver methods here drive author /
 * handle / verification badges for both surfaces, so they share the same TDLib path.
 *
 * No in-process LRU. Per TDLib docs:
 *
 *   "getUser / getChat / getSupergroup — This is an offline method if the current
 *    user is not a bot."
 *
 * With `useChatInfoDatabase = true` TDLib keeps Users / Chats / Supergroups in its
 * own local store and re-emits `updateUser` / `updateNewChat` / `updateSupergroup`
 * BEFORE returning any id that references them, so by the time we hold a
 * `userId` / `chatId` / `supergroupId` the upstream cache is already warm. Layering
 * our own `LinkedHashMap` LRU on top added microseconds of "win" and a steady supply
 * of stale-rename / stale-avatar bugs whenever an UpdateUser / UpdateSupergroup
 * arrived while a row was already on screen.
 */
class MessageMapper(private val td: TdSender, private val res: StringResolver) {

    private val defaultUserName: String get() = res.getString(R.string.user_default_name)
    private val defaultChannelName: String get() = res.getString(R.string.channel_default_name)

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
            // Personal-author posts: surface the human's user id so the avatar/name
            // taps route into the user-profile sheet instead of the channel sheet.
            // Channel-as-sender posts leave this null — the chat header is the right
            // affordance there.
            senderUserId = (sender as? TdApi.MessageSenderUser)?.userId,
            // Foreign-chat-as-sender (case 3): admin posted on behalf of one of their
            // OTHER channels. Header surfaces that foreign chat; tap routes into it
            // through [safelyOpenChannel] (same kind-gate the deep-link dispatcher uses).
            // Skip the trivial case where the chat sender IS the host channel — that's
            // case 1 and the avatar/name already point at the host, no separate target.
            senderChatId = (sender as? TdApi.MessageSenderChat)
                ?.chatId
                ?.takeUnless { it == chat.id },
            // Per-chat report eligibility from TDLib (TdApi.Chat.canBeReported).
            // Cheaper than [TdApi.GetMessageProperties] per post — TDLib already
            // populated this field on the Chat object via chatCache updates, and
            // for the long-press action sheet's "Report" row gate, chat-level
            // truth is sufficient. Telegram-Android uses the same gate on its
            // own post overflow menu.
            canReportChat = chat.canBeReported,
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
            senderUserId = (message.senderId as? TdApi.MessageSenderUser)?.userId,
            // Comment authored on behalf of a channel (rare anonymous-admin case where
            // the admin replies "as the channel" inside the linked discussion group).
            // The thread's hosting chat IS the discussion supergroup, so there is no
            // host/foreign discriminator here — every chat-sender id is "foreign" to
            // the human reader and worth a tap target. Null for human commenters.
            senderChatId = (message.senderId as? TdApi.MessageSenderChat)?.chatId,
            isSenderPremium = sender.isPremium,
        )
    }

    suspend fun resolveSender(senderId: TdApi.MessageSender): ResolvedSender = when (senderId) {
        is TdApi.MessageSenderUser -> fetchUser(senderId.userId)
        is TdApi.MessageSenderChat -> fetchChat(senderId.chatId)
        else -> ResolvedSender("—", null, null, null)
    }

    /**
     * No-op kept on the type surface because [AppGraph] still calls it from its
     * logout handler. The mapper holds no session-scoped state of its own — every
     * resolver round-trips to TDLib, which clears its own User / Chat / Supergroup
     * caches on logout. Leaving this here as `{}` is cheaper than threading a
     * conditional through `AppGraph` for a hot path that runs at most once per
     * session.
     */
    fun clear() {
        // Intentionally empty — see KDoc.
    }

    private suspend fun resolveChannelHandle(chat: TdApi.Chat): String? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        val sg = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
        return sg?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }
    }

    private suspend fun resolveChannelVerification(chat: TdApi.Chat): SenderVerification? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        val sg = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
        return sg?.verificationStatus?.toMark()
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
            isPremium = u.isPremium,
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
            userName = fetchUser(origin.senderUserId).name,
            userId = origin.senderUserId,
        )
        is TdApi.MessageOriginChat -> {
            val resolved = fetchChat(origin.senderChatId)
            ForwardOrigin.Chat(
                chatName = resolved.name,
                authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
                sourceChatId = origin.senderChatId,
                sourceHandle = resolved.handle,
            )
        }
        is TdApi.MessageOriginHiddenUser -> ForwardOrigin.HiddenUser(origin.senderName.orEmpty())
        is TdApi.MessageOriginChannel -> {
            val resolved = fetchChat(origin.chatId)
            ForwardOrigin.Channel(
                channelName = resolved.name,
                authorSignature = origin.authorSignature?.takeUnless { it.isNullOrBlank() },
                sourceChatId = origin.chatId,
                sourceHandle = resolved.handle,
                sourceMessageId = origin.messageId.takeIf { it != 0L },
            )
        }
        else -> ForwardOrigin.HiddenUser("")
    }

    private suspend fun mapReply(replyTo: TdApi.MessageReplyTo?, chatId: Long): ReplyPreview? {
        val reply = replyTo as? TdApi.MessageReplyToMessage ?: return null
        // Normalise TDLib's "unknown chat" sentinel at the mapping boundary so every
        // downstream consumer (feed grouping, channel/comments quote-tap navigation,
        // album-anchor threading) reads a usable chat id without per-site fallbacks.
        // Per [TdApi.MessageReplyToMessage.chat_id] docstring:
        //   "The identifier of the chat to which the message belongs; may be 0 if
        //    the replied message is in unknown chat."
        // And Aliaksei Levin on tdlib/td#2855: `InputMessageReplyToMessage.chat_id = 0`
        // is the canonical "same chat as the SendMessage target" signal on the
        // INPUT side, and clients that pass it through unchanged surface
        // round-tripped `messageReplyToMessage { chat_id = 0, message_id = 0 }` on
        // the OUTPUT side. The "truly unknown chat" case (TDLib has no record of
        // the replied chat) sets BOTH fields to 0, in which case there's no
        // useful navigation target — we still surface the quote card (author /
        // excerpt / thumb travel via the embedded snapshot) but the
        // `replyToChatId/replyToMessageId` pair stays zero and consumer
        // navigation taps land on a "link not found" snackbar, the right
        // behaviour for that branch.
        // Bias for the ambiguous middle case (`chat_id = 0` with
        // `message_id != 0`): treat it as "same chat as the host post" — that's
        // the dominant client-bug shape per tdlib/td#2855 and matches
        // Telegram-Android's own quote-card behaviour.
        val effectiveReplyChatId = if (reply.chatId != 0L) reply.chatId
        else if (reply.messageId != 0L) chatId
        else 0L

        // Round-trip policy:
        //   • TDLib gave us a non-null content snapshot → we have everything we need
        //     (kind, thumb, caption text); skip GetMessage and resolve the author from
        //     the chat. Fast path; covers most public channel posts.
        //   • Snapshot is null → fall back to GetMessage to get content + author. This
        //     is the path where TDLib doesn't preload reply content (most channel-side
        //     reply-to-channel-post pathways). `GetMessage` is local-cache-first
        //     when `useMessageDatabase = true` — no network round-trip when the parent
        //     is already in the message DB.
        val needsFetch = reply.content == null && effectiveReplyChatId != 0L && reply.messageId != 0L
        val refMsg = if (needsFetch) {
            runCatching { td.send(TdApi.GetMessage(effectiveReplyChatId, reply.messageId)) }
                .getOrNull()
        } else null
        val author = refMsg?.let { resolveSender(it.senderId).name }
            ?: if (effectiveReplyChatId != 0L) fetchChat(effectiveReplyChatId).name else ""
        val effectiveContent: TdApi.MessageContent? = refMsg?.content ?: reply.content
        val (thumb, kind) = extractReplyMedia(effectiveContent)
        val fallback = extractTextOrCaption(effectiveContent)

        // Excerpt priority:
        //   1. Explicit user-selected quote (Telegram's quote-text feature).
        //   2. The parent's own text or caption (resolved via GetMessage above).
        //   3. Empty — the kind label takes over in the UI.
        val excerpt = reply.quote?.text?.text.orEmpty().ifBlank { fallback }

        return ReplyPreview(
            authorName = author,
            excerpt = excerpt,
            isQuote = reply.quote != null,
            replyToChatId = effectiveReplyChatId,
            replyToMessageId = reply.messageId,
            mediaThumb = thumb,
            mediaKind = kind,
        )
    }

    /**
     * Coarse classification + thumbnail from a parent message's content. Stickers, video
     * notes, animations and documents all expose a `thumbnail` field; photos/videos expose
     * the largest size. Audio / voice / polls don't have stills, so the UI falls back to a
     * kind icon. When [content] is null entirely we degrade to None.
     */
    private fun extractReplyMedia(content: TdApi.MessageContent?): Pair<TdMedia?, ReplyMediaKind> = when (content) {
        // Reply previews paint at ~40 dp — Preview tier (≈`m`, 320 px) is the
        // smallest variant that still survives a 3x density without visible
        // pixelation. Pulling the inline / fullscreen variant would burn
        // 3-4× the bytes for a thumbnail the user reads as "icon".
        is TdApi.MessagePhoto -> content.photo.toMedia(PHOTO_TARGET_PREVIEW_PX) to ReplyMediaKind.Photo
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
                    is TdApi.PaidMediaPhoto -> piece.photo.toMedia(PHOTO_TARGET_PREVIEW_PX)
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
        /**
         * Mirrors `TdApi.User.isPremium`. False for chat-sender messages and for
         * users we couldn't resolve (`GetUser` failed) — both are non-premium by
         * construction.
         */
        val isPremium: Boolean = false,
    )
}

private fun TdApi.VerificationStatus.toMark(): SenderVerification? = when {
    isVerified -> SenderVerification.Verified
    isScam -> SenderVerification.Scam
    isFake -> SenderVerification.Fake
    else -> null
}
