package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.isUnreadAt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The read-state slice of a post that [clusterFeed] hid: enough to tell whether it is
 * still unread under a chat's cursor, without carrying the post itself into every
 * [FeedAnnotation] equality check.
 *
 * @property highestId the post's highest message id (album-aware, like [isUnreadAt]).
 */
@Immutable
data class FoldedPost(
    val chatId: Long,
    val highestId: Long,
    val date: Long,
)

/**
 * What a kept feed row says about the posts folded into it.
 *
 * @property forwardedBy channels that forwarded the same original post; their copies are
 *   hidden and this row stands for all of them.
 * @property forwardCopies read state of those hidden copies (any chat, including a second
 *   copy in the kept row's own chat). Same content as the kept row, so they are acked
 *   together with it and keep the row unread while any of them is.
 * @property seriesHidden newer posts of the same burst folded under this row (0 = none).
 * @property seriesKey key of this row, the series' current kept row.
 * @property seriesMemberKeys keys of EVERY post of the burst, the kept row included. The
 *   "expand" action records all of them, so the series stays open when it later grows at
 *   either end (see [clusterFeed]).
 * @property seriesFolded read state of the folded (hidden) members, newest first. They
 *   have never been on screen, so reading the kept row does NOT ack them — they keep the
 *   row unread until the series is expanded and they are read in place.
 * @property seriesLatest excerpt of the newest folded post, so the freshest update of a
 *   burst (an air-raid all-clear, a correction) is readable without expanding it.
 */
@Immutable
data class FeedAnnotation(
    val forwardedBy: ImmutableList<ChannelBadge> = persistentListOf(),
    val forwardCopies: ImmutableList<FoldedPost> = persistentListOf(),
    val seriesHidden: Int = 0,
    val seriesKey: String? = null,
    val seriesMemberKeys: ImmutableList<String> = persistentListOf(),
    val seriesFolded: ImmutableList<FoldedPost> = persistentListOf(),
    val seriesLatest: String? = null,
)

/**
 * @property posts the rows that stay visible, in input order.
 * @property annotations per visible row key, what that row stands for.
 * @property keptKeyOf hidden post key → key of the VISIBLE row that swallowed it. Lets a
 *   caller holding a key that clustering has since hidden (a scroll anchor captured before
 *   older history joined a burst, an accepted arrival that folded into a series) resolve
 *   it to the row that now stands for it.
 * @property ackCompanions visible row key → the hidden forward copies acked together with
 *   that row (same original, so reading one is reading all).
 */
@Immutable
data class ClusteredFeed(
    val posts: List<TimelinePost>,
    val annotations: ImmutableMap<String, FeedAnnotation>,
    val keptKeyOf: ImmutableMap<String, String> = persistentMapOf(),
    val ackCompanions: ImmutableMap<String, ImmutableList<TimelinePost>> = persistentMapOf(),
) {
    /** [key] itself if it is a visible row, else the visible row that swallowed it. */
    fun resolveKey(key: String): String = keptKeyOf[key] ?: key
}

/** Annotations for the visible rows plus the "expand this series" action, for PostCard. */
@Immutable
class FeedAnnotations(
    val byKey: ImmutableMap<String, FeedAnnotation>,
    val onExpandSeries: (String) -> Unit,
)

val LocalFeedAnnotations = staticCompositionLocalOf<FeedAnnotations?> { null }

/** Gap between neighbouring posts of one channel that still counts as the same burst. */
internal const val SERIES_GAP_MS = 15 * 60 * 1000L

/** Fewest consecutive posts of one channel that fold into a series. */
internal const val SERIES_MIN_POSTS = 4

private const val SERIES_EXCERPT_CHARS = 90

