package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.rich.RichDocument
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
        /** Web-mode CDN URL of the playback stream. See [AlbumItem.Video.remoteVideoUrl]. */
        val remoteVideoUrl: String? = null,
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
        /** Web-mode CDN URL of the playback stream. */
        val remoteVideoUrl: String? = null,
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
        // Prefer the user-authored caption, fall back to filename so the
        // post-action sheet's Copy / Share excerpt is never empty for files
        // whose poster didn't write a caption.
        override val captionPlain: String
            get() = caption.text.takeUnless { it.isBlank() } ?: fileName
    }

    @Immutable
    data class Audio(
        val fileId: Int?,
        val title: String,
        val performer: String,
        val durationSec: Int,
    ) : PostContent {
        // Surface track title (and performer when present) to the action sheet
        // so Copy / Share have something to copy. The card already renders the
        // same fields above the duration line, so the exposed text matches
        // what's on screen.
        override val captionPlain: String
            get() = listOfNotNull(
                title.takeUnless { it.isBlank() },
                performer.takeUnless { it.isBlank() },
            ).joinToString(" — ")
    }

    @Immutable
    data class VoiceNote(
        val fileId: Int?,
        val durationSec: Int,
        val waveform: ByteArray?,
    ) : PostContent {
        override fun equals(other: Any?): Boolean = other is VoiceNote && other.fileId == fileId
        override fun hashCode(): Int = fileId.hashCode()
    }

    /**
     * Telegram "round video message" (`messageVideoNote`). Source is always square
     * (TDLib caps capture at 384×384; default is 240×240), so the renderer clips to a
     * circle. [video] is the playable MP4 — separate TDLib fileId from [thumb] (the
     * poster). [video] may be null on rare hydration paths that only saw the thumbnail;
     * the mapper always populates it when TDLib delivered the message normally.
     */
    @Immutable
    data class VideoNote(
        val thumb: TdMedia?,
        val video: TdMedia?,
        val durationSec: Int,
    ) : PostContent

    @Immutable
    data class Sticker(
        /** Playback file: the .tgs / .webm / .webp itself (id is the sticker payload). */
        val media: TdMedia,
        /**
         * Static WEBP/PNG poster TDLib delivers alongside the sticker. Used as an instant
         * placeholder while [media] downloads, and as the rendered surface for static
         * (non-animated) stickers when [media.fileId] equals it. May be null when TDLib
         * didn't ship a thumb (rare; usually only for cold-start old WEBP stickers).
         */
        val thumb: TdMedia?,
        val emoji: String,
        val format: StickerFormat,
    ) : PostContent

    @Immutable
    data class Poll(
        /** TDLib poll id — stable across [UpdateMessageContent] revisions; used for telemetry only. */
        val id: Long,
        /** [FormattedText] is supported on questions for custom-emoji entities only (TDLib enforces). */
        val question: FormattedText,
        val options: ImmutableList<PollOption>,
        val totalVotes: Int,
        val isAnonymous: Boolean,
        val isClosed: Boolean,
        /** Regular vs Quiz mode + its associated bits (multi-answer, revoting, correct-options, etc.). */
        val kind: PollKind,
        /**
         * Unix timestamp when the poll auto-closes; 0 if open indefinitely. Combined with
         * [openPeriod] the UI renders a countdown chip in the header (Telegram-style).
         */
        val closeDate: Int,
        /** Original poll duration in seconds; 0 if open indefinitely. */
        val openPeriod: Int,
        /**
         * True once the user has cast at least one vote. Mirrors `any { it.isChosen }` but is
         * cached so the UI can branch on "pre-vote choose options view" vs "post-vote results
         * view" without recomputing on every recomposition.
         */
        val hasVoted: Boolean,
        /**
         * Description authored alongside the poll — Telegram Premium "Polls 2.0" feature where
         * the question is short ("Best framework?") and the description carries the prose
         * ("Best for SSR, vote below"). Rendered as a small body block under the question.
         */
        val description: FormattedText = FormattedText("", emptyList()),
        /**
         * Optional photo attached to the poll itself (top banner, above the question). Null if
         * the poll has no media. Only photo medias are surfaced — TDLib also allows
         * animation/video/sticker/venue/location attachments on the new poll schema, but those
         * surfaces tap-through to Telegram (Hortay treats poll media as a header thumbnail).
         */
        val headerMedia: TdMedia? = null,
        /**
         * True iff the author marked this poll as "let viewers propose new options". Hortay is a
         * reader app — we surface the affordance as "Запропонувати варіант" that taps through
         * to the official Telegram client (no AddPollOption RPC sent from this client).
         */
        val canAddOption: Boolean = false,
        /**
         * Count of recent voters returned by TDLib for non-anonymous polls. Used to render a
         * small "X та інші проголосували" label under the total votes; avatars are not loaded
         * to keep the feed light, but the count gives social proof.
         */
        val recentVoterCount: Int = 0,
    ) : PostContent {
        // The question is the only meaningful text — expose it so Copy / Share
        // doesn't return blank for a poll-only post.
        override val captionPlain: String get() = question.text
    }

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
        /** Animated sticker (TGS/WebM/WEBP) for the emoji; null until TDLib resolves it. */
        val sticker: TdMedia?,
        /** Static WEBP/PNG poster delivered alongside the sticker; instant fallback. */
        val thumb: TdMedia?,
        val format: StickerFormat,
    ) : PostContent

    /** Telegram's 2025 Tasks/checklist content — title plus a list of done/undone tasks. */
    @Immutable
    data class Checklist(
        val title: FormattedText,
        val tasks: List<ChecklistItem>,
    ) : PostContent {
        // Title + task lines (with simple "[x]" / "[ ]" markers) so Copy /
        // Share gives the reader the whole list — not just the heading.
        override val captionPlain: String
            get() = buildString {
                if (title.text.isNotBlank()) append(title.text)
                tasks.forEach { task ->
                    if (isNotEmpty()) append('\n')
                    append(if (task.isDone) "[x] " else "[ ] ")
                    append(task.text.text)
                }
            }
    }

    /**
     * Self-destruct (TTL) media that has already expired on the server. Rendered as a
     * disabled placeholder with the original media kind labelled — there is no file to
     * download.
     */
    @Immutable
    data class ExpiredMedia(val kind: ExpiredKind) : PostContent

    /**
     * Telegram Stars paid media post.
     *
     * Two render states are wrapped here so the action sheet, filter, and UI
     * all see one type:
     *
     *   - [items] **non-empty**: the post ships unlocked media (PaidMediaPhoto /
     *     PaidMediaVideo). Render exactly like a [PhotoAlbum] but stamp the
     *     "⭐ [starCount]" chip on the corner so the user knows the post is
     *     paid (otherwise unlocked paid posts read identically to free ones).
     *   - [items] **empty**: everything is locked behind a [PaidMediaPreview]
     *     or [PaidMediaUnsupported]. Render a single lock card with the star
     *     price and route the tap to the original Telegram post (Hortay does
     *     not host the in-app unlock flow).
     *
     * Always carries the original [caption] / [captionAbove] so even locked
     * posts surface their text body — important for channels that sell
     * single-paragraph "insight" posts behind a star paywall.
     */
    @Immutable
    data class PaidMedia(
        val items: List<AlbumItem>,
        val starCount: Long,
        val caption: FormattedText,
        val captionAbove: Boolean = false,
    ) : PostContent {
        override val captionPlain: String get() = caption.text
        val isLocked: Boolean get() = items.isEmpty()
    }

    /**
     * Generic "non-actionable in Hortay, tap to open the source" placeholder.
     *
     * Used for Telegram features Hortay deliberately doesn't reimplement —
     * invoices, giveaways, games, story embeds, premium gift codes — but which
     * are real *content* (not service noise), so the global feed should still
     * surface them with an explicit affordance instead of silently dropping
     * them via the [Unsupported] filter. Tap routes to the original post via
     * [PostInteractions.onOpenClick], same as the file-card rows.
     *
     * Carries:
     *   - [iconSymbol] — `Symbol` registry name for the leading badge.
     *   - [title]      — short user-facing label (e.g. "Giveaway", "Invoice").
     *   - [subtitle]   — optional secondary line (product name, game title).
     */
    @Immutable
    data class OpenInSource(
        val iconSymbol: String,
        val title: String,
        val subtitle: String = "",
    ) : PostContent {
        override val captionPlain: String
            get() = listOfNotNull(
                title.takeUnless { it.isBlank() },
                subtitle.takeUnless { it.isBlank() },
            ).joinToString(" — ")
    }

    /**
     * Service / system event we want to surface in some contexts (e.g. discussion threads
     * rendering "📌 pinned a message" or "🚀 boosted the channel"). Still filtered out of
     * the channel feed by [PostFilterStrategy] — service noise doesn't belong there.
     */
    @Immutable
    data class Service(val event: ServiceEvent) : PostContent

    /**
     * TDLib `messageRichMessage` — an instant-view-style rich document (headings, lists,
     * tables, media blocks, nested inline styling). Mapped to the TDLib-independent
     * [RichDocument] AST by `RichMessageMapper`. [plainText] is the projector output cached
     * at map time so search / copy / preview surfaces don't re-walk the tree.
     */
    @Immutable
    data class RichMessage(
        val document: RichDocument,
        val plainText: String,
    ) : PostContent {
        override val captionPlain: String get() = plainText
    }

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
        /**
         * Higher-resolution variant for the [FullScreenMediaViewer]. Telegram
         * ships a photo size pyramid (`m`/`x`/`y`/`w`); the feed renders
         * [media] (≈`y`, 1280 px) but pinch-zoom + full-screen need the `w`
         * (≈2560 px) variant to stay sharp. Defaults to [media] for
         * compatibility with paths that don't have access to the full
         * pyramid (paid media, web-mode posts) — those cases simply zoom
         * the same fileId the feed uses, which is the existing behaviour.
         *
         * The viewer's progressive-enhancement path stacks [media] (already
         * Ready in [MediaCache] from feed rendering) under [fullscreen], so
         * the user sees the inline variant immediately on open and the `w`
         * variant crossfades over once it lands — no minithumb-blur stage
         * on full-screen open.
         */
        val fullscreen: TdMedia = media,
    ) : AlbumItem

    @Immutable
    data class Video(
        override val media: TdMedia,
        val durationSec: Int,
        val playbackFileId: Int,
        val qualities: VideoQualities,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
        /**
         * Web (anonymous) mode: direct CDN URL of the video stream. TDLib
         * mode leaves this null and uses [playbackFileId] via [MediaCache].
         * Distinct from [media.remoteUrl] which carries the static poster
         * for [TdMediaImage] rendering — playing the poster URL as video
         * would (and did) just hand ExoPlayer a JPEG.
         */
        val remoteVideoUrl: String? = null,
    ) : AlbumItem

    @Immutable
    data class Animation(
        override val media: TdMedia,
        val playbackFileId: Int,
        val hasSpoiler: Boolean = false,
        val isSecret: Boolean = false,
        /** Web-mode CDN URL of the playback stream. See [Video.remoteVideoUrl]. */
        val remoteVideoUrl: String? = null,
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
 * True for a video that has no playable source — neither a TDLib file nor a
 * direct CDN URL. Currently produced only by guest-mode "Media is too big"
 * posts where t.me/s/ strips the `<video src>` element and only ships the
 * poster. The UI uses this flag to show the poster with a soft blur + play
 * badge and route the tap straight to "open in Telegram" instead of
 * launching the in-app gallery (which would spin on the empty URL).
 */
val AlbumItem.isUnplayableVideo: Boolean
    get() = this is AlbumItem.Video &&
        playbackFileId == 0 &&
        remoteVideoUrl.isNullOrBlank()

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
    /**
     * Optional remote-URL fallback rendered by [TdMediaImage] when [fileId] is
     * null. Web (anonymous) mode uses this to flow t.me/s/ CDN URLs through the
     * same media renderer as TDLib file ids — so PostCard / PostBody render
     * web content with no parallel composable tree.
     */
    val remoteUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TdMedia && other.fileId == fileId && other.remoteUrl == remoteUrl
    override fun hashCode(): Int = (fileId ?: 0) * 31 + (remoteUrl?.hashCode() ?: 0)

    /**
     * Aspect ratio (`width / height`) for sizing surfaces that need a seed
     * before the playback decoder reports its real frame size — TDLib delivers
     * `width` / `height` on the original message content (poster geometry),
     * which is the same shape as the video stream in steady state.
     *
     * Returns `0f` when either dimension is missing — the conventional sentinel
     * for "unknown — fill parent until something authoritative reports a size"
     * used across the inline media surfaces (see `TdVideoPlayer.initialAspect`).
     */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
}

