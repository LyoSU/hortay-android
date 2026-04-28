package dev.lyo.telread.data

/**
 * Single comment in a channel post's discussion thread. Twitter-style: replies are
 * resolved into a flat list sorted by date with optional `replyingTo` excerpts so the
 * conversation chain reads top-to-bottom without deep nesting.
 */
data class Comment(
    val id: Long,
    val chatId: Long,
    val authorName: String,
    val avatarFileId: Int?,
    val text: FormattedText,
    val date: Long,
    val replyingToAuthor: String?,
    val replyingToExcerpt: String?,
    val reactions: Reactions,
)
