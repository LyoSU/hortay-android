package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.MutedWordsFilter
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.AlbumItem

/**
 * Pure decisions behind a guest new-post notification, kept apart from Android so they
 * can be unit-tested: which stored posts a notification is about ([select]), what each
 * line says ([previewOf]) and how a new batch folds into a notification still on screen
 * ([merge]).
 */
object WebNotifySelection {

    /** Lines an expanded notification lists (InboxStyle). */
    const val MAX_LINES = 5

    /** One post as a notification line: its first text line, or what kind of media it is. */
    data class Preview(val seq: Long, val text: String?, val kind: ReplyMediaKind)

    /**
     * @property maxSeq the highest seq among the candidates, shown or not — the next
     *   baseline, so filtered posts are not reconsidered every period. Null without candidates.
     * @property previews the posts the feed would show, newest first. Empty = nothing to notify.
     */
    data class Selection(val maxSeq: Long?, val previews: List<Preview>)

    /**
     * What a channel's notification shows: [count] new posts, [lines] newest first
     * (≤ [MAX_LINES]), the newest of them being [maxSeq] — reading up to it clears it.
     */
    data class Shown(val count: Int, val lines: List<String>, val maxSeq: Long)

    /**
     * The posts above the baseline that would reach the guest feed: none for a channel the
     * reader hid from the feed, and none containing a muted word — the feed's own
     * predicates ([dev.lyo.hortay.data.IgnoredChannelsStore] by synthetic chat id,
     * [MutedWordsFilter.isMuted] on the caption).
     */
    fun select(
        candidates: List<TimelinePost>,
        notifiedSeq: Long,
        channelHidden: Boolean,
        mutedWords: Set<String>,
    ): Selection {
        val fresh = candidates.filter { it.id > notifiedSeq }
        val maxSeq = fresh.maxOfOrNull { it.id }
        if (channelHidden) return Selection(maxSeq, emptyList())
        val previews = fresh
            .asSequence()
            .filterNot { MutedWordsFilter.isMuted(it.content.captionPlain, mutedWords) }
            .sortedByDescending { it.id }
            .map(::previewOf)
            .toList()
        return Selection(maxSeq, previews)
    }

    fun previewOf(post: TimelinePost): Preview {
        val firstLine = post.content.captionPlain
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        return Preview(seq = post.id, text = firstLine, kind = kindOf(post.content))
    }

    /** The label a media-only post is announced with; [ReplyMediaKind.None] = "New post". */
    fun kindOf(content: PostContent): ReplyMediaKind = when (content) {
        is PostContent.PhotoAlbum ->
            if (content.items.isNotEmpty() && content.items.all { it is AlbumItem.Video }) {
                ReplyMediaKind.Video
            } else {
                ReplyMediaKind.Photo
            }
        is PostContent.Video -> ReplyMediaKind.Video
        is PostContent.Animation -> ReplyMediaKind.Animation
        is PostContent.Document, is PostContent.FileAlbum -> ReplyMediaKind.Document
        is PostContent.Audio -> ReplyMediaKind.Audio
        is PostContent.VoiceNote -> ReplyMediaKind.VoiceNote
        is PostContent.VideoNote -> ReplyMediaKind.VideoNote
        is PostContent.Sticker -> ReplyMediaKind.Sticker
        is PostContent.Poll -> ReplyMediaKind.Poll
        else -> ReplyMediaKind.None
    }

    /**
     * Fold [newLines] (newest first, [newCount] posts, the newest being [newMaxSeq]) into the notification still showing
     * for the channel, if any: counts add up and the newest lines win the [MAX_LINES] slots.
     * A notification the reader swiped away is gone, so [previous] is null and it starts over.
     */
    fun merge(previous: Shown?, newLines: List<String>, newCount: Int, newMaxSeq: Long): Shown {
        val lines = (newLines + previous?.lines.orEmpty()).take(MAX_LINES)
        return Shown(
            count = newCount + (previous?.count ?: 0),
            lines = lines,
            maxSeq = maxOf(newMaxSeq, previous?.maxSeq ?: Long.MIN_VALUE),
        )
    }
}