/**
 * Web link preview attached to a text message.
 *
 * TDLib's [org.drinkless.tdlib.TdApi.LinkPreview] carries far more than the original
 * mapping ever surfaced — `displayUrl` (the human-friendly host), `author`, and a
 * handful of render-hint flags that decide whether the preview is rendered as a
 * compact Twitter-style row or a Telegram-X-style large media card. Everything is
 * defaulted so existing callsites that only fill the original five fields keep
 * compiling.
 *
 * The [kind] discriminator is what the UI uses to pick a fallback icon when no
 * thumbnail is available — different link kinds (sticker, story, gift, document)
 * each get a distinct glyph so the card still reads as more than "untitled link".
 */
@Immutable
data class WebPreview(
    val url: String,
    /**
     * Human-readable URL (e.g. `youtube.com/watch?v=…` instead of the encoded
     * canonical). Blank when TDLib didn't compute one — UI then falls back to
     * [url] for the small label.
     */
    val displayUrl: String = "",
    val siteName: String,
    val title: String,
    val description: String,
    /** Original author / byline. Distinct from [siteName] (which is the domain). */
    val author: String = "",
    val image: TdMedia?,
    val kind: WebPreviewKind = WebPreviewKind.Article,
    /**
     * True when the preview must render its media full-width above/below the
     * card metadata instead of as a leading 72.dp thumbnail. Matches Telegram's
     * `showLargeMedia` flag, OR'd with `hasLargeMedia` so we render the bigger
     * variant whenever TDLib reports it's available.
     */
    val showLargeMedia: Boolean = false,
    /** When [showLargeMedia] is true, render the image above the description (not below). */
    val showMediaAboveDescription: Boolean = false,
    /**
     * Hint that the preview should be displayed above the message body. Stored
     * for future layout work — the current UI always renders the preview below
     * the text, which is fine for the feed cards (Telegram's own Android client
     * does the same in compact threads).
     */
    val showAboveText: Boolean = false,
)

