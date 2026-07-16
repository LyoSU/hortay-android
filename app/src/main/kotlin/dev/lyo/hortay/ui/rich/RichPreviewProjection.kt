package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Top-level content blocks a [RichMessageMode.FeedPreview] keeps (everything that isn't media,
 * incl. a table or collapsible section — each counts as ONE content block, which is also the
 * seam the later compact-table feed preview hangs off). With the feed body clamped to ~18 lines
 * this overfills the visible height while capping composition cost; the pixel clamp discards
 * whatever the projected prefix still overflows.
 */
internal const val FEED_PREVIEW_MAX_CONTENT_BLOCKS = 4

/** Top-level media blocks a [RichMessageMode.FeedPreview] keeps — one lead image is the feed idiom. */
internal const val FEED_PREVIEW_MAX_MEDIA_BLOCKS = 1

/**
 * Media-bearing top-level blocks: each mounts a media composable (MediaCache observe + download
 * enqueue) so they count against the media budget, not the content one.
 */
private fun RichBlock.isMedia(): Boolean = when (this) {
    is RichBlock.Photo,
    is RichBlock.Video,
    is RichBlock.Animation,
    is RichBlock.Audio,
    is RichBlock.VoiceNote,
    is RichBlock.Collage,
    is RichBlock.Slideshow,
    is RichBlock.MapPreview,
    -> true
    else -> false
}

/**
 * Bounded prefix of [RichDocument.blocks] for a feed preview — a pure function over the block
 * list. Walks blocks in document order, admitting each until its category budget
 * ([maxContentBlocks] for everything text-ish incl. a table / collapsible section counted as one
 * block, [maxMediaBlocks] for media) is spent; blocks past a full budget are dropped, and the
 * walk stops once both budgets are full. Order is preserved. When nothing is dropped the
 * original [RichDocument.blocks] instance is returned unchanged (stable identity for `remember`
 * keys downstream).
 */
fun RichDocument.previewProjection(
    maxContentBlocks: Int = FEED_PREVIEW_MAX_CONTENT_BLOCKS,
    maxMediaBlocks: Int = FEED_PREVIEW_MAX_MEDIA_BLOCKS,
): ImmutableList<RichBlock> {
    var contentCount = 0
    var mediaCount = 0
    val kept = ArrayList<RichBlock>(minOf(blocks.size, maxContentBlocks + maxMediaBlocks))
    for (block in blocks) {
        if (contentCount >= maxContentBlocks && mediaCount >= maxMediaBlocks) break
        if (block.isMedia()) {
            if (mediaCount < maxMediaBlocks) {
                kept += block
                mediaCount++
            }
        } else if (contentCount < maxContentBlocks) {
            kept += block
            contentCount++
        }
    }
    return if (kept.size == blocks.size) blocks else kept.toImmutableList()
}
