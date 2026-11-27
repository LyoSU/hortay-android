package dev.lyo.hortay.ui.channels

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.subscriptions.FollowedChannel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

// @Immutable required: the ByteArray field defeats Compose's automatic
// stability inference (arrays are mutable references), so without the
// annotation every ChannelRow in a 200-channel LazyColumn re-composes on any
// upstream list mutation — even if the row's own ChannelSummary didn't change.
// The annotation is a contract: ChannelSummary instances are never mutated
// after construction (and [channelSummaries] builds fresh ones every pass,
// so this trivially holds).
@Immutable
internal data class ChannelSummary(
    val chatId: Long,
    val title: String,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val lastPostExcerpt: String,
    /** Newest post's date (ms); 0 for a followed channel with nothing in the slice yet. */
    val lastPostDate: Long,
    /**
     * Highest (album-aware) message id this channel contributed to the loaded slice.
     * Compared against the read cursor to derive the unread dot — same album-aware rule
     * as [TimelinePost.isUnreadAt] (an album stays unread until every member is acked).
     */
    val lastPostId: Long,
    /** Non-null for a channel read without joining: its status in the followed store. */
    val followedStatus: FollowedChannel.Status? = null,
    /** The store's local read cursor; only meaningful with [followedStatus]. */
    val followedReadCursor: Long = 0L,
    /**
     * Album-aware id of every post this channel has in the slice — kept only for followed
     * rows, whose unread count is counted locally (Telegram keeps none for a non-member).
     */
    val postIds: ImmutableList<Long> = persistentListOf(),
) {
    val isFollowed: Boolean get() = followedStatus != null
}

/**
 * One row per channel in [posts], plus a row for every [followed] channel that has no post
 * in the slice yet (just followed, still restoring, or Unavailable) — otherwise a followed
 * channel would be invisible in the Channels tab. Newest post first; post-less rows last.
 */
internal fun channelSummaries(
    posts: List<TimelinePost>,
    followed: Map<Long, FollowedChannel>,
): List<ChannelSummary> {
    val fromPosts = posts
        .groupBy { it.chatId }
        .map { (chatId, list) -> summarize(chatId, list, followed[chatId]) }
    val seen = fromPosts.mapTo(HashSet()) { it.chatId }
    val postless = followed.values
        .filter { it.chatId !in seen }
        .sortedByDescending { it.addedAtMs }
        .map { entry ->
            ChannelSummary(
                chatId = entry.chatId,
                title = entry.title.ifBlank { entry.username?.let { "@$it" }.orEmpty() },
                avatarThumb = null,
                avatarFileId = null,
                lastPostExcerpt = "",
                lastPostDate = 0L,
                lastPostId = 0L,
                followedStatus = entry.status,
                followedReadCursor = entry.readCursor,
            )
        }
    return fromPosts.sortedByDescending { it.lastPostDate } + postless
}

private fun summarize(chatId: Long, list: List<TimelinePost>, followed: FollowedChannel?): ChannelSummary {
    val anchor = list.maxBy { it.date }
    // Personal-author channel mode: each post's senderName / avatar is the admin who
    // wrote it, NOT the channel. We need the channel's own identity here, which lives
    // in [channelContext]. Prefer ANY post in the group whose channelContext is set
    // (or, equivalently, whose own senderName is already the channel) so a channel
    // with multiple posting admins still surfaces as one row with the right name and
    // photo. Falling back to anchor.senderName covers the all-channel-as-sender case
    // where channelContext is null on every post.
    val channelLike = list.firstNotNullOfOrNull { it.channelContext }
    val title = channelLike?.name ?: anchor.senderName
    val thumb = channelLike?.avatarThumb ?: anchor.avatarThumb
    val fileId = channelLike?.avatarFileId ?: anchor.avatarFileId
    // Highest id across every post (and album member) this channel put in the slice.
    // Album-aware so a partially-read album still reads as unread, mirroring isUnreadAt.
    val ids = list.map { p -> p.albumMessageIds.maxOrNull() ?: p.id }
    return ChannelSummary(
        chatId = chatId,
        title = title,
        avatarThumb = thumb,
        avatarFileId = fileId,
        lastPostExcerpt = anchor.content.captionPlain.take(EXCERPT_CHARS),
        lastPostDate = anchor.date,
        lastPostId = ids.max(),
        followedStatus = followed?.status,
        followedReadCursor = followed?.readCursor ?: 0L,
        postIds = if (followed != null) ids.toImmutableList() else persistentListOf(),
    )
}

/**
 * Unread posts of a followed row: posts in the slice above the read position, which is
 * the higher of the app-wide cursor ([cursor], already max-merged with the store) and the
 * store's own one. Counts posts, not messages — an album is one post.
 */
internal fun followedUnreadCount(summary: ChannelSummary, cursor: Long?): Int {
    val readUpTo = maxOf(cursor ?: 0L, summary.followedReadCursor)
    return summary.postIds.count { it > readUpTo }
}

/** Whether a row's subtitle is the "can't update" line instead of the post excerpt. */
internal fun showsUnavailableLine(summary: ChannelSummary): Boolean =
    summary.followedStatus == FollowedChannel.Status.Unavailable

private const val EXCERPT_CHARS = 120