/**
 * Coarse classification of a [WebPreview] kind. Drives the fallback icon when
 * the preview has no thumbnail, and lets the UI tag chat / story / gift / etc.
 * cards with a glyph that hints at where the link will land. The enum maps
 * roughly 1:1 to TDLib's `LinkPreviewType*` subclasses but coalesces niche
 * variants (chat / direct-messages-chat / video-chat / channel-boost /
 * supergroup-boost) onto a single icon-bearing kind since they all render
 * identically in a small preview card.
 */
enum class WebPreviewKind {
    Article,
    Photo,
    Video,
    Animation,
    Audio,
    Document,
    Album,
    App,
    Chat,
    User,
    Sticker,
    StickerSet,
    Story,
    WebApp,
    Gift,
    Invoice,
    Theme,
    External,
    Unsupported,
}

@Immutable
data class PollOption(
    /**
     * 0-based identifier passed to `SetPollAnswer.optionIds`. Stable across `UpdateMessageContent`
     * revisions — TDLib never reorders the array even when `Poll.optionOrder` tells us to render
     * a different sequence on screen (we apply the order at render time, not here).
     */
    val index: Int,
    /** Option label as [FormattedText] — TDLib accepts custom-emoji entities here. */
    val text: FormattedText,
    val voterCount: Int,
    val percent: Int,
    /** True if the local user picked this option (post-vote results view). */
    val isChosen: Boolean = false,
    /** True while a `SetPollAnswer` RPC is in flight — UI renders a progress shimmer on the row. */
    val isBeingChosen: Boolean = false,
    /**
     * Optional thumbnail rendered alongside the option label — TDLib's "Polls 2.0" extended
     * options support attached media (photo / sticker / venue thumb). Only the static
     * preview is exposed; animation / video options tap through to Telegram.
     */
    val thumb: TdMedia? = null,
)

