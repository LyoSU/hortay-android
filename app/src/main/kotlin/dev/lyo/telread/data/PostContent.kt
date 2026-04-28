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

    /** Anything we deliberately don't render (service messages, sponsored, restricted). */
    data class Unsupported(
        val description: String,
    ) : PostContent
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
 *   • [fileId]: the asset shown as the thumbnail / preview frame in the feed.
 *   • [minithumbBytes]: instant inline blur (~150B base64 JPEG) before download completes.
 */
data class TdMedia(
    val fileId: Int,
    val width: Int,
    val height: Int,
    val minithumbBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is TdMedia && other.fileId == fileId
    override fun hashCode(): Int = fileId
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

/** Aggregated reaction summary. */
data class Reactions(val totalCount: Int, val topEmojis: List<String>)
