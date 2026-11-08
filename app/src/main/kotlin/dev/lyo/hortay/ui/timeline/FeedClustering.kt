package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap

/**
 * What a kept feed row says about the posts folded into it.
 *
 * @property forwardedBy channels that forwarded the same original post; their copies are
 *   hidden and this row stands for all of them.
 * @property seriesHidden newer posts of the same burst folded under this row (0 = none).
 * @property seriesKey key of this row, used to remember that the user expanded its series.
 * @property seriesLatest excerpt of the newest folded post, so the freshest update of a
 *   burst (an air-raid all-clear, a correction) is readable without expanding it.
 */
@Immutable
data class FeedAnnotation(
    val forwardedBy: ImmutableList<ChannelBadge> = persistentListOf(),
    val seriesHidden: Int = 0,
    val seriesKey: String? = null,
    val seriesLatest: String? = null,
)

@Immutable
data class ClusteredFeed(
    val posts: List<TimelinePost>,
    val annotations: ImmutableMap<String, FeedAnnotation>,
)

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
 * Thread grouping) broke scroll anchoring. A kept row is chosen to be stable as the feed
 * grows: the EARLIEST post of a forward cluster (the original, when the user follows its
 * source) and the OLDEST post of a series, so a new arrival only ever joins an existing
 * cluster behind its kept row instead of replacing it.
 *
 * [posts] is in feed data order (newest first). Comments ([TimelinePost.parentId]) and
 * deleted tombstones are never folded.
 *
 * @param expandedSeries series keys the user opened; their posts stay visible.
 */
internal fun clusterFeed(
    posts: List<TimelinePost>,
    expandedSeries: Set<String>,
    badgeFor: (TimelinePost) -> ChannelBadge,
): ClusteredFeed {
    if (posts.size < 2) return ClusteredFeed(posts, persistentMapOf())
    val annotations = HashMap<String, FeedAnnotation>()

    // 1. One original, many forwards → keep the earliest copy, list the other channels.
    val byOriginal = posts.filter { it.isFoldable() }.groupBy { it.originalIdentity() }
    // Keyed by row key, not the post itself: a data-class hash would walk every post's
    // whole content tree on each feed rebuild.
    val hidden = HashSet<String>()
    for ((_, copies) in byOriginal) {
        if (copies.size < 2) continue
        val kept = copies.minWith(compareBy<TimelinePost> { it.date }.thenBy { it.id })
        val others = copies.filter { it !== kept }
        others.forEach { hidden += it.feedKey() }
        val forwarders = others
            .filter { it.chatId != kept.chatId }
            .distinctBy { it.chatId }
            .map(badgeFor)
            .toImmutableList()
        if (forwarders.isNotEmpty()) {
            annotations[kept.feedKey()] = FeedAnnotation(forwardedBy = forwarders)
        }
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
        val oldest = run.last()
        val key = oldest.feedKey()
        if (run.size >= SERIES_MIN_POSTS && key !in expandedSeries) {
            result += oldest
            val previous = annotations[key] ?: FeedAnnotation()
            annotations[key] = previous.copy(
                seriesHidden = run.size - 1,
                seriesKey = key,
                seriesLatest = run.first().content.captionPlain
                    .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(SERIES_EXCERPT_CHARS),
            )
        } else {
            result += run
        }
        i = end + 1
    }
    return ClusteredFeed(result, annotations.toPersistentMap())
}

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
