package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable

/**
 * One row in a flattened discussion-thread tree. The message itself rides as a generic
 * [TimelinePost] — channel posts and comments share that model so the same renderer,
 * mapper and caches handle both. The two extra fields are tree-positional metadata
 * computed by [CommentsRepository] for direct LazyColumn consumption.
 */
@Immutable
data class ThreadRow(
    val message: TimelinePost,
    /** Indentation level. Capped so deeply nested chains stay readable on phones. */
    val depth: Int,
    /** True if this row is the last sibling in its parent's reply list — drives connector lines. */
    val isLastSibling: Boolean,
)

/**
 * Flattens a thread's comments into depth-first [ThreadRow]s: replies under the comment
 * they answer (by [TimelinePost.parentId], after mapping album members through
 * [redirect]), siblings by date, depth capped at [maxDepth]. A reply whose parent is the
 * thread root ([rootMessageId]) or isn't in [posts] (deleted, or on a page not loaded)
 * becomes a top-level row. Shared by the signed-in [CommentsRepository] and the guest-mode
 * [dev.lyo.hortay.data.web.WebCommentsThread] so both screens thread comments identically.
 */
internal fun buildThreadRows(
    posts: List<TimelinePost>,
    rootMessageId: Long,
    maxDepth: Int,
    redirect: Map<Long, Long> = emptyMap(),
): List<ThreadRow> {
    if (posts.isEmpty()) return emptyList()
    val ids = posts.mapTo(HashSet()) { it.id }
    val children: Map<Long, List<TimelinePost>> = posts.groupBy { post ->
        val canonicalReplyId = post.parentId?.let { redirect[it] ?: it }
        if (canonicalReplyId != null && canonicalReplyId != rootMessageId && canonicalReplyId in ids) {
            canonicalReplyId
        } else {
            0L
        }
    }
    val rows = ArrayList<ThreadRow>(posts.size)
    fun walk(parentId: Long, depth: Int) {
        val siblings = children[parentId].orEmpty().sortedBy { it.date }
        siblings.forEachIndexed { idx, msg ->
            rows += ThreadRow(
                message = msg,
                depth = depth.coerceAtMost(maxDepth),
                isLastSibling = idx == siblings.lastIndex,
            )
            walk(msg.id, depth + 1)
        }
    }
    walk(0L, 0)
    return rows
}
