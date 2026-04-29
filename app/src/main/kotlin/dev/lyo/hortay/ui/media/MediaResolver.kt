package dev.lyo.hortay.ui.media

import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent

/**
 * Project a [PostContent] onto the flat list the full-screen viewer expects. Returns null when
 * the content is not viewer-compatible (text, polls, audio-only, …) so callers can skip the tap.
 *
 * Shared between timeline and comments — they render the same content shapes, so they share
 * the same projection.
 */
fun PostContent.toAlbumItems(): List<AlbumItem>? = when (this) {
    is PostContent.PhotoAlbum -> items.takeIf { it.isNotEmpty() }
    is PostContent.Video -> listOf(AlbumItem.Video(media, durationSec, playbackFileId))
    is PostContent.Animation -> listOf(AlbumItem.Animation(media, playbackFileId))
    else -> null
}
