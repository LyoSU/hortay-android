package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi

/**
 * Stateful mapper from raw TDLib types to UI-facing [TimelinePost] / [PostContent].
 *
 * Holds per-instance caches of resolved user / supergroup / chat names so that fields like
 * `forwardOrigin` and `reply.authorName` can carry real handles ("@durov", "Telegram News")
 * instead of opaque numeric ids. Caches are scoped to the [PostsRepository] instance so
 * they don't leak between sessions.
 */
internal class MessageMapper(private val td: TdClient) {

    private val userCache = mutableMapOf<Long, ResolvedSender>()
    private val chatCache = mutableMapOf<Long, ResolvedSender>()
    private val supergroupCache = mutableMapOf<Long, String?>() // username

    suspend fun toTimelinePost(message: TdApi.Message, chat: TdApi.Chat): TimelinePost {
        val content = mapContent(message.content)
        return TimelinePost(
            id = message.id,
            chatId = message.chatId,
            mediaAlbumId = message.mediaAlbumId,
            channelTitle = chat.title.orEmpty(),
            channelHandle = resolveChannelHandle(chat),
            avatarFileId = chat.photo?.small?.id,
            content = content,
            views = message.interactionInfo?.viewCount ?: 0,
            date = message.date.toLong() * 1000L,
            editDate = message.editDate.toLong() * 1000L,
            forwardOrigin = message.forwardInfo?.origin?.let { mapForwardOrigin(it) },
            authorSignature = message.authorSignature.takeUnless { it.isNullOrBlank() },
            reply = mapReply(message.replyTo, message.chatId),
            reactions = mapReactions(message.interactionInfo?.reactions),
            commentCount = message.interactionInfo?.replyInfo?.replyCount,
        )
    }

