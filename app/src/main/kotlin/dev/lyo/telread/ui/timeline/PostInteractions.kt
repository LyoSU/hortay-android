package dev.lyo.telread.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.telread.data.TimelinePost

/**
 * Bundle of post-level callbacks. Held immutable so Compose can skip recomposition when the
 * parent re-renders with the same handlers.
 */
@Immutable
class PostInteractions(
    val onPostClick: (post: TimelinePost) -> Unit = {},
    val onMediaClick: (post: TimelinePost, index: Int) -> Unit = { _, _ -> },
    val onChannelClick: (post: TimelinePost) -> Unit = {},
    val onBookmarkClick: (post: TimelinePost) -> Unit = {},
    val onShareClick: (post: TimelinePost) -> Unit = {},
    val onCopyClick: (post: TimelinePost) -> Unit = {},
    val onOpenClick: (post: TimelinePost) -> Unit = {},
    val isBookmarked: (post: TimelinePost) -> Boolean = { false },
) {
    companion object {
        val Noop = PostInteractions()
    }
}