/**
 * Declutters the merged feed without merging rows: copies of one forwarded original and
 * the tail of a posting burst are HIDDEN, and the row that stays carries a
 * [FeedAnnotation] saying what it stands for.
 *
 * Row identity stays one post per row — see [FeedItem] for why reshaping rows (the old
 * Thread grouping) broke scroll anchoring. Clustering only ever REMOVES rows; a row that
 * stays keeps its own key.
 *
 * The kept row is the EARLIEST post of a forward cluster (the original, when the user
 * follows its source) and the OLDEST loaded post of a series, so a new arrival only ever
 * joins an existing cluster behind its kept row. That choice is NOT stable against older
 * history hydrating (channel-push prefetch, first-sign-in backfill, pagination): a still
 * older post can join a burst or a forward cluster and become the new kept row, hiding
 * the previous one. Two things keep that survivable:
 *  - Expansion is tracked by member keys, not by the kept key: a run counts as expanded
 *    when ANY of its members is in [expandedSeries], and the expand action records every
 *    member key ([FeedAnnotation.seriesMemberKeys]). A series therefore stays open when it
 *    grows at either end, and when two runs merge across a newly loaded post.
 *  - [ClusteredFeed.keptKeyOf] maps every hidden key to the visible row that swallowed
 *    it, so a scroll anchor or an arrivals target naming a now-hidden post resolves to
 *    the right row instead of being lost.
 *
 * [posts] is in feed data order (newest first). Comments ([TimelinePost.parentId]) and
 * deleted tombstones are never folded.
 *
 * @param expandedSeries post keys of series the user opened; a run with any member here
 *   stays fully visible.
 */
internal fun clusterFeed(
    posts: List<TimelinePost>,
    expandedSeries: Set<String>,
    badgeFor: (TimelinePost) -> ChannelBadge,
): ClusteredFeed {
    if (posts.size < 2) return ClusteredFeed(posts, persistentMapOf())
    val annotations = HashMap<String, FeedAnnotation>()
    val keptKeyOf = HashMap<String, String>()
    val companions = HashMap<String, ImmutableList<TimelinePost>>()

    // 1. One original, many forwards → keep the earliest copy, list the other channels.
    val byOriginal = posts.filter { it.isFoldable() }.groupBy { it.originalIdentity() }
    // Keyed by row key, not the post itself: a data-class hash would walk every post's
    // whole content tree on each feed rebuild.
    val hidden = HashSet<String>()
    for ((_, copies) in byOriginal) {
        if (copies.size < 2) continue
        val kept = copies.minWith(compareBy<TimelinePost> { it.date }.thenBy { it.id })
        val keptKey = kept.feedKey()
        val others = copies.filter { it !== kept }
        others.forEach {
            val key = it.feedKey()
            hidden += key
            keptKeyOf[key] = keptKey
        }
        val forwarders = others
            .filter { it.chatId != kept.chatId }
            .distinctBy { it.chatId }
            .map(badgeFor)
            .toImmutableList()
        companions[keptKey] = others.toImmutableList()
        annotations[keptKey] = FeedAnnotation(
            forwardedBy = forwarders,
            forwardCopies = others.map { it.folded() }.toImmutableList(),
        )
    }
    val afterForwards = if (hidden.isEmpty()) posts else posts.filter { it.feedKey() !in hidden }

    // 2. A burst of one channel → keep its oldest post, fold the newer ones under it.
    val result = ArrayList<TimelinePost>(afterForwards.size)
    var i = 0
    while (i < afterForwards.size) {
        var end = i
        val head = afterForwards[i]
        if (head.isFoldable()) {
            while (end + 1 < afterForwards.size) {
                val next = afterForwards[end + 1]
                val sameBurst = next.isFoldable() && next.chatId == head.chatId &&
                    afterForwards[end].date - next.date <= SERIES_GAP_MS
                if (!sameBurst) break
                end++
            }
        }
        val run = afterForwards.subList(i, end + 1)
        val memberKeys = if (run.size >= SERIES_MIN_POSTS) run.map { it.feedKey() } else emptyList()
        val expanded = memberKeys.any { it in expandedSeries }
        if (run.size >= SERIES_MIN_POSTS && !expanded) {
            val oldest = run.last()
            val key = memberKeys.last()
            result += oldest
            val folded = run.subList(0, run.size - 1)
            for (k in 0 until memberKeys.size - 1) keptKeyOf[memberKeys[k]] = key
            val previous = annotations[key] ?: FeedAnnotation()
            annotations[key] = previous.copy(
                seriesHidden = folded.size,
                seriesKey = key,
                seriesMemberKeys = memberKeys.toImmutableList(),
                seriesFolded = folded.map { it.folded() }.toImmutableList(),
                seriesLatest = run.first().content.captionPlain
                    .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(SERIES_EXCERPT_CHARS),
            )
        } else {
            result += run
        }
        i = end + 1
    }

    // A forward copy can point at a kept forward row that step 2 then folded into a
    // series: chase the chain to the row that is actually visible. Chains are at most two
    // links deep (forward → series), and step 2 never maps a key onto a hidden one.
    val resolved = HashMap<String, String>(keptKeyOf.size)
    for ((hiddenKey, keptKey) in keptKeyOf) {
        resolved[hiddenKey] = keptKeyOf[keptKey] ?: keptKey
    }
    // Annotations of rows that are no longer visible are dropped with them.
    annotations.keys.retainAll(result.mapTo(HashSet()) { it.feedKey() })
    companions.keys.retainAll(annotations.keys)
    return ClusteredFeed(
        posts = result,
        annotations = annotations.toPersistentMap(),
        keptKeyOf = resolved.toPersistentMap(),
        ackCompanions = companions.toPersistentMap(),
    )
}

