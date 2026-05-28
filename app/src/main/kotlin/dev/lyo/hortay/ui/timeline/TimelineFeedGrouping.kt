@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost

/**
 * Hard cap on inline-autoplay video duration we're willing to *speculatively* prefetch the
 * playback file for. Telegram's own autoplay threshold is 60 s, but at home-DC bitrates a
 * 60 s clip is ~10 MB — too much to gamble on a post the user may never scroll to. 30 s
 * keeps speculative cost ≤ ~5 MB per pre-warmed video, which on healthy Wi-Fi is sub-second
 * and on cellular is still tolerable. Longer autoplay clips fall back to the on-mount
 * download path; the user will see the standard loading overlay if needed.
 */
private const val INLINE_PREFETCH_MAX_DURATION_SEC = 30

/**
 * The fileIds whose **poster / preview** should be eagerly downloaded when this content is
 * about to enter viewport. Intentionally excludes playback files (full videos, audio,
 * documents) — those are too big for speculative download, and the user's tap is the right
 * trigger for them. The poster is what TdMediaImage paints behind the play badge / progress
 * overlay, so warming it is what makes "scrolled into view" feel instant.
 */
internal fun PostContent.posterFileIds(): List<Int> = buildList {
    when (val content = this@posterFileIds) {
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Photo -> item.media.fileId?.let(::add)
                is AlbumItem.Video -> item.media.fileId?.let(::add)
                is AlbumItem.Animation -> item.media.fileId?.let(::add)
            }
        }
        is PostContent.Video -> content.media.fileId?.let(::add)
        is PostContent.Animation -> content.media.fileId?.let(::add)
        is PostContent.Document -> content.thumb?.fileId?.let(::add)
        is PostContent.Sticker -> {
            // Stickers are tiny (<100 KB) — pulling both thumb and the playback file
            // up-front means the inline animation starts the moment the post settles,
            // without the placeholder→media swap.
            content.thumb?.fileId?.let(::add)
            content.media.fileId?.let(::add)
        }
        is PostContent.AnimatedEmoji -> {
            content.thumb?.fileId?.let(::add)
            content.sticker?.fileId?.let(::add)
        }
        is PostContent.VideoNote -> content.thumb?.fileId?.let(::add)
        // Video-note playback fileIds intentionally NOT added here — they are warmed
        // via [playbackFileIds] below alongside the autoplay-prewarm pass, where the
        // duration gate matches the actual autoplay eligibility.
        // Text/Audio/VoiceNote/Poll/Location/Contact/Dice/Checklist/Service/Expired/
        // Unsupported — no still preview to warm.
        else -> Unit
    }
}

/**
 * Playback file ids worth pre-warming for inline auto-play (short videos, GIF animations).
 * Honours the same spoiler/secret guards as the renderer — we never prefetch a file the
 * user hasn't explicitly opted into seeing yet, even speculatively. Returns the empty list
 * for content types that are *not* inline-played in the feed (long videos, photos, audio).
 */
