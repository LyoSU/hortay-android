package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/**
 * UI-facing message model — used for both channel feed posts AND discussion-thread comments.
 * The two are technically the same Telegram message kind with a small set of channel-only
 * extras (views, comment counters, channel handle); separating them into two data classes
 * meant duplicating mappers, caches and renderers. One model + a [parentId] flag covers both.
 *
 * Sender naming is deliberately generic ([senderName]/[senderHandle]) so the same field
 * carries either a channel title/`@username` or a discussion-comment author's name/handle.
 *
 * [@Immutable] is a Compose contract: although [avatarThumb] is a [ByteArray] (which Compose
 * normally infers as unstable, defeating skippable composition), the post is logically
 * immutable — every mutation flows through `copy()` and produces a fresh instance. The
 * annotation lets PostCard and downstream composables skip recomposition when their post
 * argument hasn't changed by reference, which matters in feeds that re-emit the list on
 * every UpdateMessageInteractionInfo.
 */
@Immutable
data class TimelinePost(
    val id: Long,
    val chatId: Long,
    /** Telegram media album id; 0 means standalone post (do not merge). */
    val mediaAlbumId: Long,
    /** Channel title for posts; user/chat display name for comments. */
    val senderName: String,
    /** `@handle` for channel posts; `@username` for comment authors who have one; null otherwise. */
    val senderHandle: String?,
    /** Inline JPEG (~40×40) — instant placeholder, no download. */
    val avatarThumb: ByteArray?,
    /** ProfilePhoto.small.id (160×160). Downloaded at [DownloadPriority.Avatar] — never blocks media. */
    val avatarFileId: Int?,
    val content: PostContent,
    /** Channel post views. Always 0 for comments. */
    val views: Int,
    val date: Long,
    /** Server-side edit timestamp; 0 if never edited. */
    val editDate: Long,
    val forwardOrigin: ForwardOrigin?,
    /** Author signature on a channel post (admin's name). Null for comments. */
    val authorSignature: String?,
    val reply: ReplyPreview?,
    val reactions: Reactions,
    /** Null when the channel has no linked discussion group; 0 when discussion is enabled but empty. Always null for comments. */
    val commentCount: Int?,
    /**
     * All message ids belonging to this album, in posting order. Empty for standalone
     * posts. Telegram attaches the discussion thread to a SINGLE album member (usually
     * the one with the caption); other members report `Message has no thread` from
     * [getMessageThread]. The comments screen probes [getMessageProperties] across this
     * list to find the carrier without guessing.
     */
    val albumMessageIds: List<Long>,
    /**
     * Parent comment id when this row is a discussion-thread reply. Null for top-level
     * comments AND for channel feed posts. Used by [CommentsRepository] to build the
     * threaded tree.
     */
    val parentId: Long? = null,
    /** Mirrors `TdApi.Message.isPinned` — surfaces a small pin badge on the card. */
    val isPinned: Boolean = false,
    /** Telegram verification mark (blue check / scam / fake). Null when none. */
    val verification: SenderVerification? = null,
    /**
     * Channel attribution surfaced as a secondary "in &lt;Channel&gt;" line under the
     * sender row. Populated only when the post's [senderName]/[avatar] has been swapped
     * to a personal author (admin posting under their own identity in the new TDLib
     * "personal-author" channel mode), so the reader still sees which channel the post
     * actually lives in. Null for the standard "channel-as-sender" path — that case
     * needs no secondary attribution.
     */
    val channelContext: ChannelContext? = null,
)

/**
 * Lightweight channel descriptor used for the "in &lt;Channel&gt;" subtitle when the post's
 * displayed sender is a person rather than the channel itself. We deliberately do NOT
 * reuse [TimelinePost] — the subtitle needs only enough to render an avatar pip + name +
 * tap-to-filter target, and TimelinePost would drag interaction data we have no use for.
 */
@Immutable
data class ChannelContext(
    val name: String,
    val handle: String?,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
)
