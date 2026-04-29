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
