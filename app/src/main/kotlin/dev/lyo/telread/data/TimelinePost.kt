package dev.lyo.telread.data

/** UI-facing post model. Decouples Compose from TDLib's TdApi types. */
data class TimelinePost(
    val id: Long,
    val chatId: Long,
    /** Telegram media album id; 0 means standalone post (do not merge). */
    val mediaAlbumId: Long,
    val channelTitle: String,
    val channelHandle: String?,
    /** Inline JPEG (~40×40) from ChatPhotoInfo.minithumbnail.data — instant placeholder, no download. */
    val avatarThumb: ByteArray?,
    /** ChatPhotoInfo.small.id (160×160). Downloaded at [DownloadPriority.Avatar] — never blocks media. */
    val avatarFileId: Int?,
    val content: PostContent,
    val views: Int,
    val date: Long,
    /** Server-side edit timestamp; 0 if never edited. */
    val editDate: Long,
    val forwardOrigin: ForwardOrigin?,
    val authorSignature: String?,
    val reply: ReplyPreview?,
    val reactions: Reactions,
    /** Null when the channel has no linked discussion group; 0 when discussion is enabled but empty. */
    val commentCount: Int?,
    /**
     * All message ids belonging to this album, in posting order. Empty for standalone
     * posts. Telegram attaches the discussion thread to a SINGLE album member (usually
     * the one with the caption); other members report `Message has no thread` from
     * [getMessageThread]. The comments screen probes [getMessageProperties] across this
     * list to find the carrier without guessing.
     */
    val albumMessageIds: List<Long>,
)
