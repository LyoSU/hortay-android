package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Whether [RichMessageBody] renders the whole document or a bounded feed preview.
 *
 * `Preview` projects the block list to a short prefix ([RichDocument.previewProjection]) BEFORE
 * composition, so a feed card never mounts the tables, collapsible details, slideshows or media
 * composables (state collectors, downloads) that sit far below the clamped fold. A pixel clamp
 * ([dev.lyo.hortay.ui.text.ClampedContent]) still trims the projected prefix; the projection
 * bounds the *work*, the clamp bounds the *height*.
 */
enum class RichMessageMode { Preview, Full }

/**
 * Top-level text-ish blocks a [RichMessageMode.Preview] keeps. With the feed body clamped to
 * ~18 lines this comfortably overfills the visible height while capping composition cost; the
 * pixel clamp discards whatever the projected prefix still overflows.
 */
internal const val PREVIEW_MAX_TEXT_BLOCKS = 6

/** Top-level media blocks a [RichMessageMode.Preview] keeps — one lead image is the feed idiom. */
internal const val PREVIEW_MAX_MEDIA_BLOCKS = 1

/**
 * Media-bearing top-level blocks: each mounts a media composable (MediaCache observe + download
 * enqueue) so they count against the media budget, not the text one.
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
 * ([maxTextBlocks] for everything text-ish incl. tables/details, [maxMediaBlocks] for media) is
 * spent; blocks past a full budget are dropped, and the walk stops once both budgets are full.
 * Order is preserved. When nothing is dropped the original [RichDocument.blocks] instance is
 * returned unchanged (stable identity for `remember` keys downstream).
 */
fun RichDocument.previewProjection(
    maxTextBlocks: Int = PREVIEW_MAX_TEXT_BLOCKS,
    maxMediaBlocks: Int = PREVIEW_MAX_MEDIA_BLOCKS,
): ImmutableList<RichBlock> {
    var textCount = 0
    var mediaCount = 0
    val kept = ArrayList<RichBlock>(minOf(blocks.size, maxTextBlocks + maxMediaBlocks))
    for (block in blocks) {
        if (textCount >= maxTextBlocks && mediaCount >= maxMediaBlocks) break
        if (block.isMedia()) {
            if (mediaCount < maxMediaBlocks) {
                kept += block
                mediaCount++
            }
        } else if (textCount < maxTextBlocks) {
            kept += block
            textCount++
        }
    }
    return if (kept.size == blocks.size) blocks else kept.toImmutableList()
}
