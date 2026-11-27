package dev.lyo.hortay.ui.comments

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.ThreadRow
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One entry of the comments list below the post: a comment, or the quiet "Show N more
 * replies" row standing in for the hidden tail of a long reply branch. Built by
 * [visibleThreadItems].
 */
@Immutable
internal sealed interface ThreadItem {
    /** LazyColumn key. Comments keep their message id (a Long), so the list anchors on them. */
    val key: Any

    /** Visual nesting depth used for the connector rails (see [threadRails]). */
    val railDepth: Int

    @Immutable
    data class Comment(val row: ThreadRow, override val railDepth: Int) : ThreadItem {
        override val key: Any get() = row.message.id
    }

    /**
     * Stands in for [hiddenCount] replies of the branch under top-level comment [rootId].
     * [faces] are up to [MAX_BRANCH_FACES] distinct authors of the hidden replies, in order.
     * Keyed by a String, so the read-ack dwell (which only counts Long keys) never sees it.
     */
    @Immutable
    data class MoreReplies(
        val rootId: Long,
        val hiddenCount: Int,
        val faces: ImmutableList<TimelinePost>,
    ) : ThreadItem {
        override val key: Any get() = "more-replies:$rootId"
        override val railDepth: Int get() = 1
    }
}

/**
 * Lays out [rows] (depth-first pre-order, as `buildThreadRows` emits them) for display:
 * a top-level comment whose branch holds more than [COLLAPSE_BRANCH_ABOVE] replies, and
 * whose id is not in [expandedRoots], shows only its first [SHOWN_BRANCH_REPLIES] replies
 * in thread order, followed by one [ThreadItem.MoreReplies] row counting the rest.
 *
 * Collapsing is a pure function of the current rows, so a reply arriving in a collapsed
 * branch raises its count instead of opening it, and an expanded branch stays expanded.
 * The shown replies are a pre-order prefix of the branch, so every visible reply still
 * sits under a visible parent and the rails stay connected.
 */
internal fun visibleThreadItems(
    rows: List<ThreadRow>,
    expandedRoots: Set<Long>,
    maxVisualDepth: Int,
): ImmutableList<ThreadItem> {
    if (rows.isEmpty()) return persistentListOf()
    val out = ArrayList<ThreadItem>(rows.size)
    var start = 0
    while (start < rows.size) {
        var end = start + 1
        while (end < rows.size && rows[end].depth > 0) end++
        val root = rows[start]
        val replies = end - start - 1
        val collapse = root.depth == 0 &&
            replies > COLLAPSE_BRANCH_ABOVE &&
            root.message.id !in expandedRoots
        val shownEnd = if (collapse) start + 1 + SHOWN_BRANCH_REPLIES else end
        for (i in start until shownEnd) {
            out += ThreadItem.Comment(rows[i], rows[i].depth.coerceAtMost(maxVisualDepth))
        }
        if (collapse) {
            out += ThreadItem.MoreReplies(
                rootId = root.message.id,
                hiddenCount = end - shownEnd,
                faces = branchFaces(rows.subList(shownEnd, end)),
            )
        }
        start = end
    }
    return out.toImmutableList()
}

/**
 * Id of the top-level comment whose branch holds [commentId] (the comment itself when it
 * is top-level), or null when it isn't in [rows].
 */
internal fun branchRootOf(rows: List<ThreadRow>, commentId: Long): Long? {
    var root: Long? = null
    for (row in rows) {
        if (row.depth == 0) root = row.message.id
        if (row.message.id == commentId) return root ?: row.message.id
    }
    return null
}

private fun branchFaces(hidden: List<ThreadRow>): ImmutableList<TimelinePost> =
    hidden.asSequence()
        .map { it.message }
        .distinctBy { it.senderUserId ?: it.senderName }
        .take(MAX_BRANCH_FACES)
        .toList()
        .toImmutableList()

/**
 * A branch collapses only past this many replies. Below it, the "Show N more" row would
 * hide one or two replies at roughly their own height — a tap saved nowhere.
 */
internal const val COLLAPSE_BRANCH_ABOVE = 4

/** Replies a collapsed branch keeps on screen under its top-level comment. */
internal const val SHOWN_BRANCH_REPLIES = 2

/** Author faces on a "Show N more replies" row. */
internal const val MAX_BRANCH_FACES = 3