internal fun PostContent.playbackFileIds(): List<Int> = buildList {
    when (val content = this@playbackFileIds) {
        is PostContent.Video -> {
            if (!content.hasSpoiler && !content.isSecret &&
                content.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
            ) {
                add(content.playbackFileId)
            }
        }
        is PostContent.Animation -> {
            if (!content.hasSpoiler && !content.isSecret) {
                add(content.playbackFileId)
            }
        }
        is PostContent.VideoNote -> {
            // Round video messages are Telegram-capped at 60 s of square 240/384 source,
            // so the typical playback file is ≤ 5 MB — well within the speculative budget
            // [INLINE_PREFETCH_MAX_DURATION_SEC] guards for long-form video. Always prewarm.
            content.video?.fileId?.let(::add)
        }
        is PostContent.PhotoAlbum -> content.items.forEach { item ->
            when (item) {
                is AlbumItem.Video -> {
                    if (!item.hasSpoiler && !item.isSecret &&
                        item.durationSec in 1..INLINE_PREFETCH_MAX_DURATION_SEC
                    ) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Animation -> {
                    if (!item.hasSpoiler && !item.isSecret) {
                        add(item.playbackFileId)
                    }
                }
                is AlbumItem.Photo -> Unit
            }
        }
        else -> Unit
    }
}

/**
 * One slot in a Compose `LazyColumn` of posts (feed, channel, or search results). A feed
 * item is a 1:1 wrapper around a [TimelinePost] — there is intentionally NO Single/Thread
 * sealed split. Reply continuity is conveyed by the inline reply-quote block (`ReplyBlock`)
 * on the reply's card, NOT by collapsing parent + reply into a stacked Thread row.
 *
 * Why "one post = one row" is load-bearing for scroll stability:
 *
 *   The previous design ran a `groupReplies` pass that promoted same-channel parent ↔
 *   reply pairs into a stacked Thread row keyed on `reply.id`. That reshape was the
 *   dominant cause of two distinct "scroll jumps to the start" reports:
 *
 *     1. Returning to the feed from a channel: `loadChannelHistory(A)` writes channel A's
 *        80-message slice into `_posts`. If the user's anchor row was a standalone parent
 *        P of channel A and the backfilled batch carried its reply R, grouping promoted
 *        `Single(P, key=P.id)` into `Thread(P, R, key=R.id)` — the anchor row's key
 *        disappeared from the new list, `LazyColumn`'s `firstVisibleItemKey` lookup
 *        missed, scroll fell back to the saved `firstVisibleItemIndex`, and the integer
 *        index pointed at a freshly-inserted channel-A row — exactly the user-reported
 *        "feed throws me onto an old post of that channel" symptom.
 *
 *     2. Scrolling inside a channel: a fresh same-channel reply arriving via
 *        `UpdateNewMessage` (or `loadOlder` paginating older history that included a
 *        chain) would re-run grouping; the visible parent's key would flip the same way
 *        and the channel's own LazyColumn would jump.
 *
 *   The cleanest fix is to remove the reshape entirely. There is no Thread variant, no
 *   `groupReplies` pass, no `THREAD_FRESH_WINDOW_MS` / "consecutive" heuristic — every
 *   row is keyed off its single backing [TimelinePost] for the lifetime of that post,
 *   regardless of what siblings appear around it later. The threaded VISUAL it replaced
 *   was a UX-nice-to-have; reply continuity is preserved by the inline reply-quote card.
 *
 * **Album anchor flip** invariant on [key]: album cards key on `(chatId, mediaAlbumId)`,
 * NOT on `post.id`. When the cold-start harvest ingests `Chat.lastMessage` and the
 * surround-fetch in `coalesceAlbumFragments` fails (FLOOD_WAIT, transient network,
 * message aged out of the local store), the feed renders a 1-fragment card whose
 * `post.id` is `chat.lastMessage.id` — typically the *highest* album member id (TDLib
 * stamps `lastMessage` as the most recent message id, and album members are sent
 * sequentially so the last sibling has the highest id). When the user later opens that
 * channel, `loadChannelHistory` returns the full album and `foldRawIntoCurrent` /
 * `PostFilterStrategy.mergeAlbumMembers` rebuild the card with `anchor.id` = the
 * *lowest* member id (intentional; the lowest id is the only carrier of `replyInfo` /
 * `reactions` per tdlib/td#2312, and it's stable across interaction-info updates). With
 * a `post.id`-based key the row's identity would flip on that rebuild — `LazyColumn`
 * couldn't anchor by the saved `firstVisibleItemKey`, fell back to the integer
 * `firstVisibleItemIndex`, and the user saw the feed "throw them onto" a different post.
 * Keying albums on the TDLib-assigned `mediaAlbumId` makes both renderings of the same
 * conceptual album share a row identity, so the LazyColumn scroll-anchor survives the
 * rebuild. `mediaAlbumId` is per-session but `LazyListState.Saver` only persists raw
 * index + offset across process death, so we never need it to be globally unique — only
 * stable for the duration of one app process, which it is.
 */
/**
 * One row of the merged feed. Two variants:
 *
 * - [Post] — a regular post (or album). Carries [post] and uses [TimelinePost]'s
 *   chatId/mediaAlbumId for a stable key (see [feedItemKey]).
 * - [Boundary] — the "Нові пости" / "New posts" divider rendered between the
 *   read-history prefix and the unread queue. Inserted at most once per feed
 *   snapshot by `withBoundary` (added in a later task) using the snapshot's
 *   `epoch` so its key stays constant across per-card dwell-acks but advances
 *   on PTR / drill-out-in / FeedOrder switch (the canonical "session anchor"
 *   Telegram-Android uses).
 *
 * Implemented as a sealed interface (not `sealed class`) so [Post] and [Boundary]
 * stay simple Kotlin `@Immutable` data classes — Compose stability is preserved
 * end-to-end. The compatibility shim [posts] returns an empty list for
 * [Boundary], so iterators that fan visible rows into posts skip boundaries
 * naturally.
 */
@Immutable
sealed interface FeedItem {
    val key: String

    @Immutable
    data class Post(val post: TimelinePost) : FeedItem {
        override val key: String get() = feedItemKey(post)
    }

    @Immutable
    data class Boundary(val epoch: Long) : FeedItem {
        override val key: String get() = "boundary_$epoch"
    }
}

/**
 * Stable per-row key for a [TimelinePost]. Album posts key on `(chatId, mediaAlbumId)`
 * so a 1-fragment → full-album rebuild (anchor.id flip) keeps the same row identity;
 * standalones key on `post.id`. See [FeedItem] for the full rationale.
 */
private fun feedItemKey(post: TimelinePost): String =
    if (post.mediaAlbumId != 0L) "album_${post.chatId}_${post.mediaAlbumId}"
    else "post_${post.chatId}_${post.id}"

/**
 * Compatibility shim for downstream call sites that derive a [TimelinePost] from a feed
 * row. After the Thread variant was removed there is always at most one post per item
 * (zero for [FeedItem.Boundary]); call sites that iterate visible-row → post lists
 * still read better as `item.posts().first()` than `item.post`.
 */
internal fun FeedItem.posts(): List<TimelinePost> = when (this) {
    is FeedItem.Post -> listOf(post)
    is FeedItem.Boundary -> emptyList()
}

/**
 * Compact subscriber count formatter — Telegram convention. 12 345 → "12.3K", 1 050 000
 * → "1.1M". Round numbers drop the decimal so the label reads as "12K" rather than "12.0K".
 */
internal fun formatSubscribers(count: Int): String {
    fun compact(value: Double, suffix: String): String {
        val rounded = ((value * 10).toLong()) / 10.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}$suffix"
        else "%.1f%s".format(rounded, suffix)
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count / 1_000.0, "K")
        else -> compact(count / 1_000_000.0, "M")
    }
}
