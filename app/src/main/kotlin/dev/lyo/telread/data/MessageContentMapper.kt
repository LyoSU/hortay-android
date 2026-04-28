package dev.lyo.telread.data

import org.drinkless.tdlib.TdApi

/**
 * Pure, synchronous mapper from [TdApi.MessageContent] to UI-facing [PostContent].
 *
 * Lives outside [MessageMapper] (which is stateful — caches usernames/avatars) so any
 * callsite that just needs to render content (channel posts, comments, reply previews,
 * forwards) can share the same exhaustive type coverage.
 */
internal object MessageContentMapper {

    fun map(content: TdApi.MessageContent): PostContent = when (content) {
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
        is TdApi.MessageInvoice -> PostContent.Unsupported("Invoice: ${content.productInfo?.title.orEmpty()}")
        is TdApi.MessageGiveaway -> PostContent.Unsupported("🎁 Giveaway")
        is TdApi.MessageGiveawayCompleted -> PostContent.Unsupported("🎁 Giveaway завершено")
        is TdApi.MessageGiveawayWinners -> PostContent.Unsupported("🎁 Giveaway · переможці")
        is TdApi.MessageGame -> PostContent.Unsupported("🎮 Game: ${content.game?.title.orEmpty()}")
        is TdApi.MessageStory -> PostContent.Unsupported("📰 Story")
        is TdApi.MessagePaidMedia -> {
            val first = content.media?.firstOrNull()
            when (first) {
                is TdApi.PaidMediaPhoto -> PostContent.PhotoAlbum(
                    items = content.media.orEmpty().mapNotNull { (it as? TdApi.PaidMediaPhoto)?.photo?.toMedia()?.let(AlbumItem::Photo) },
                    caption = mapFormattedText(content.caption),
                )
                else -> PostContent.Unsupported("⭐ Платний контент")
            }
        }
        else -> PostContent.Unsupported(content::class.java.simpleName)
    }

    fun mapFormattedText(t: TdApi.FormattedText?): FormattedText {
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

    fun mapReactions(reactions: TdApi.MessageReactions?): Reactions {
        val list = reactions?.reactions.orEmpty()
        if (list.isEmpty()) return Reactions(0, emptyList())
        val total = list.sumOf { it.totalCount }
        // Keep order TDLib gave us — it ranks by frequency. Custom-emoji buckets are skipped
        // for now (we'd need GetCustomEmojiStickers + sticker rendering to show them).
        val items = list.mapNotNull { r ->
            val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
            ReactionItem(emoji, r.totalCount)
        }
        return Reactions(total, items)
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

    private fun mapStickerFormat(format: TdApi.StickerFormat?): StickerFormat = when (format) {
        is TdApi.StickerFormatTgs -> StickerFormat.Tgs
        is TdApi.StickerFormatWebm -> StickerFormat.Webm
        else -> StickerFormat.Webp
    }
}

internal fun TdApi.Photo.toMedia(): TdMedia {
    val largest = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.first()
    return TdMedia(
        fileId = largest.photo.id,
        width = largest.width,
        height = largest.height,
        minithumbBytes = minithumbnail?.data,
    )
}

internal fun TdApi.Video.toThumbMedia(): TdMedia = TdMedia(
    // Only use the (image-decodable) thumbnail file. Falling back to video.id pointed Coil at
    // the MP4 itself, which never decodes — leaving the GIF/video box blank until playback.
    fileId = thumbnail?.file?.id,
    width = width,
    height = height,
    minithumbBytes = minithumbnail?.data,
)

internal fun TdApi.Animation.toThumbMedia(): TdMedia = TdMedia(
    fileId = thumbnail?.file?.id,
    width = width,
    height = height,
    minithumbBytes = minithumbnail?.data,
)

internal fun TdApi.Sticker.toMedia(): TdMedia = TdMedia(
    // For static (WEBP) stickers the sticker file IS the image, so the fallback is fine.
    // Animated formats (TGS/WebM) need their own renderer; for now the thumbnail is the
    // best preview available.
    fileId = thumbnail?.file?.id ?: sticker.id,
    width = width,
    height = height,
    minithumbBytes = null,
)

internal fun TdApi.Thumbnail.toMedia(): TdMedia = TdMedia(
    fileId = file.id,
    width = width,
    height = height,
    minithumbBytes = null,
)
