package dev.lyo.telread.data

/**
 * Feed-shaping rules applied right after raw → [TimelinePost] mapping.
 *
 *   1. **Drop unsupported** — service messages, sponsored, restricted; they don't belong
 *      in a Twitter-style read feed.
 *   2. **Merge media albums** — Telegram represents an album as N separate messages with
 *      the same `mediaAlbumId`. Show one card per album. `mediaAlbumId == 0` means a
 *      standalone post; never group those.
 *   3. **Sort** by date, newest first.
 */
object PostFilterStrategy {

    fun apply(raw: List<TimelinePost>): List<TimelinePost> = raw
        .filterNot { it.content is PostContent.Unsupported }
        .let(::mergeAlbums)
        .sortedByDescending { it.date }

    private fun mergeAlbums(posts: List<TimelinePost>): List<TimelinePost> {
        val standalones = posts.filter { it.mediaAlbumId == 0L }
        val grouped = posts
            .filter { it.mediaAlbumId != 0L }
            .groupBy { it.chatId to it.mediaAlbumId }
            .map { (_, members) -> mergeAlbumMembers(members) }
        return standalones + grouped
    }

    private fun mergeAlbumMembers(members: List<TimelinePost>): TimelinePost {
        if (members.size == 1) return members.first()

        // Stable, oldest-first sort so the resulting album reads in posting order.
        val sorted = members.sortedBy { it.date }
        val anchor = sorted.first()

        val items = sorted.flatMap { post ->
            when (val c = post.content) {
                is PostContent.PhotoAlbum -> c.items
                is PostContent.Video -> listOf(AlbumItem.Video(c.media, c.durationSec))
                is PostContent.Animation -> listOf(AlbumItem.Photo(c.media))
                else -> emptyList()
            }
        }
        val caption = sorted
            .firstOrNull { it.content.captionPlain.isNotBlank() }
            ?.content
            ?.toFormattedCaption()
            ?: FormattedText.Empty

        return anchor.copy(
            content = PostContent.PhotoAlbum(items = items, caption = caption),
            views = members.maxOf { it.views },
            commentCount = members.maxOf { it.commentCount },
            reactions = members.map { it.reactions }.maxByOrNull { it.totalCount } ?: Reactions(0, emptyList()),
        )
    }

    private fun PostContent.toFormattedCaption(): FormattedText = when (this) {
        is PostContent.Text -> formatted
        is PostContent.PhotoAlbum -> caption
        is PostContent.Video -> caption
        is PostContent.Animation -> caption
        is PostContent.Document -> caption
        else -> FormattedText.plain(captionPlain)
    }
}
