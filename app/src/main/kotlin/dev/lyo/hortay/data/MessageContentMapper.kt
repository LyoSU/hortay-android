package dev.lyo.hortay.data

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
            items = listOf(
                AlbumItem.Photo(
                    media = content.photo.toMedia(),
                    hasSpoiler = content.hasSpoiler,
                    isSecret = content.isSecret,
                ),
            ),
            caption = mapFormattedText(content.caption),
            captionAbove = content.showCaptionAboveMedia,
        )
        is TdApi.MessageVideo -> {
            val qualities = videoQualities(content.video, content.alternativeVideos)
            // Cover (designer-supplied poster) is preferred over auto-extracted thumb.
            // Telegram lets the uploader pick a custom frame as cover — when it ships
            // we render that instead of the heuristic first-frame thumb.
            val poster = content.cover?.toMedia() ?: content.video.toThumbMedia()
            PostContent.Video(
                media = poster,
                playbackFileId = qualities.defaultPick.fileId,
                qualities = qualities,
                caption = mapFormattedText(content.caption),
                durationSec = content.video.duration,
                captionAbove = content.showCaptionAboveMedia,
                hasSpoiler = content.hasSpoiler,
                isSecret = content.isSecret,
            )
        }
        is TdApi.MessageAnimation -> PostContent.Animation(
            media = content.animation.toThumbMedia(),
            playbackFileId = content.animation.animation.id,
            caption = mapFormattedText(content.caption),
            captionAbove = content.showCaptionAboveMedia,
            hasSpoiler = content.hasSpoiler,
            isSecret = content.isSecret,
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
        is TdApi.MessageSticker -> {
            val s = content.sticker
            PostContent.Sticker(
                media = TdMedia(
                    fileId = s.sticker?.id,
                    width = s.width,
                    height = s.height,
                    minithumbBytes = null,
                ),
                thumb = s.thumbnail?.toMedia(),
                emoji = s.emoji.orEmpty(),
                format = mapStickerFormat(s.format),
            )
        }
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
        is TdApi.MessageAnimatedEmoji -> {
            val s = content.animatedEmoji?.sticker
            PostContent.AnimatedEmoji(
                emoji = content.emoji.orEmpty(),
                // sticker is null while TDLib still resolves the custom-emoji sprite — the
                // UI falls back to rendering the plain unicode emoji at display size.
                sticker = s?.let {
                    TdMedia(
                        fileId = it.sticker?.id,
                        width = it.width,
                        height = it.height,
                        minithumbBytes = null,
                    )
                },
                thumb = s?.thumbnail?.toMedia(),
                format = mapStickerFormat(s?.format),
            )
        }
        is TdApi.MessageChecklist -> {
            val list = content.list
            PostContent.Checklist(
                title = mapFormattedText(list?.title),
                tasks = list?.tasks.orEmpty().map { task ->
                    ChecklistItem(
                        text = mapFormattedText(task.text),
                        isDone = task.completionDate > 0,
                    )
                },
            )
        }
        is TdApi.MessageExpiredPhoto -> PostContent.ExpiredMedia(ExpiredKind.Photo)
        is TdApi.MessageExpiredVideo -> PostContent.ExpiredMedia(ExpiredKind.Video)
        is TdApi.MessageExpiredVideoNote -> PostContent.ExpiredMedia(ExpiredKind.VideoNote)
        is TdApi.MessageExpiredVoiceNote -> PostContent.ExpiredMedia(ExpiredKind.VoiceNote)

        // Service / system events — surfaced as a Service payload so callers can choose to
        // skip them (channel feed) or render them inline (discussion threads).
        is TdApi.MessagePinMessage -> PostContent.Service(ServiceEvent.PinnedMessage(content.messageId))
        is TdApi.MessageChatBoost -> PostContent.Service(ServiceEvent.ChannelBoosted(content.boostCount))
        is TdApi.MessageGiveawayCreated -> PostContent.Service(ServiceEvent.GiveawayStarted)
        is TdApi.MessageScreenshotTaken -> PostContent.Service(ServiceEvent.ScreenshotTaken)
        is TdApi.MessageVideoChatStarted -> PostContent.Service(ServiceEvent.VideoChatStarted(content.groupCallId))
        is TdApi.MessageVideoChatEnded -> PostContent.Service(ServiceEvent.VideoChatEnded)
        is TdApi.MessageGroupCall -> PostContent.Service(ServiceEvent.GroupCall(content.isVideo))

        // Things that are technically informational but we still render as plain text rather
        // than try to UI-design every single Telegram event type. Wrapped in Service.Other
        // so the channel feed filters them out by default.
        is TdApi.MessageContactRegistered,
        is TdApi.MessageChatJoinByLink,
        is TdApi.MessageChatJoinByRequest,
        is TdApi.MessageChatAddMembers,
        is TdApi.MessageChatDeleteMember,
        is TdApi.MessageChatChangeTitle,
        is TdApi.MessageChatChangePhoto,
        is TdApi.MessageChatDeletePhoto,
        is TdApi.MessageVideoChatScheduled,
        is TdApi.MessageBotWriteAccessAllowed,
        is TdApi.MessageCustomServiceAction,
        is TdApi.MessageWebAppDataReceived,
        is TdApi.MessageWebAppDataSent,
        is TdApi.MessageInviteVideoChatParticipants,
        is TdApi.MessageProximityAlertTriggered,
        is TdApi.MessageForumTopicCreated,
        is TdApi.MessageForumTopicEdited,
        is TdApi.MessageForumTopicIsClosedToggled,
        is TdApi.MessageForumTopicIsHiddenToggled,
        is TdApi.MessageBasicGroupChatCreate,
        is TdApi.MessageSupergroupChatCreate,
        is TdApi.MessageChatUpgradeFrom,
        is TdApi.MessageChatUpgradeTo,
        is TdApi.MessageChatSetTheme,
        is TdApi.MessageChatSetBackground,
        is TdApi.MessageChatSetMessageAutoDeleteTime -> PostContent.Service(ServiceEvent.Other)

        is TdApi.MessageInvoice -> PostContent.Unsupported("Invoice: ${content.productInfo?.title.orEmpty()}")
        is TdApi.MessageGiveaway -> PostContent.Unsupported("🎁 Giveaway")
        is TdApi.MessageGiveawayCompleted -> PostContent.Unsupported("🎁 Giveaway завершено")
        is TdApi.MessageGiveawayWinners -> PostContent.Unsupported("🎁 Giveaway · переможці")
        is TdApi.MessageGame -> PostContent.Unsupported("🎮 Game: ${content.game?.title.orEmpty()}")
        is TdApi.MessageStory -> PostContent.Unsupported("📰 Story")
        is TdApi.MessagePaidMedia -> mapPaidMedia(content)
        else -> PostContent.Unsupported(content::class.java.simpleName)
    }

    /**
     * Paid posts can mix photos and videos under the same star-cost lock. We unwrap into the
     * generic [PostContent.PhotoAlbum] so the existing album renderer handles both — videos
     * surface with their thumbnail and a play badge, identical to a free album. The "⭐"
     * intent is captured by the price, but we don't render a star bar yet (would need an
     * unlock interaction we don't support); the post still reads as media.
     */
    private fun mapPaidMedia(content: TdApi.MessagePaidMedia): PostContent {
        val items = content.media.orEmpty().mapNotNull { piece ->
            when (piece) {
                is TdApi.PaidMediaPhoto -> AlbumItem.Photo(piece.photo.toMedia())
                is TdApi.PaidMediaVideo -> {
                    // PaidMediaVideo doesn't expose alternativeVideos, so the picker
                    // is hidden (qualities.hasOptions == false). The single original
                    // quality plays back through MediaCache like any other video.
                    val qualities = VideoQualities(
                        original = videoQuality(piece.video, label = qualityLabel(piece.video.height)),
                    )
                    AlbumItem.Video(
                        media = piece.video.toThumbMedia(),
                        durationSec = piece.video.duration,
                        playbackFileId = qualities.original.fileId,
                        qualities = qualities,
                    )
                }
                else -> null
            }
        }
        return if (items.isEmpty()) PostContent.Unsupported("⭐ Платний контент")
        else PostContent.PhotoAlbum(
            items = items,
            caption = mapFormattedText(content.caption),
            captionAbove = content.showCaptionAboveMedia,
        )
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
        // Keep order TDLib gave us — it ranks by frequency. Both reaction kinds are
        // mapped: unicode emoji renders as a glyph chip, custom-emoji renders the sticker
        // resolved through CustomEmojiRepository. Buckets with unknown reaction types
        // (forward-compat) are dropped so the total stays consistent with what's drawn.
        val items = list.mapNotNull { r ->
            val kind = when (val t = r.type) {
                is TdApi.ReactionTypeEmoji -> {
                    val text = t.emoji.orEmpty()
                    if (text.isEmpty()) return@mapNotNull null
                    ReactionKind.Emoji(text)
                }
                is TdApi.ReactionTypeCustomEmoji -> {
                    if (t.customEmojiId == 0L) return@mapNotNull null
                    ReactionKind.CustomEmoji(t.customEmojiId)
                }
                else -> return@mapNotNull null
            }
            ReactionItem(kind, r.totalCount, r.isChosen)
        }
        return Reactions(items.sumOf { it.count }, items)
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

internal fun TdApi.Thumbnail.toMedia(): TdMedia = TdMedia(
    fileId = file.id,
    width = width,
    height = height,
    minithumbBytes = null,
)

/**
 * Synthesise a [VideoQualities] tree for a [TdApi.MessageVideo]. Original is the
 * uploader's file; alternatives are server-generated re-encodes (sorted HD-first
 * for the picker). When the message ships no alternatives, the resulting
 * [VideoQualities.hasOptions] is false and the picker UI hides itself.
 */
internal fun videoQualities(
    video: TdApi.Video,
    alternatives: Array<TdApi.AlternativeVideo>?,
): VideoQualities {
    val original = videoQuality(video, qualityLabel(video.height))
    val alts = alternatives.orEmpty()
        .mapNotNull { alt ->
            val file = alt.video ?: return@mapNotNull null
            VideoQuality(
                fileId = file.id,
                width = alt.width,
                height = alt.height,
                label = qualityLabel(alt.height),
                sizeBytes = file.size,
            )
        }
        // Drop duplicates that match the original's resolution — Telegram occasionally
        // ships an alternative at the same size as the original which would clutter
        // the picker with two identical-looking entries.
        .filter { it.height != original.height || it.width != original.width }
        .sortedByDescending { it.height }
    return VideoQualities(original = original, alternatives = alts)
}

internal fun videoQuality(video: TdApi.Video, label: String): VideoQuality = VideoQuality(
    fileId = video.video.id,
    width = video.width,
    height = video.height,
    label = label,
    sizeBytes = video.video.size,
)

/** Map pixel height to the user-facing label Telegram uses (720p, 1080p, 4K, …). */
internal fun qualityLabel(height: Int): String = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "1440p"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    height >= 480 -> "480p"
    height >= 360 -> "360p"
    height >= 240 -> "240p"
    height >= 144 -> "144p"
    else -> "${height}p"
}