/**
 * True while the row standing for [post] has anything unread under [cursorOf]: the post
 * itself, a hidden forward copy, or a folded series member. Each constituent is held to
 * the [minDate] recency floor by its own date (`<= 0` disables the floor).
 */
internal fun rowHasUnread(
    post: TimelinePost,
    annotation: FeedAnnotation?,
    minDate: Long = 0L,
    cursorOf: (Long) -> Long?,
): Boolean = countRowUnread(post, annotation, minDate, stopAtFirst = true, cursorOf = cursorOf) > 0

/**
 * How many unread posts the row for [post] stands for: one for the post and its forward
 * copies together (one piece of content, acked as one), plus one per unread folded series
 * member (each is a distinct post the reader still has to see). Same recency floor as
 * [rowHasUnread].
 */
internal fun rowUnreadCount(
    post: TimelinePost,
    annotation: FeedAnnotation?,
    minDate: Long = 0L,
    cursorOf: (Long) -> Long?,
): Int = countRowUnread(post, annotation, minDate, stopAtFirst = false, cursorOf = cursorOf)

private fun countRowUnread(
    post: TimelinePost,
    annotation: FeedAnnotation?,
    minDate: Long,
    stopAtFirst: Boolean,
    cursorOf: (Long) -> Long?,
): Int {
    fun recent(date: Long) = minDate <= 0L || date >= minDate
    var count = 0
    val selfUnread = recent(post.date) && post.isUnreadAt(cursorOf(post.chatId))
    val copyUnread = !selfUnread && annotation != null && annotation.forwardCopies.any {
        recent(it.date) && it.isUnreadAt(cursorOf(it.chatId))
    }
    if (selfUnread || copyUnread) {
        count++
        if (stopAtFirst) return count
    }
    annotation?.seriesFolded?.forEach {
        if (recent(it.date) && it.isUnreadAt(cursorOf(it.chatId))) {
            count++
            if (stopAtFirst) return count
        }
    }
    return count
}

/** Same rule as [TimelinePost.isUnreadAt]: unread while the cursor sits below it. */
internal fun FoldedPost.isUnreadAt(cursor: Long?): Boolean = cursor != null && highestId > cursor

private fun TimelinePost.folded(): FoldedPost =
    FoldedPost(chatId = chatId, highestId = albumMessageIds.maxOrNull() ?: id, date = date)

private fun TimelinePost.isFoldable(): Boolean = parentId == null && !isDeleted

/**
 * The post a row ultimately shows: the forwarded source for a channel forward with a
 * known message id, otherwise the post itself.
 */
private fun TimelinePost.originalIdentity(): Pair<Long, Long> {
    val origin = forwardOrigin as? ForwardOrigin.Channel
    val sourceId = origin?.sourceMessageId
    return if (origin != null && sourceId != null && sourceId != 0L) origin.sourceChatId to sourceId
    else chatId to id
}

/** Same key as the feed row of this post — see [FeedItem.Post.key]. */
internal fun TimelinePost.feedKey(): String = FeedItem.Post(this).key