/** Regular polls (single- or multi-vote) vs quiz polls (single correct answer + explanation). */
@Immutable
sealed interface PollKind {
    @Immutable
    data class Regular(
        val allowsMultipleAnswers: Boolean,
        /**
         * True iff TDLib lets the user retract or change their vote on a regular poll. Quiz
         * polls always reject retraction (TDLib enforces it server-side), so [Quiz] does not
         * carry this bit. UI conditions the "Retract vote" affordance on this.
         */
        val allowsRevoting: Boolean,
    ) : PollKind

    @Immutable
    data class Quiz(
        /** 0-based indices of the correct option(s). Empty until the user has answered. */
        val correctOptionIds: ImmutableList<Int>,
        /** Per-poll explanation shown after the user answers (or taps the lamp). Empty when absent. */
        val explanation: FormattedText,
    ) : PollKind {
        companion object {
            val Unanswered: Quiz = Quiz(
                correctOptionIds = persistentListOf(),
                explanation = FormattedText("", emptyList()),
            )
        }
    }
}

enum class StickerFormat { Webp, Tgs, Webm }

/** Metadata about who originally sent a forwarded post. */
sealed interface ForwardOrigin {
    @Immutable
    data class User(
        val userName: String,
        /** TDLib id of the original user sender. Null only on legacy forwards where the
         *  origin payload lacked the user id (the renderer falls back to the static name). */
        val userId: Long? = null,
    ) : ForwardOrigin
    @Immutable
    data class Channel(
        val channelName: String,
        val authorSignature: String?,
        val sourceChatId: Long,
        val sourceHandle: String?,
        /** Message id of the original post in the source channel. Drives the
         *  forward-chip tap so it lands AT the original post instead of the
         *  channel's newest entry. `TdApi.MessageOriginChannel.message_id` is
         *  non-zero for real channel forwards; the field is nullable only for
         *  the legacy fallback path in [MessageMapper.mapForwardOrigin]. */
        val sourceMessageId: Long?,
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
    /**
     * Chat id of the message being replied to. When equal to the host post's chatId, the
     * reply is a "self-reply" inside the same channel — TimelineScreen merges those into a
     * threaded pair so the parent appears stacked above the reply with a connector line.
     */
    val replyToChatId: Long,
    /** Message id of the message being replied to. Companion to [replyToChatId]. */
    val replyToMessageId: Long,
    /**
     * Optional thumbnail of the parent's media (Twitter-style quote card affordance). Sourced
     * from [TdApi.MessageReplyToMessage.content] — TDLib gives us a snapshot of the parent's
     * content right inside the reply payload, so we don't need a follow-up GetMessage just to
     * decorate the quote card. May be null when the parent has no media or when TDLib didn't
     * attach the content snapshot.
     */
    val mediaThumb: TdMedia? = null,
    /** Coarse parent media kind used to pick an icon when there's no thumbnail. */
    val mediaKind: ReplyMediaKind = ReplyMediaKind.None,
)

/**
 * Coarse classification of the parent's media. Drives the icon shown in the quote card when
 * no thumbnail is available (audio / voice / poll have no still preview, but their kind is
 * still meaningful to the reader). [None] = pure-text or unknown.
 */
@Immutable
enum class ReplyMediaKind { None, Photo, Video, Animation, Document, Audio, VoiceNote, VideoNote, Sticker, Poll }

/**
 * Telegram verification mark on a sender (channel or user). Mutually exclusive set: a chat
 * is at most one of [Verified] (blue check), [Scam] (red badge) or [Fake] (yellow badge).
 */
enum class SenderVerification { Verified, Scam, Fake }

/**
 * Reaction "kind" — Telegram supports three:
 *   - unicode emoji (any user),
 *   - custom-emoji sticker (Premium / channel boost),
 *   - Telegram Stars paid reaction (since 2024: each tap costs the sender ⭐;
 *     channels with monetisation enabled aggregate the donations).
 *
 * Modeled as a sealed type so the chip renderer can pick a glyph, a sticker
 * or the star pill, and so the toggle action keeps every path one-call wide.
 */
@Immutable
sealed interface ReactionKind {
    @Immutable data class Emoji(val text: String) : ReactionKind
    @Immutable data class CustomEmoji(val customEmojiId: Long) : ReactionKind
    /**
     * Paid star reaction. Has no payload (the chip count is the star total —
     * Telegram doesn't split per-sender amounts on the channel side). The
     * toggle action is currently inert; we never send paid reactions from
     * this client, but we render incoming counts so channels with monetised
     * posts don't show empty reaction rows.
     */
    @Immutable data object Paid : ReactionKind
}

/** Stable key for a reaction bucket — used by lazy lists / animations to dedupe across ticks. */
val ReactionKind.stableKey: String
    get() = when (this) {
        is ReactionKind.Emoji -> "e:$text"
        is ReactionKind.CustomEmoji -> "c:$customEmojiId"
        is ReactionKind.Paid -> "p"
    }

/** Single reaction bucket: emoji or custom-emoji sticker plus a usage count. */
@Immutable
data class ReactionItem(val kind: ReactionKind, val count: Int, val isChosen: Boolean = false)

/** Aggregated reaction summary — full per-emoji breakdown plus total. */
@Immutable
data class Reactions(val totalCount: Int, val items: List<ReactionItem>)
