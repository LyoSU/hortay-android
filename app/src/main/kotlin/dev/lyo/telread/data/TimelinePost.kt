package dev.lyo.telread.data

/**
 * UI-facing message model — used for both channel feed posts AND discussion-thread comments.
 * The two are technically the same Telegram message kind with a small set of channel-only
 * extras (views, comment counters, channel handle); separating them into two data classes
 * meant duplicating mappers, caches and renderers. One model + a [parentId] flag covers both.
 *
 * Sender naming is deliberately generic ([senderName]/[senderHandle]) so the same field
 * carries either a channel title/`@username` or a discussion-comment author's name/handle.
 */
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
)
