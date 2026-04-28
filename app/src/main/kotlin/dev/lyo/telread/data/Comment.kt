package dev.lyo.telread.data

/**
 * Single comment in a channel post's discussion thread, with chain metadata used for
 * Reddit/Twitter-style indented rendering.
 */
data class Comment(
    val id: Long,
    val parentId: Long?,
    val chatId: Long,
    val authorName: String,
    val avatarFileId: Int?,
    val text: FormattedText,
    val date: Long,
    val reactions: Reactions,
)

/** Flattened tree row produced by [CommentsRepository] for direct LazyColumn consumption. */
data class CommentRow(
    val comment: Comment,
    /** Indentation level. Capped so deeply nested chains stay readable on phones. */
    val depth: Int,
    /** True if this row is the last sibling in its parent's reply list — drives connector lines. */
    val isLastSibling: Boolean,
)
