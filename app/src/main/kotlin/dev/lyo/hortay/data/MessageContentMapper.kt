package dev.lyo.hortay.data

import dev.lyo.hortay.R
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import org.drinkless.tdlib.TdApi

/**
 * Pure, synchronous mapper from [TdApi.MessageContent] to UI-facing [PostContent].
 *
 * Lives outside [MessageMapper] (which is stateful — caches usernames/avatars) so any
 * callsite that just needs to render content (channel posts, comments, reply previews,
 * forwards) can share the same exhaustive type coverage.
 */
internal object MessageContentMapper {

    fun map(content: TdApi.MessageContent, res: StringResolver): PostContent = when (content) {
        is TdApi.MessageText -> PostContent.Text(
            formatted = mapFormattedText(content.text),
            webPreview = content.linkPreview?.let(::mapWebPreview),
        )
        is TdApi.MessagePhoto -> PostContent.PhotoAlbum(
            items = listOf(
                AlbumItem.Photo(
                    media = content.photo.toMedia(PHOTO_TARGET_INLINE_PX),
                    fullscreen = content.photo.toMedia(PHOTO_TARGET_FULLSCREEN_PX),
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
            // Poster is rendered inline in the feed and never independently
            // pinch-zoomed (tap opens the video, not the still). Inline tier
            // is the right cap.
            val poster = content.cover?.toMedia(PHOTO_TARGET_INLINE_PX) ?: content.video.toThumbMedia()
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
        is TdApi.MessagePoll -> mapPoll(content)
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

        is TdApi.MessageInvoice -> PostContent.OpenInSource(
            iconSymbol = "description",
            title = res.getString(R.string.content_invoice),
            subtitle = content.productInfo?.title.orEmpty(),
        )
        is TdApi.MessageGiveaway -> PostContent.OpenInSource(
            iconSymbol = "card_giftcard",
            title = res.getString(R.string.content_giveaway),
        )
        is TdApi.MessageGiveawayCompleted -> PostContent.OpenInSource(
            iconSymbol = "card_giftcard",
            title = res.getString(R.string.content_giveaway_completed),
        )
        is TdApi.MessageGiveawayWinners -> PostContent.OpenInSource(
            iconSymbol = "card_giftcard",
            title = res.getString(R.string.content_giveaway_winners),
        )
        is TdApi.MessageGame -> PostContent.OpenInSource(
            iconSymbol = "info",
            title = res.getString(R.string.content_game),
            subtitle = content.game?.title.orEmpty(),
        )
        is TdApi.MessageStory -> PostContent.OpenInSource(
            iconSymbol = "visibility",
            title = res.getString(R.string.content_story),
        )
        is TdApi.MessagePremiumGiftCode -> PostContent.OpenInSource(
            iconSymbol = "card_giftcard",
            title = res.getString(R.string.content_premium_gift),
        )
        is TdApi.MessageGift -> PostContent.OpenInSource(
            iconSymbol = "card_giftcard",
            title = res.getString(R.string.content_gift),
        )
        is TdApi.MessagePaidMedia -> mapPaidMedia(content, res)
        else -> PostContent.Unsupported(content::class.java.simpleName)
    }

    /**
     * Paid posts can mix unlocked photos/videos with locked [TdApi.PaidMediaPreview]
     * placeholders. We surface this as [PostContent.PaidMedia] regardless: the model
     * carries the starCount so the UI can stamp a "⭐ N" chip on unlocked posts and
     * render a lock card with the price when everything is gated. Previously we
     * either dropped the starCount (rendering paid posts as free albums) or, when
     * every media piece was locked, downgraded to [Unsupported] — which the feed
     * filter then deleted, silently hiding the post from the user.
     */
    private fun mapPaidMedia(content: TdApi.MessagePaidMedia, res: StringResolver): PostContent {
        val items = content.media.orEmpty().mapNotNull { piece ->
            when (piece) {
                is TdApi.PaidMediaPhoto -> AlbumItem.Photo(
                    media = piece.photo.toMedia(PHOTO_TARGET_INLINE_PX),
                    fullscreen = piece.photo.toMedia(PHOTO_TARGET_FULLSCREEN_PX),
                )
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
                // Locked PaidMediaPreview / PaidMediaUnsupported pieces don't ship
                // an AlbumItem we can render. They still count toward "the post is
                // paid and at least partially locked" — the wrapper's starCount
                // surfaces that. Reaching the UI as null entries just trims the
                // unlocked album.
                else -> null
            }
        }
        @Suppress("UNUSED_PARAMETER")
        res // Reserved for future localised lock labels; intentionally untouched.
        return PostContent.PaidMedia(
            items = items,
            starCount = content.starCount,
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
        // Keep order TDLib gave us — it ranks by frequency. All three reaction kinds
        // are mapped: unicode emoji renders as a glyph chip, custom-emoji renders the
        // sticker resolved through CustomEmojiRepository, and Telegram Stars paid
        // reactions render as a star pill. Buckets with unknown reaction types
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
                is TdApi.ReactionTypePaid -> ReactionKind.Paid
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

    /**
     * Map [TdApi.LinkPreview] to UI-facing [WebPreview]. Exhaustive over the
     * 38 `LinkPreviewType*` subclasses TDLib currently ships — every variant
     * yields a [WebPreviewKind] (so the UI can pick a fallback icon when no
     * photo is attached) and, where the payload carries one, a thumbnail in
     * the preview tier (≈`m`, 320 px).
     *
     * Link-preview thumbs render in a small leading box (compact mode) or a
     * full-width media card (large mode, gated on [WebPreview.showLargeMedia])
     * and never open into a zoomable viewer — the tap opens the URL. Preview
     * tier is sized accordingly: it covers the rendered size at any phone
     * density without burning the bytes / decode CPU of the inline / `w`
     * variants.
     */
    private fun mapWebPreview(p: TdApi.LinkPreview): WebPreview = WebPreview(
        url = p.url.orEmpty(),
        displayUrl = p.displayUrl.orEmpty(),
        siteName = p.siteName.orEmpty(),
        title = p.title.orEmpty(),
        description = p.description?.text.orEmpty(),
        author = p.author.orEmpty(),
        image = mapWebPreviewImage(p.type),
        kind = mapWebPreviewKind(p.type),
        // hasLargeMedia means "a larger variant exists", showLargeMedia means
        // "TDLib's renderer would actually pick it". The OR makes our card
        // marginally more generous than vanilla Telegram — when a server says
        // the large variant exists at all, we show it; the alternative
        // ("the small thumb plus tiny description column") rarely reads well
        // for video / animation / album previews.
        showLargeMedia = p.showLargeMedia || p.hasLargeMedia,
        showMediaAboveDescription = p.showMediaAboveDescription,
        showAboveText = p.showAboveText,
    )

    /**
     * Pick a [WebPreviewKind] for the preview's TDLib type. Visible to tests
     * as `internal` so [dev.lyo.hortay.data.MessageContentMapperWebPreviewTest]
     * can guard the mapping without spinning up a full [TdApi.LinkPreview] for
     * every kind.
     */
    internal fun mapWebPreviewKind(type: TdApi.LinkPreviewType?): WebPreviewKind = when (type) {
        null -> WebPreviewKind.Article
        is TdApi.LinkPreviewTypeArticle,
        is TdApi.LinkPreviewTypeMessage -> WebPreviewKind.Article
        is TdApi.LinkPreviewTypePhoto -> WebPreviewKind.Photo
        is TdApi.LinkPreviewTypeVideo,
        is TdApi.LinkPreviewTypeVideoNote,
        is TdApi.LinkPreviewTypeEmbeddedVideoPlayer -> WebPreviewKind.Video
        is TdApi.LinkPreviewTypeAnimation,
        is TdApi.LinkPreviewTypeEmbeddedAnimationPlayer -> WebPreviewKind.Animation
        is TdApi.LinkPreviewTypeAudio,
        is TdApi.LinkPreviewTypeVoiceNote,
        is TdApi.LinkPreviewTypeEmbeddedAudioPlayer -> WebPreviewKind.Audio
        is TdApi.LinkPreviewTypeDocument,
        is TdApi.LinkPreviewTypeBackground -> WebPreviewKind.Document
        is TdApi.LinkPreviewTypeAlbum -> WebPreviewKind.Album
        is TdApi.LinkPreviewTypeApp -> WebPreviewKind.App
        is TdApi.LinkPreviewTypeChat,
        is TdApi.LinkPreviewTypeDirectMessagesChat,
        is TdApi.LinkPreviewTypeVideoChat,
        is TdApi.LinkPreviewTypeChannelBoost,
        is TdApi.LinkPreviewTypeSupergroupBoost,
        is TdApi.LinkPreviewTypeGroupCall,
        is TdApi.LinkPreviewTypeShareableChatFolder -> WebPreviewKind.Chat
        is TdApi.LinkPreviewTypeUser -> WebPreviewKind.User
        is TdApi.LinkPreviewTypeSticker -> WebPreviewKind.Sticker
        is TdApi.LinkPreviewTypeStickerSet -> WebPreviewKind.StickerSet
        is TdApi.LinkPreviewTypeStory,
        is TdApi.LinkPreviewTypeStoryAlbum,
        is TdApi.LinkPreviewTypeLiveStory -> WebPreviewKind.Story
        is TdApi.LinkPreviewTypeWebApp,
        is TdApi.LinkPreviewTypeRequestManagedBot -> WebPreviewKind.WebApp
        is TdApi.LinkPreviewTypeGiftAuction,
        is TdApi.LinkPreviewTypeGiftCollection,
        is TdApi.LinkPreviewTypeUpgradedGift,
        is TdApi.LinkPreviewTypePremiumGiftCode -> WebPreviewKind.Gift
        is TdApi.LinkPreviewTypeInvoice -> WebPreviewKind.Invoice
        is TdApi.LinkPreviewTypeTheme -> WebPreviewKind.Theme
        is TdApi.LinkPreviewTypeExternalAudio,
        is TdApi.LinkPreviewTypeExternalVideo -> WebPreviewKind.External
        is TdApi.LinkPreviewTypeUnsupported -> WebPreviewKind.Unsupported
        else -> WebPreviewKind.Unsupported
    }

    /**
     * Pick a thumbnail [TdMedia] for the preview. Returns null when the type
     * carries no image payload (giveaway, invoice, group-call, etc.) — UI then
     * renders an icon-only tile based on [WebPreviewKind].
     */
    internal fun mapWebPreviewImage(type: TdApi.LinkPreviewType?): TdMedia? = when (type) {
        null -> null
        is TdApi.LinkPreviewTypeArticle -> type.photo?.toMedia(PHOTO_TARGET_PREVIEW_PX)
        is TdApi.LinkPreviewTypePhoto -> type.photo.toMedia(PHOTO_TARGET_PREVIEW_PX)
        is TdApi.LinkPreviewTypeVideo ->
            type.cover?.toMedia(PHOTO_TARGET_PREVIEW_PX) ?: type.video?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeAnimation -> type.animation?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeApp -> type.photo?.toMedia(PHOTO_TARGET_PREVIEW_PX)
        is TdApi.LinkPreviewTypeAudio -> type.audio?.albumCoverThumbnail?.toMedia()
        is TdApi.LinkPreviewTypeDocument -> type.document?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeEmbeddedAnimationPlayer ->
            type.thumbnail?.toMedia(PHOTO_TARGET_PREVIEW_PX)
                ?: type.animation?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeEmbeddedAudioPlayer ->
            type.thumbnail?.toMedia(PHOTO_TARGET_PREVIEW_PX)
                ?: type.audio?.albumCoverThumbnail?.toMedia()
        is TdApi.LinkPreviewTypeEmbeddedVideoPlayer ->
            type.thumbnail?.toMedia(PHOTO_TARGET_PREVIEW_PX)
                ?: type.video?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeBackground -> type.document?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeChannelBoost -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeSupergroupBoost -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeChat -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeDirectMessagesChat -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeUser -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeVideoChat -> type.photo?.toMedia()
        is TdApi.LinkPreviewTypeSticker -> type.sticker?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeStickerSet -> type.stickers?.firstOrNull()?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeStoryAlbum ->
            type.photoIcon?.toMedia(PHOTO_TARGET_PREVIEW_PX)
                ?: type.videoIcon?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeWebApp -> type.photo?.toMedia(PHOTO_TARGET_PREVIEW_PX)
        is TdApi.LinkPreviewTypeTheme -> type.documents?.firstOrNull()?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeAlbum -> firstAlbumMediaThumb(type.media)
        is TdApi.LinkPreviewTypeGiftAuction -> type.gift?.sticker?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeGiftCollection ->
            type.icons?.firstOrNull()?.thumbnail?.toMedia()
        is TdApi.LinkPreviewTypeUpgradedGift -> null
        // Variants without any inline image payload — UI falls back to an
        // icon tile derived from the kind.
        is TdApi.LinkPreviewTypeExternalAudio,
        is TdApi.LinkPreviewTypeExternalVideo,
        is TdApi.LinkPreviewTypeGroupCall,
        is TdApi.LinkPreviewTypeInvoice,
        is TdApi.LinkPreviewTypeLiveStory,
        is TdApi.LinkPreviewTypeMessage,
        is TdApi.LinkPreviewTypePremiumGiftCode,
        is TdApi.LinkPreviewTypeRequestManagedBot,
        is TdApi.LinkPreviewTypeShareableChatFolder,
        is TdApi.LinkPreviewTypeStory,
        is TdApi.LinkPreviewTypeVideoNote,
        is TdApi.LinkPreviewTypeVoiceNote,
        is TdApi.LinkPreviewTypeUnsupported -> null
        else -> null
    }

    private fun firstAlbumMediaThumb(media: Array<TdApi.LinkPreviewAlbumMedia>?): TdMedia? {
        val first = media?.firstOrNull() ?: return null
        return when (first) {
            is TdApi.LinkPreviewAlbumMediaPhoto -> first.photo?.toMedia(PHOTO_TARGET_PREVIEW_PX)
            is TdApi.LinkPreviewAlbumMediaVideo -> first.video?.thumbnail?.toMedia()
            else -> null
        }
    }

    private fun mapStickerFormat(format: TdApi.StickerFormat?): StickerFormat = when (format) {
        is TdApi.StickerFormatTgs -> StickerFormat.Tgs
        is TdApi.StickerFormatWebm -> StickerFormat.Webm
        else -> StickerFormat.Webp
    }

    /**
     * Map a TDLib [TdApi.MessagePoll] to [PostContent.Poll].
     *
     * Honours `Poll.optionOrder`: when non-empty, TDLib wants us to render options in
     * that sequence (admins can shuffle display order without changing the underlying 0-based
     * ids `SetPollAnswer` expects). [PollOption.index] keeps the original 0-based id so voting
     * stays correct regardless of render order.
     *
     * The new "Polls 2.0" schema (TDLib master, May 2026) extends polls with [description],
     * [headerMedia], [canAddOption], and per-option media. We surface what's renderable inline
     * (photo banner + per-option photo thumb) and let the rest tap through to Telegram.
     */
    private fun mapPoll(msg: TdApi.MessagePoll): PostContent.Poll {
        val poll = msg.poll
        val rawOptions = poll.options.orEmpty()
        val mapped = rawOptions.mapIndexed { idx, opt ->
            PollOption(
                index = idx,
                text = mapFormattedText(opt.text),
                voterCount = opt.voterCount,
                percent = opt.votePercentage,
                isChosen = opt.isChosen,
                isBeingChosen = opt.isBeingChosen,
                thumb = pollMediaThumb(opt.media),
            )
        }
        // optionOrder is a permutation of indices; when present, render in that sequence.
        // Empty/malformed → fall back to natural order.
        val order: IntArray? = poll.optionOrder
        val ordered = if (order != null && order.isNotEmpty() && order.size == mapped.size) {
            val reordered = order.asList().mapNotNull { mapped.getOrNull(it) }
            if (reordered.size == mapped.size) reordered else mapped
        } else {
            mapped
        }
        val kind = when (val t = poll.type) {
            is TdApi.PollTypeQuiz -> PollKind.Quiz(
                correctOptionIds = (t.correctOptionIds ?: IntArray(0)).toList().toImmutableList(),
                explanation = mapFormattedText(t.explanation),
            )
            else -> {
                // PollTypeRegular and any future subtypes — Regular is the safe default.
                val reg = poll.type as? TdApi.PollTypeRegular
                PollKind.Regular(
                    allowsMultipleAnswers = poll.allowsMultipleAnswers,
                    allowsRevoting = reg != null && poll.allowsRevoting,
                )
            }
        }
        return PostContent.Poll(
            id = poll.id,
            question = mapFormattedText(poll.question),
            options = ordered.toPersistentList(),
            totalVotes = poll.totalVoterCount,
            isAnonymous = poll.isAnonymous,
            isClosed = poll.isClosed,
            kind = kind,
            closeDate = poll.closeDate,
            openPeriod = poll.openPeriod,
            hasVoted = ordered.any { it.isChosen },
            description = mapFormattedText(msg.description),
            headerMedia = pollMediaThumb(msg.media),
            canAddOption = msg.canAddOption,
            recentVoterCount = poll.recentVoterIds?.size ?: 0,
        )
    }

    /**
     * Extract a static [TdMedia] preview from a TDLib poll-attached media payload. Only photo /
     * video / animation / sticker carry a usable still-image thumbnail; venue / location have
     * no thumb in TDLib's payload (they ship coordinates only) so we skip them. Returns null
     * for null input or unsupported variants — caller treats null as "no inline preview, fall
     * back to plain layout".
     */
    private fun pollMediaThumb(media: TdApi.MessageContent?): TdMedia? = when (media) {
        is TdApi.MessagePhoto -> media.photo.toMedia(PHOTO_TARGET_INLINE_PX)
        is TdApi.MessageVideo -> media.video.toThumbMedia()
        is TdApi.MessageAnimation -> media.animation.toThumbMedia()
        is TdApi.MessageSticker -> media.sticker.thumbnail?.toMedia()
        else -> null
    }
}

/**
 * Display-tier targets for [TdApi.Photo.toMedia]. Telegram's server-side photo
 * pyramid ships standard variants per `tdlib/td` photo-types: `m` (~320 px) for
 * thumbnails, `x` (~800 px), `y` (~1280 px) for inline message bodies, `w`
 * (~2560 px) for the full-screen viewer. Asking for the smallest variant whose
 * longer side ≥ target replicates Telegram-Android's
 * `chooseClosestPhotoSizeWithSide(sizes, side)` selection — the inline path
 * never burns the bytes / decode CPU of `w` to paint a card capped at the
 * device width.
 *
 * Decoupling tiers from "always pick largest" is the difference between a
 * 200-channel feed pulling 2-3 MB JPEGs per card and the same feed pulling
 * 300-600 KB JPEGs that already exceed the screen's pixel grid. The
 * fullscreen viewer still gets the canonical `w` variant — see
 * [FullScreenMediaViewer]'s stacked progressive-enhancement path which keeps
 * the inline variant on screen while the fullscreen file downloads.
 */
internal const val PHOTO_TARGET_PREVIEW_PX = 320
internal const val PHOTO_TARGET_INLINE_PX = 1280
internal const val PHOTO_TARGET_FULLSCREEN_PX = Int.MAX_VALUE

/**
 * Pick the smallest [TdApi.PhotoSize] whose longer side ≥ [targetMaxSidePx],
 * falling back to the largest available when no variant meets the target.
 * Returns null only when [TdApi.Photo.sizes] is empty (the API contract says
 * it never is, but we treat it as a defensive null so callers can choose to
 * skip the post entirely rather than dereference). Matches Telegram-Android's
 * `chooseClosestPhotoSizeWithSide` semantics.
 */
private fun TdApi.Photo.pickSize(targetMaxSidePx: Int): TdApi.PhotoSize? {
    val list = sizes
    if (list.isEmpty()) return null
    var bestAtOrAbove: TdApi.PhotoSize? = null
    var largest: TdApi.PhotoSize = list[0]
    for (s in list) {
        val side = maxOf(s.width, s.height)
        if (side >= targetMaxSidePx) {
            val curBest = bestAtOrAbove
            if (curBest == null || maxOf(s.width, s.height) < maxOf(curBest.width, curBest.height)) {
                bestAtOrAbove = s
            }
        }
        if (maxOf(s.width, s.height) > maxOf(largest.width, largest.height)) {
            largest = s
        }
    }
    return bestAtOrAbove ?: largest
}

/**
 * Map [TdApi.Photo] to a [TdMedia] for the requested display tier — see
 * [PHOTO_TARGET_PREVIEW_PX] / [PHOTO_TARGET_INLINE_PX] / [PHOTO_TARGET_FULLSCREEN_PX].
 *
 * The minithumb is the same regardless of tier (~150 B inline JPEG, used as
 * the soft placeholder); the picked variant only affects the fileId we hand
 * to [MediaCache] and the dimensions that drive aspect-ratio layout.
 */
internal fun TdApi.Photo.toMedia(targetMaxSidePx: Int = PHOTO_TARGET_INLINE_PX): TdMedia {
    // Defensive degradation when TDLib hands us a Photo with an empty sizes
    // pyramid. The TdApi javadoc says the array is "Available variants of the
    // photo" — never explicitly nullable, but in practice the daemon has been
    // observed to ship empty arrays for partially-deserialised messages
    // arriving mid-DC migration and for some payment-receipt thumbs that
    // strip the pyramid before forwarding. The original "maxByOrNull(...) ?:
    // sizes.first()" shape would crash with IndexOutOfBoundsException on
    // those — surfacing the bug as a feed scroll that hard-crashes the app.
    // Returning a TdMedia with fileId=null keeps the renderer on the
    // minithumb-only path (the same shape we use for forwarded GIFs without
    // a server-side thumb), so the post still surfaces — just without the
    // full-resolution variant the message never carried.
    val pick = pickSize(targetMaxSidePx)
    return TdMedia(
        fileId = pick?.photo?.id,
        width = pick?.width ?: 0,
        height = pick?.height ?: 0,
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
 * Map a [TdApi.ChatPhoto] (the full payload TDLib delivers for a user/channel
 * profile photo) to a [TdMedia]. Used by [WebPreview] mapping for chat / user
 * / boost / video-chat link kinds — where the link's "thumbnail" is actually
 * the target chat's avatar.
 *
 * ChatPhoto carries the same `sizes: PhotoSize[]` pyramid as the regular
 * [TdApi.Photo], so the same nearest-target picker applies. The default
 * target ([PHOTO_TARGET_PREVIEW_PX]) lines up with what the preview card
 * paints in the 64-96 dp leading slot — full-screen avatar viewing isn't
 * something link previews ever do.
 */
internal fun TdApi.ChatPhoto.toMedia(targetMaxSidePx: Int = PHOTO_TARGET_PREVIEW_PX): TdMedia {
    val list = sizes
    val pick = list?.let { array ->
        if (array.isEmpty()) return@let null
        var best: TdApi.PhotoSize? = null
        var largest: TdApi.PhotoSize = array[0]
        for (s in array) {
            val side = maxOf(s.width, s.height)
            if (side >= targetMaxSidePx) {
                val curBest = best
                if (curBest == null || maxOf(s.width, s.height) < maxOf(curBest.width, curBest.height)) {
                    best = s
                }
            }
            if (maxOf(s.width, s.height) > maxOf(largest.width, largest.height)) {
                largest = s
            }
        }
        best ?: largest
    }
    return TdMedia(
        fileId = pick?.photo?.id,
        width = pick?.width ?: 0,
        height = pick?.height ?: 0,
        minithumbBytes = minithumbnail?.data,
    )
}

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

