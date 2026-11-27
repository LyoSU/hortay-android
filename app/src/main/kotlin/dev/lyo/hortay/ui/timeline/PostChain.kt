package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TimelinePost

/**
 * How a post row joins the reply thread of the rows drawn right above and below it.
 *
 * Channels often post a run of replies to one of their own posts (or to each other). Each
 * of those cards used to repeat the same reply-quote card, a "staircase" of identical
 * quotes. A row that [joinsAbove] drops its quote instead, and the two cards are linked by
 * a thin connector in the gap between them, so the run reads as one thread. The first post
 * of the run keeps its quote, which stays the way to the parent.
 *
 * Purely visual: the rows stay separate `LazyColumn` items with their own keys. Merging a
 * chain into one row (the old Thread grouping) broke scroll anchoring — see [FeedItem].
 *
 * Directions are VISUAL (screen top / bottom), already resolved for the feed order, so
 * PostCard never has to know about `reverseLayout`.
 *
 * @property joinsAbove the row continues the thread of the row above it: its reply quote is
 *   redundant and the connector runs from its top edge up to that row.
 * @property joinsBelow the row below continues this one's thread: the connector runs from
 *   this row's bottom edge, and the hairline between the two cards is not drawn.
 */
@Immutable
enum class PostChain(val joinsAbove: Boolean, val joinsBelow: Boolean) {
    None(joinsAbove = false, joinsBelow = false),
    Start(joinsAbove = false, joinsBelow = true),
    Middle(joinsAbove = true, joinsBelow = true),
    End(joinsAbove = true, joinsBelow = false),
    ;

    companion object {
        fun of(joinsAbove: Boolean, joinsBelow: Boolean): PostChain = when {
            joinsAbove && joinsBelow -> Middle
            joinsAbove -> End
            joinsBelow -> Start
            else -> None
        }
    }
}

/**
 * Whether [post] continues the thread of [above], the row drawn directly above it: [above]
 * is the post it replies to (any member of an album), or a sibling replying to the same
 * post. Only same-chat replies chain — a reply to another channel's post keeps its quote,
 * which is the only thing saying whose post it answers. A hand-picked quote also keeps its
 * card: the quoted fragment is content, not a repeat of the parent. Comments and deleted
 * tombstones never chain.
 */
internal fun continuesThreadOf(post: TimelinePost, above: TimelinePost): Boolean {
    if (post.chatId != above.chatId || !post.isChainable() || !above.isChainable()) return false
    val reply = post.reply ?: return false
    if (reply.replyToChatId != post.chatId || reply.isQuote) return false
    val target = reply.replyToMessageId
    if (target == above.id || target in above.albumMessageIds) return true
    val aboveReply = above.reply ?: return false
    return aboveReply.replyToChatId == post.chatId && aboveReply.replyToMessageId == target
}

private fun TimelinePost.isChainable(): Boolean = parentId == null && !isDeleted

/**
 * [items] (data order, newest first) with each post row's [PostChain] set for the visual
 * order: with [reverseLayout] the older neighbour (higher index) is drawn above, otherwise
 * the newer one (lower index) is.
 *
 * Only directly adjacent post rows link. A [FeedItem.Boundary] divider or a post of another
 * channel in between breaks the chain. [source] is the list [items] was cut from before
 * [clusterFeed] hid rows (forward copies, folded series tails): two rows that were not
 * neighbours there do not link, so a connector never bridges hidden posts — a series' kept
 * row does not reach across its folded members. Null when nothing was hidden.
 *
 * Pure function of its inputs; rows whose chain is unchanged keep their instance.
 */
internal fun withPostChains(
    items: List<FeedItem>,
    reverseLayout: Boolean,
    source: List<TimelinePost>? = null,
): List<FeedItem> {
    if (items.isEmpty()) return items
    val sourceIndex: Map<String, Int>? = source?.let { src ->
        HashMap<String, Int>(src.size * 2).also { map -> src.forEachIndexed { i, p -> map[p.feedKey()] = i } }
    }
    // linked[i]: data rows i and i + 1 are one thread.
    val linked = BooleanArray((items.size - 1).coerceAtLeast(0)) { i ->
        val newer = items[i] as? FeedItem.Post
        val older = items[i + 1] as? FeedItem.Post
        newer != null && older != null && wereNeighbours(newer, older, sourceIndex) && if (reverseLayout) {
            continuesThreadOf(newer.post, above = older.post)
        } else {
            continuesThreadOf(older.post, above = newer.post)
        }
    }
    return items.mapIndexed { i, item ->
        if (item !is FeedItem.Post) return@mapIndexed item
        val withNewer = i > 0 && linked[i - 1]
        val withOlder = i < linked.size && linked[i]
        val chain = if (reverseLayout) {
            PostChain.of(joinsAbove = withOlder, joinsBelow = withNewer)
        } else {
            PostChain.of(joinsAbove = withNewer, joinsBelow = withOlder)
        }
        if (chain == item.chain) item else item.copy(chain = chain)
    }
}

private fun wereNeighbours(newer: FeedItem.Post, older: FeedItem.Post, sourceIndex: Map<String, Int>?): Boolean {
    if (sourceIndex == null) return true
    val a = sourceIndex[newer.key] ?: return false
    val b = sourceIndex[older.key] ?: return false
    return b == a + 1
}
