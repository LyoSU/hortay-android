package dev.lyo.telread.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.telread.data.FormattedText
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
    /** User tapped the "Переслано від …" chip — open the source channel if resolvable. */
    val onForwardSourceClick: (post: TimelinePost) -> Unit = {},
    val onBookmarkClick: (post: TimelinePost) -> Unit = {},
    val onShareClick: (post: TimelinePost) -> Unit = {},
    val onCopyClick: (post: TimelinePost) -> Unit = {},
    val onOpenClick: (post: TimelinePost) -> Unit = {},
    val isBookmarked: (post: TimelinePost) -> Boolean = { false },
    val onTranslateClick: (post: TimelinePost) -> Unit = {},
    val onClearTranslationClick: (post: TimelinePost) -> Unit = {},
    val isTranslated: (post: TimelinePost) -> Boolean = { false },
    /** Translated text for this post (or any of its album members), null when not translated. */
    val translationFor: (post: TimelinePost) -> FormattedText? = { null },
    /** Toggle the user's reaction with [emoji] on the given post. */
    val onReactionToggle: (post: TimelinePost, emoji: String) -> Unit = { _, _ -> },
) {
    companion object {
        val Noop = PostInteractions()
    }
}
