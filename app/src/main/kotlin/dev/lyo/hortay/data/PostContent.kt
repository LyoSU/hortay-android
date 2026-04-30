package dev.lyo.hortay.data

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
        /** Telegram lets posters render the caption above the media instead of below. */
        val captionAbove: Boolean = false,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    @Immutable
    data class Video(
        val media: TdMedia,
        val playbackFileId: Int,
        val qualities: VideoQualities,
        val caption: FormattedText,
        val durationSec: Int,
        val captionAbove: Boolean = false,
        /** Spoiler-tagged by sender — UI hides until tap. */
        val hasSpoiler: Boolean = false,
        /** Self-destructing / sensitive — same UX as spoiler but stronger phrasing. */
        val isSecret: Boolean = false,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
    }

    @Immutable
    data class Animation(
        val media: TdMedia,
        val playbackFileId: Int,
        val caption: FormattedText,
        val captionAbove: Boolean = false,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
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
    data class Photo(
        override val media: TdMedia,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
    ) : AlbumItem

    @Immutable
    data class Video(
        override val media: TdMedia,
        val durationSec: Int,
        val playbackFileId: Int,
        val qualities: VideoQualities,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
    ) : AlbumItem

    @Immutable
    data class Animation(
        override val media: TdMedia,
        val playbackFileId: Int,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
    ) : AlbumItem
}

/** Convenience flags shared across the three [AlbumItem] subtypes that can be hidden. */
val AlbumItem.hasSpoiler: Boolean
    get() = when (this) {
        is AlbumItem.Photo -> hasSpoiler
        is AlbumItem.Video -> hasSpoiler
        is AlbumItem.Animation -> hasSpoiler
    }

val AlbumItem.isSecret: Boolean
    get() = when (this) {
        is AlbumItem.Photo -> isSecret
        is AlbumItem.Video -> isSecret
        is AlbumItem.Animation -> isSecret
    }

/**
 * One re-encoded variant of a video, addressable as a TDLib file. Telegram-Android
 * displays these in the quality picker (`360p`, `480p`, `720p`, …) — when the user
 * picks one, playback switches to its [fileId]. The [original] is the format the
 * uploader sent; [alternatives] are server-generated re-encodes that ship alongside
 * the message in [org.drinkless.tdlib.TdApi.MessageVideo.alternativeVideos].
 */
@Immutable
data class VideoQuality(
    val fileId: Int,
    val width: Int,
    val height: Int,
    /** Display label like "720p", "1080p", "4K". */
    val label: String,
    val sizeBytes: Long,
)

@Immutable
data class VideoQualities(
    val original: VideoQuality,
    /** Server-generated re-encodes, sorted by descending height (HD first). */
    val alternatives: List<VideoQuality> = emptyList(),
) {
    /** Original first, then alternatives — matches the picker's natural order. */
    val all: List<VideoQuality> get() = listOf(original) + alternatives

    /** Whether the picker should be shown at all. */
    val hasOptions: Boolean get() = alternatives.isNotEmpty()

    /**
     * Picked when the user opens the video without explicit quality choice. Server
     * re-encodes (alternatives) are typically smaller than the original at comparable
     * perceived quality — phone-shot 4K clips bloat the original. Telegram's own
     * default is HLS ABR which we don't implement here; the closest stable static
     * choice is the *highest* alternative (usually 720p, the sweet spot for size and
     * legibility on a phone screen). Falls back to original when no alternatives ship.
     */
    val defaultPick: VideoQuality
        get() = alternatives.maxByOrNull { it.height } ?: original
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
    data class Channel(
        val channelName: String,
        val authorSignature: String?,
        val sourceChatId: Long,
        val sourceHandle: String?,
    ) : ForwardOrigin
    @Immutable
    data class HiddenUser(val senderName: String) : ForwardOrigin
    @Immutable
    data class Chat(
        val chatName: String,
        val authorSignature: String?,
        val sourceChatId: Long,
        val sourceHandle: String?,
    ) : ForwardOrigin
}

/** Reply / quote preview shown above the post body. */
@Immutable
data class ReplyPreview(
    val authorName: String,
    val excerpt: String,
    val isQuote: Boolean,
)

/**
 * Telegram verification mark on a sender (channel or user). Mutually exclusive set: a chat
 * is at most one of [Verified] (blue check), [Scam] (red badge) or [Fake] (yellow badge).
 */
enum class SenderVerification { Verified, Scam, Fake }

/** Single reaction bucket: an emoji and how many times it was used. */
@Immutable
data class ReactionItem(val emoji: String, val count: Int, val isChosen: Boolean = false)

/** Aggregated reaction summary — full per-emoji breakdown plus total. */
@Immutable
data class Reactions(val totalCount: Int, val items: List<ReactionItem>)
