package dev.lyo.telread.data

import androidx.compose.runtime.Immutable

/**
 * Rich content model decoupled from TdApi.* types. Mirrors the subset of Telegram message
 * content rendered in the feed.
 *
 * Every `data class` below is annotated [@Immutable]. Compose's stability inference looks at
 * field types, and a few of ours (ByteArray, List<…>) make Compose mark the whole subtree
 * unstable by default — defeating skippable composition for PostCard. The annotation is a
 * promise that instances are produced by `copy()` and never mutated in place, which holds
 * across our codebase: TDLib updates flow through PostsRepository's `_posts.update {}` which
 * always allocates fresh instances.
 */
@Immutable
sealed interface PostContent {

    /** Plain text caption (used for fallback in subtitles); rich version lives on each subtype. */
    val captionPlain: String get() = ""

    @Immutable
    data class Text(
        val formatted: FormattedText,
        val webPreview: WebPreview? = null,
    ) : PostContent {
        override val captionPlain: String get() = formatted.text
    }

    @Immutable
    data class PhotoAlbum(
        val items: List<AlbumItem>,
        val caption: FormattedText,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    @Immutable
    data class Video(
        val media: TdMedia,
        val playbackFileId: Int,
        val caption: FormattedText,
        val durationSec: Int,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    @Immutable
    data class Animation(
        val media: TdMedia,
        val playbackFileId: Int,
        val caption: FormattedText,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    @Immutable
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

    @Immutable
    data class Audio(
        val fileId: Int?,
        val title: String,
        val performer: String,
        val durationSec: Int,
    ) : PostContent

    @Immutable
    data class VoiceNote(
        val fileId: Int?,
        val durationSec: Int,
        val waveform: ByteArray?,
    ) : PostContent {
        override fun equals(other: Any?): Boolean = other is VoiceNote && other.fileId == fileId
        override fun hashCode(): Int = fileId.hashCode()
    }

    @Immutable
    data class VideoNote(
        val thumb: TdMedia?,
        val durationSec: Int,
    ) : PostContent

    @Immutable
    data class Sticker(
        val media: TdMedia,
        val emoji: String,
        val format: StickerFormat,
    ) : PostContent

    @Immutable
    data class Poll(
        val question: String,
        val options: List<PollOption>,
        val totalVotes: Int,
        val isAnonymous: Boolean,
        val isClosed: Boolean,
    ) : PostContent

    @Immutable
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val title: String?,
        val address: String?,
    ) : PostContent

    @Immutable
    data class Contact(
        val name: String,
        val phone: String,
    ) : PostContent

    @Immutable
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
    @Immutable
    data class AnimatedEmoji(
        val emoji: String,
        val sticker: TdMedia?,
        val format: StickerFormat,
    ) : PostContent

    /** Telegram's 2025 Tasks/checklist content — title plus a list of done/undone tasks. */
    @Immutable
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
    @Immutable
    data class ExpiredMedia(val kind: ExpiredKind) : PostContent

    /**
     * Service / system event we want to surface in some contexts (e.g. discussion threads
     * rendering "📌 pinned a message" or "🚀 boosted the channel"). Still filtered out of
     * the channel feed by [PostFilterStrategy] — service noise doesn't belong there.
     */
    @Immutable
    data class Service(val event: ServiceEvent) : PostContent

    /** Anything we deliberately don't render (sponsored, restricted, payment receipts). */
    @Immutable
    data class Unsupported(
        val description: String,
    ) : PostContent
}

/** Single line in a Telegram checklist. */
@Immutable
data class ChecklistItem(
    val text: FormattedText,
    val isDone: Boolean,
)

enum class ExpiredKind { Photo, Video, VideoNote, VoiceNote }

/** Service / system event payloads — symbolic so the UI picks both icon and label. */
sealed interface ServiceEvent {
    @Immutable
    data class PinnedMessage(val pinnedMessageId: Long) : ServiceEvent
    @Immutable
    data class ChannelBoosted(val boostCount: Int) : ServiceEvent
    data object GiveawayStarted : ServiceEvent
    data object ScreenshotTaken : ServiceEvent
    @Immutable
    data class VideoChatStarted(val groupCallId: Int) : ServiceEvent
    data object VideoChatEnded : ServiceEvent
    @Immutable
    data class GroupCall(val isVideo: Boolean) : ServiceEvent
    data object Other : ServiceEvent
}

/** A single item inside a media album — either a photo, video or animation. */
sealed interface AlbumItem {
    val media: TdMedia

    @Immutable
    data class Photo(override val media: TdMedia) : AlbumItem

    @Immutable
    data class Video(
        override val media: TdMedia,
        val durationSec: Int,
        val playbackFileId: Int,
    ) : AlbumItem

    @Immutable
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
@Immutable
data class TdMedia(
    val fileId: Int?,
    val width: Int,
    val height: Int,
    val minithumbBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is TdMedia && other.fileId == fileId
    override fun hashCode(): Int = fileId ?: 0
}

@Immutable
data class WebPreview(
    val url: String,
    val siteName: String,
    val title: String,
    val description: String,
    val image: TdMedia?,
)

@Immutable
data class PollOption(
    val text: String,
    val voterCount: Int,
    val percent: Int,
)

enum class StickerFormat { Webp, Tgs, Webm }

/** Metadata about who originally sent a forwarded post. */
sealed interface ForwardOrigin {
    @Immutable
    data class User(val userName: String) : ForwardOrigin
    @Immutable
    data class Channel(val channelName: String, val authorSignature: String?) : ForwardOrigin
    @Immutable
    data class HiddenUser(val senderName: String) : ForwardOrigin
    @Immutable
    data class Chat(val chatName: String, val authorSignature: String?) : ForwardOrigin
}

/** Reply / quote preview shown above the post body. */
@Immutable
data class ReplyPreview(
    val authorName: String,
    val excerpt: String,
    val isQuote: Boolean,
)

/** Single reaction bucket: an emoji and how many times it was used. */
@Immutable
data class ReactionItem(val emoji: String, val count: Int)

/** Aggregated reaction summary — full per-emoji breakdown plus total. */
@Immutable
data class Reactions(val totalCount: Int, val items: List<ReactionItem>)
