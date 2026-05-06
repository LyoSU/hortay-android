package dev.lyo.hortay.data

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
        // Drop only the "we can't render this at all" bucket here. Service / expired-media
        // content stays in the data layer because callers want it conditionally — the
        // global timeline hides them as noise, but a single-channel filter view shows
        // them ("📌 pinned a message", "🚀 boosted") because in that context they're the
        // record of what actually happened in the channel. UI applies the contextual
        // filter (see TimelineScreen.visiblePosts).
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

        // Sort by message id, NOT by date. Telegram emits all members of an album
        // with the same `date` (whole-second resolution); a stable sortedBy { date }
        // therefore preserves whatever order the upstream stage handed us — which
        // varies between paths (refresh, snapshot restore, live ingest, pagination)
        // and HashMap groupBy iteration. The anchor (sorted.first()) flipped per
        // refresh, with three knock-on regressions:
        //   • LazyColumn key churn — anchor.id changes between renders, the card
        //     remounts mid-scroll.
        //   • Card disappears from the feed — TimelineViewModel.seenPostIds tracks
        //     the previously-rendered anchor.id, so a flipped anchor falls out of
        //     the seen filter and shows up under the "новi пости" pill instead of
        //     in the visible list ("пропадає все окрім одного").
        //   • commentCount drops to null and reactions stop counting — TDLib's
        //     interactionInfo only fills `replyInfo` / `reactions` on the FIRST
        //     message of an album (tdlib/td#2312: "only the first message in an
        //     album can receive reactions"). The first message is the lowest id;
        //     anchor-ing anything else means the per-member maxOf aggregation
        //     pulls from members that never carry the data.
        // TDLib message ids are monotonic per chat, and an album lives in one chat
        // by construction (groupBy { chatId to mediaAlbumId }), so `sortedBy { id }`
        // is canonical chronological order with no ties.
        val sorted = members.sortedBy { it.id }
        val anchor = sorted.first()

        val items = sorted.flatMap { post ->
            when (val c = post.content) {
                is PostContent.PhotoAlbum -> c.items
                is PostContent.Video -> listOf(
                    AlbumItem.Video(
                        media = c.media,
                        durationSec = c.durationSec,
                        playbackFileId = c.playbackFileId,
                        qualities = c.qualities,
                        hasSpoiler = c.hasSpoiler,
                        isSecret = c.isSecret,
                    ),
                )
                is PostContent.Animation -> listOf(
                    AlbumItem.Animation(
                        media = c.media,
                        playbackFileId = c.playbackFileId,
                        hasSpoiler = c.hasSpoiler,
                        isSecret = c.isSecret,
                    ),
                )
                else -> emptyList()
            }
        }
        val captionCarrier = sorted.firstOrNull { it.content.captionPlain.isNotBlank() }
        val caption = captionCarrier?.content?.toFormattedCaption() ?: FormattedText.Empty
        val captionAbove = captionCarrier?.content?.captionAbove() ?: false

        // Keep the oldest member's id as the card's stable list key (so LazyColumn doesn't
        // remount when interaction-info updates land). The discussion thread, however,
        // may live on any member — we pass all ids to CommentsRepository, which probes
        // GetMessageProperties to find the canonical carrier.
        return anchor.copy(
            content = PostContent.PhotoAlbum(items = items, caption = caption, captionAbove = captionAbove),
            views = members.maxOf { it.views },
            commentCount = members.mapNotNull { it.commentCount }.maxOrNull(),
            reactions = members.map { it.reactions }.maxByOrNull { it.totalCount } ?: Reactions(0, emptyList()),
            albumMessageIds = sorted.map { it.id },
            isPinned = members.any { it.isPinned },
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

    private fun PostContent.captionAbove(): Boolean = when (this) {
        is PostContent.PhotoAlbum -> captionAbove
        is PostContent.Video -> captionAbove
        is PostContent.Animation -> captionAbove
        else -> false
    }
}
