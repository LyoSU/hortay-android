package dev.lyo.hortay.ui.comments

import androidx.compose.runtime.Immutable

/**
 * The connector-rail slices ONE comment row paints, derived from the whole thread's
 * depth sequence by [threadRails].
 *
 * A rail is a thin vertical line at a "column" — the horizontal centre of the avatar of a
 * comment at that visual depth. It starts under a parent's avatar and runs down to the
 * elbow that hooks into each of its replies. Each row paints only its own vertical slice of
 * every rail crossing it, and rows in the thread list sit edge to edge, so the slices join
 * into one continuous line — no row needs to know any other row's height.
 */
@Immutable
internal data class RailSegments(
    /**
     * Column of the parent's rail this row hooks into with an elbow (top edge → avatar
     * centre → avatar), or -1 for a top-level comment, which hangs off the post itself.
     */
    val elbowColumn: Int,
    /** The parent's rail carries on below this row's elbow towards a later reply of it. */
    val elbowContinues: Boolean,
    /** Bit `k` set = the rail at column `k` (left of [elbowColumn]) crosses this whole row. */
    val passThroughMask: Int,
    /** This row's own rail starts under its avatar: its first reply follows directly. */
    val startsOwnRail: Boolean,
) {
    fun passesThrough(column: Int): Boolean = (passThroughMask shr column) and 1 == 1

    val isEmpty: Boolean
        get() = elbowColumn < 0 && passThroughMask == 0 && !startsOwnRail

    companion object {
        val None = RailSegments(elbowColumn = -1, elbowContinues = false, passThroughMask = 0, startsOwnRail = false)
    }
}

/**
 * Computes [RailSegments] for every row of a thread laid out in depth-first pre-order —
 * the order `CommentsRepository` emits rows in (every reply directly after its parent's
 * subtree prefix), so a row's depth is at most the previous row's depth + 1.
 *
 * [depths] are VISUAL depths (already capped to the screen's indent cap), so a capped
 * deeper reply hooks into the same column its on-screen siblings use. A row that breaks
 * the pre-order invariant (deeper than previous + 1) gets no elbow instead of a line to
 * nowhere.
 *
 * Linear in rows × columns: one backward pass keeps, per depth bound `t`, the depth of
 * the first later row whose depth is ≤ `t`. The rail at column `k` continues past row `i`
 * exactly when that first later row at depth ≤ `k + 1` is another reply at depth `k + 1`
 * (a later child of the same column-`k` ancestor) rather than something shallower that
 * closes the subtree.
 */
internal fun threadRails(depths: List<Int>): List<RailSegments> {
    val n = depths.size
    if (n == 0) return emptyList()
    val maxDepth = depths.maxOrNull()?.coerceAtLeast(0) ?: 0
    // firstAtMost[t] = depth of the first row in the suffix (after the current row) whose
    // depth is ≤ t, or -1 when no such row exists.
    val firstAtMost = IntArray(maxDepth + 2) { -1 }
    val out = arrayOfNulls<RailSegments>(n)
    for (i in n - 1 downTo 0) {
        val d = depths[i].coerceAtLeast(0)
        fun continues(column: Int): Boolean {
            val bound = column + 1
            return bound < firstAtMost.size && firstAtMost[bound] == bound
        }
        val hasElbow = d > 0 && i > 0 && d <= depths[i - 1].coerceAtLeast(0) + 1
        val elbowColumn = if (hasElbow) d - 1 else -1
        var mask = 0
        // Ancestors' rails left of the elbow column (or, with no elbow, left of this row's
        // own column) that still have replies further down cross this row top to bottom.
        val passLimit = if (hasElbow) elbowColumn else d
        for (k in 0 until passLimit) {
            if (continues(k)) mask = mask or (1 shl k)
        }
        out[i] = RailSegments(
            elbowColumn = elbowColumn,
            elbowContinues = hasElbow && continues(elbowColumn),
            passThroughMask = mask,
            startsOwnRail = i + 1 < n && depths[i + 1] == d + 1,
        )
        // Fold row i into the suffix for the next (earlier) row.
        for (t in d until firstAtMost.size) firstAtMost[t] = d
    }
    return out.map { it ?: RailSegments.None }
}