    private suspend fun resolveChannelHandle(chat: TdApi.Chat): String? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        return supergroupCache.getOrPut(supergroupId) {
            runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }.getOrNull()
                ?.usernames?.activeUsernames?.firstOrNull()
                ?.let { "@$it" }
        }
    }

    private suspend fun resolveSender(senderId: TdApi.MessageSender): ResolvedSender = when (senderId) {
        is TdApi.MessageSenderUser -> userCache.getOrPut(senderId.userId) { fetchUser(senderId.userId) }
        is TdApi.MessageSenderChat -> chatCache.getOrPut(senderId.chatId) { fetchChat(senderId.chatId) }
        else -> ResolvedSender("—", null)
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

    private fun mapContent(content: TdApi.MessageContent): PostContent = when (content) {
        is TdApi.MessageText -> PostContent.Text(
            formatted = mapFormattedText(content.text),
            webPreview = content.linkPreview?.let(::mapWebPreview),
        )
        is TdApi.MessagePhoto -> PostContent.PhotoAlbum(
            items = listOf(AlbumItem.Photo(content.photo.toMedia())),
            caption = mapFormattedText(content.caption),
        )
        is TdApi.MessageVideo -> PostContent.Video(
            media = content.video.toThumbMedia(),
            playbackFileId = content.video.video.id,
            caption = mapFormattedText(content.caption),
            durationSec = content.video.duration,
        )
        is TdApi.MessageAnimation -> PostContent.Animation(
            media = content.animation.toThumbMedia(),
            playbackFileId = content.animation.animation.id,
            caption = mapFormattedText(content.caption),
        )
        is TdApi.MessageDocument -> PostContent.Document(
            fileId = content.document.document?.id,
            fileName = content.document.fileName.orEmpty(),
            mimeType = content.document.mimeType.orEmpty(),
            sizeBytes = content.document.document?.size ?: 0L,
            thumb = content.document.thumbnail?.toMedia(),
            caption = mapFormattedText(content.caption),
        )
        is TdApi.MessageAudio -> PostContent.Audio(
            fileId = content.audio.audio?.id,
            title = content.audio.title.orEmpty().ifBlank { content.audio.fileName.orEmpty() },
            performer = content.audio.performer.orEmpty(),
            durationSec = content.audio.duration,
        )
        is TdApi.MessageVoiceNote -> PostContent.VoiceNote(
            fileId = content.voiceNote.voice?.id,
            durationSec = content.voiceNote.duration,
            waveform = content.voiceNote.waveform,
        )
        is TdApi.MessageVideoNote -> PostContent.VideoNote(
            thumb = content.videoNote.thumbnail?.toMedia(),
            durationSec = content.videoNote.duration,
        )
        is TdApi.MessageSticker -> PostContent.Sticker(
            media = content.sticker.toMedia(),
            emoji = content.sticker.emoji.orEmpty(),
            format = mapStickerFormat(content.sticker.format),
        )
        is TdApi.MessagePoll -> PostContent.Poll(
            question = content.poll.question?.text.orEmpty(),
            options = content.poll.options.orEmpty().map { opt ->
                PollOption(
                    text = opt.text?.text.orEmpty(),
                    voterCount = opt.voterCount,
                    percent = opt.votePercentage,
                )
            },
            totalVotes = content.poll.totalVoterCount,
            isAnonymous = content.poll.isAnonymous,
            isClosed = content.poll.isClosed,
        )
        is TdApi.MessageLocation -> PostContent.Location(
            latitude = content.location.latitude,
            longitude = content.location.longitude,
            title = null,
            address = null,
        )
        is TdApi.MessageVenue -> PostContent.Location(
            latitude = content.venue.location.latitude,
            longitude = content.venue.location.longitude,
            title = content.venue.title.orEmpty().takeUnless { it.isBlank() },
            address = content.venue.address.orEmpty().takeUnless { it.isBlank() },
        )
        is TdApi.MessageContact -> PostContent.Contact(
            name = listOfNotNull(
                content.contact.firstName?.takeUnless { it.isBlank() },
                content.contact.lastName?.takeUnless { it.isBlank() },
            ).joinToString(" "),
            phone = content.contact.phoneNumber.orEmpty(),
        )
        is TdApi.MessageDice -> PostContent.Dice(
            emoji = content.emoji.orEmpty(),
            value = content.value,
        )
        else -> PostContent.Unsupported(content::class.java.simpleName)
    }

    private fun mapFormattedText(t: TdApi.FormattedText?): FormattedText {
        if (t == null || t.text.isNullOrEmpty()) return FormattedText.Empty
        val spans = t.entities.orEmpty().mapNotNull { entity ->
            val style = mapEntityStyle(entity.type) ?: return@mapNotNull null
            FormattedText.Span(
                start = entity.offset,
                end = entity.offset + entity.length,
                style = style,
            )
        }
        return FormattedText(t.text, spans)
    }

    private fun mapEntityStyle(type: TdApi.TextEntityType?): FormattedText.Style? = when (type) {
        is TdApi.TextEntityTypeBold -> FormattedText.Style.Bold
        is TdApi.TextEntityTypeItalic -> FormattedText.Style.Italic
        is TdApi.TextEntityTypeUnderline -> FormattedText.Style.Underline
        is TdApi.TextEntityTypeStrikethrough -> FormattedText.Style.Strikethrough
        is TdApi.TextEntityTypeCode -> FormattedText.Style.Code
        is TdApi.TextEntityTypePre -> FormattedText.Style.Pre(language = null)
        is TdApi.TextEntityTypePreCode -> FormattedText.Style.Pre(language = type.language)
        is TdApi.TextEntityTypeTextUrl -> FormattedText.Style.TextUrl(type.url)
        is TdApi.TextEntityTypeUrl -> FormattedText.Style.Url
        is TdApi.TextEntityTypeMention -> FormattedText.Style.Mention
        is TdApi.TextEntityTypeMentionName -> FormattedText.Style.MentionName(type.userId)
        is TdApi.TextEntityTypeHashtag -> FormattedText.Style.Hashtag
        is TdApi.TextEntityTypeBotCommand -> FormattedText.Style.BotCommand
        is TdApi.TextEntityTypeSpoiler -> FormattedText.Style.Spoiler
        is TdApi.TextEntityTypeCustomEmoji -> FormattedText.Style.CustomEmoji(type.customEmojiId)
        is TdApi.TextEntityTypeBlockQuote -> FormattedText.Style.BlockQuote
        is TdApi.TextEntityTypeExpandableBlockQuote -> FormattedText.Style.BlockQuote
        else -> null
    }

    private fun mapWebPreview(p: TdApi.LinkPreview): WebPreview = WebPreview(
        url = p.url.orEmpty(),
        siteName = p.siteName.orEmpty(),
        title = p.title.orEmpty(),
        description = p.description?.text.orEmpty(),
        image = p.type?.let { type ->
            when (type) {
                is TdApi.LinkPreviewTypeArticle -> type.photo?.toMedia()
                is TdApi.LinkPreviewTypePhoto -> type.photo.toMedia()
                is TdApi.LinkPreviewTypeVideo -> type.video?.thumbnail?.toMedia()
                else -> null
            }
        },
    )

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
        // Resolve real author via GetMessage on the referenced thread.
        val author = runCatching {
            val refMsg = td.send(TdApi.GetMessage(reply.chatId, reply.messageId))
            resolveSender(refMsg.senderId).name
        }.getOrNull() ?: chatCache.getOrPut(chatId) { fetchChat(chatId) }.name
        return ReplyPreview(
            authorName = author,
            excerpt = excerpt,
            isQuote = reply.quote != null,
        )
    }

    private fun mapReactions(reactions: TdApi.MessageReactions?): Reactions {
        val list = reactions?.reactions.orEmpty()
        if (list.isEmpty()) return Reactions(0, emptyList())
        val total = list.sumOf { it.totalCount }
        val emojis = list.take(3).mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
        return Reactions(total, emojis)
    }

    private fun mapStickerFormat(format: TdApi.StickerFormat?): StickerFormat = when (format) {
        is TdApi.StickerFormatTgs -> StickerFormat.Tgs
        is TdApi.StickerFormatWebm -> StickerFormat.Webm
        else -> StickerFormat.Webp
    }

    private data class ResolvedSender(val name: String, val avatarFileId: Int?)
}

private fun TdApi.Photo.toMedia(): TdMedia {
    val largest = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.first()
    return TdMedia(
        fileId = largest.photo.id,
        width = largest.width,
        height = largest.height,
        minithumbBytes = minithumbnail?.data,
    )
}

private fun TdApi.Video.toThumbMedia(): TdMedia = TdMedia(
    fileId = thumbnail?.file?.id ?: video.id,
    width = width,
    height = height,
    minithumbBytes = minithumbnail?.data,
)

private fun TdApi.Animation.toThumbMedia(): TdMedia = TdMedia(
    fileId = thumbnail?.file?.id ?: animation.id,
    width = width,
    height = height,
    minithumbBytes = minithumbnail?.data,
)

private fun TdApi.Sticker.toMedia(): TdMedia = TdMedia(
    fileId = thumbnail?.file?.id ?: sticker.id,
    width = width,
    height = height,
    minithumbBytes = null,
)

private fun TdApi.Thumbnail.toMedia(): TdMedia = TdMedia(
    fileId = file.id,
    width = width,
    height = height,
    minithumbBytes = null,
)
