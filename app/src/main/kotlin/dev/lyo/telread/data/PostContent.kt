package dev.lyo.telread.data

/**
 * Rich content model decoupled from TdApi.* types. Mirrors the subset of Telegram message
 * content rendered in the feed.
 */
sealed interface PostContent {

    /** Plain text caption (used for fallback in subtitles); rich version lives on each subtype. */
    val captionPlain: String get() = ""

    data class Text(
        val formatted: FormattedText,
        val webPreview: WebPreview? = null,
    ) : PostContent {
        override val captionPlain: String get() = formatted.text
    }

    data class PhotoAlbum(
        val items: List<AlbumItem>,
        val caption: FormattedText,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    data class Video(
        val media: TdMedia,
        val playbackFileId: Int,
        val caption: FormattedText,
        val durationSec: Int,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    data class Animation(
        val media: TdMedia,
        val playbackFileId: Int,
        val caption: FormattedText,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    data class Document(
        val fileId: Int?,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val thumb: TdMedia?,
        val caption: FormattedText,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    data class Audio(
        val fileId: Int?,
        val title: String,
        val performer: String,
        val durationSec: Int,
    ) : PostContent

    data class VoiceNote(
        val fileId: Int?,
        val durationSec: Int,
        val waveform: ByteArray?,
    ) : PostContent {
        override fun equals(other: Any?): Boolean = other is VoiceNote && other.fileId == fileId
        override fun hashCode(): Int = fileId.hashCode()
    }

    data class VideoNote(
        val thumb: TdMedia?,
        val durationSec: Int,
    ) : PostContent

    data class Sticker(
        val media: TdMedia,
        val emoji: String,
        val format: StickerFormat,
    ) : PostContent

    data class Poll(
        val question: String,
        val options: List<PollOption>,
        val totalVotes: Int,
        val isAnonymous: Boolean,
        val isClosed: Boolean,
    ) : PostContent

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val title: String?,
        val address: String?,
    ) : PostContent

    data class Contact(
        val name: String,
        val phone: String,
    ) : PostContent

    data class Dice(
        val emoji: String,
        val value: Int,
    ) : PostContent

    /**
     * Single oversized emoji — Telegram renders these specially when a message contains
     * exactly one emoji (with optional animated/custom sticker variant). Common in comments.
     * [sticker] is the lottie/webm/webp variant when the user has a Premium animated set;
     * fallback is just [emoji] rendered at display-large size.
     */
    data class AnimatedEmoji(
        val emoji: String,
        val sticker: TdMedia?,
        val format: StickerFormat,
    ) : PostContent

    /** Telegram's 2025 Tasks/checklist content — title plus a list of done/undone tasks. */
    data class Checklist(
        val title: FormattedText,
        val tasks: List<ChecklistItem>,
    ) : PostContent {
        override val captionPlain: String get() = title.text
    }

    /**
     * Self-destruct (TTL) media that has already expired on the server. Rendered as a
     * disabled placeholder with the original media kind labelled — there is no file to
     * download.
     */
    data class ExpiredMedia(val kind: ExpiredKind) : PostContent

    /**
     * Service / system event we want to surface in some contexts (e.g. discussion threads
     * rendering "📌 pinned a message" or "🚀 boosted the channel"). Still filtered out of
     * the channel feed by [PostFilterStrategy] — service noise doesn't belong there.
     */
    data class Service(val event: ServiceEvent) : PostContent

    /** Anything we deliberately don't render (sponsored, restricted, payment receipts). */
    data class Unsupported(
        val description: String,
    ) : PostContent
}

/** Single line in a Telegram checklist. */
data class ChecklistItem(
    val text: FormattedText,
    val isDone: Boolean,
)

enum class ExpiredKind { Photo, Video, VideoNote, VoiceNote }

/** Service / system event payloads — symbolic so the UI picks both icon and label. */
sealed interface ServiceEvent {
    data class PinnedMessage(val pinnedMessageId: Long) : ServiceEvent
    data class ChannelBoosted(val boostCount: Int) : ServiceEvent
    data object GiveawayStarted : ServiceEvent
    data object ScreenshotTaken : ServiceEvent
    data class VideoChatStarted(val groupCallId: Int) : ServiceEvent
    data object VideoChatEnded : ServiceEvent
    data class GroupCall(val isVideo: Boolean) : ServiceEvent
    data object Other : ServiceEvent
}

/** A single item inside a media album — either a photo, video or animation. */
sealed interface AlbumItem {
    val media: TdMedia

    data class Photo(override val media: TdMedia) : AlbumItem

    data class Video(
        override val media: TdMedia,
        val durationSec: Int,
        val playbackFileId: Int,
    ) : AlbumItem

    data class Animation(
        override val media: TdMedia,
        val playbackFileId: Int,
    ) : AlbumItem
}

/**
 * Reference to a single Telegram file rendered in the UI.
 *
 *   • [fileId]: id of the image asset to download (JPEG/WEBP/PNG). `null` when the message
 *     has no decodable still — typically a GIF/MP4 forwarded without a server-side thumb.
 *     In that case [minithumbBytes] (if present) is the only preview shown.
 *   • [minithumbBytes]: instant inline blur (~150B base64 JPEG) before download completes.
 */
data class TdMedia(
    val fileId: Int?,
    val width: Int,
    val height: Int,
    val minithumbBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is TdMedia && other.fileId == fileId
    override fun hashCode(): Int = fileId ?: 0
}

data class WebPreview(
    val url: String,
    val siteName: String,
    val title: String,
    val description: String,
    val image: TdMedia?,
)

data class PollOption(
    val text: String,
    val voterCount: Int,
    val percent: Int,
)

enum class StickerFormat { Webp, Tgs, Webm }

/** Metadata about who originally sent a forwarded post. */
sealed interface ForwardOrigin {
    data class User(val userName: String) : ForwardOrigin
    data class Channel(val channelName: String, val authorSignature: String?) : ForwardOrigin
    data class HiddenUser(val senderName: String) : ForwardOrigin
    data class Chat(val chatName: String, val authorSignature: String?) : ForwardOrigin
}

/** Reply / quote preview shown above the post body. */
data class ReplyPreview(
    val authorName: String,
    val excerpt: String,
    val isQuote: Boolean,
)

/** Single reaction bucket: an emoji and how many times it was used. */
data class ReactionItem(val emoji: String, val count: Int)

/** Aggregated reaction summary — full per-emoji breakdown plus total. */
data class Reactions(val totalCount: Int, val items: List<ReactionItem>)
