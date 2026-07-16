package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.data.rich.RichInline
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [previewProjection] — the pure block-list prefix a [RichMessageMode.FeedPreview]
 * renders, so a feed card never composes tables / details / slideshows / media past the
 * clamped fold.
 */
class RichPreviewProjectionTest {

    private fun paragraph(i: Int): RichBlock = RichBlock.Paragraph(RichInline.Plain("p$i"))
    private fun photo(): RichBlock = RichBlock.Photo(media = null, fullscreen = null, caption = null, hasSpoiler = false)

    private fun doc(blocks: List<RichBlock>): RichDocument =
        RichDocument(blocks = blocks.toImmutableList(), isRtl = false, isFull = true)

    @Test
    fun `bounds a large document to the caps and preserves order`() {
        // 500 blocks: media scattered through a long text run.
        val blocks = buildList {
            repeat(500) { i ->
                if (i % 25 == 0) add(photo()) else add(paragraph(i))
            }
        }
        val projected = doc(blocks).previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(5, projected.size, "4 content + 1 media cap")
        assertEquals(1, projected.count { it is RichBlock.Photo }, "media capped at 1")
        assertEquals(4, projected.count { it is RichBlock.Paragraph }, "content capped at 4")

        // Order preserved: block 0 is the leading photo (i == 0), then the first four paragraphs.
        assertTrue(projected.first() is RichBlock.Photo, "leading media stays first")
        val paraTexts = projected.filterIsInstance<RichBlock.Paragraph>()
            .map { (it.text as RichInline.Plain).text }
        assertEquals(listOf("p1", "p2", "p3", "p4"), paraTexts, "front-of-document order preserved")
    }

    @Test
    fun `defaults keep the shipped caps`() {
        // Guards the production constants: FEED_PREVIEW_MAX_CONTENT_BLOCKS + _MEDIA_BLOCKS.
        val blocks = buildList {
            add(photo())
            repeat(20) { add(paragraph(it)) }
        }
        val projected = doc(blocks).previewProjection()

        assertEquals(FEED_PREVIEW_MAX_CONTENT_BLOCKS, projected.count { it is RichBlock.Paragraph })
        assertEquals(FEED_PREVIEW_MAX_MEDIA_BLOCKS, projected.count { it is RichBlock.Photo })
    }

    @Test
    fun `keeps a small document unchanged and returns the same instance`() {
        val original = doc(listOf(paragraph(0), photo(), paragraph(1)))
        val projected = original.previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(original.blocks.toList(), projected.toList())
        assertSame(original.blocks, projected, "no truncation → original list instance is reused")
    }

    @Test
    fun `admits content blocks after the media budget is spent, up to the content cap`() {
        // media, then text: the media budget fills on block 0, content keeps flowing until its cap.
        val blocks = buildList {
            add(photo())
            add(photo()) // dropped — media cap is 1
            repeat(10) { add(paragraph(it)) }
        }
        val projected = doc(blocks).previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(5, projected.size)
        assertEquals(1, projected.count { it is RichBlock.Photo })
        assertEquals(4, projected.count { it is RichBlock.Paragraph })
    }

    @Test
    fun `stops once both budgets are full and counts a table as one content block`() {
        val heavyTail = RichBlock.Table(
            caption = null,
            rows = persistentListOf(),
            isBordered = false,
            isStriped = false,
        )
        val blocks = buildList {
            repeat(4) { add(paragraph(it)) }
            add(photo())
            add(heavyTail) // must not enter the projection — both budgets already full
        }
        val projected = doc(blocks).previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(5, projected.size)
        assertTrue(projected.none { it is RichBlock.Table }, "heavy tail past both caps is dropped")
    }

    @Test
    fun `keeps a within-cap table as one content block`() {
        val table = RichBlock.Table(
            caption = null,
            rows = persistentListOf(),
            isBordered = false,
            isStriped = false,
        )
        val blocks = listOf(paragraph(0), table, paragraph(1))
        val projected = doc(blocks).previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(3, projected.size, "table within the content cap is kept as one block")
        assertSame(table, projected[1])
    }

    @Test
    fun `treats collage and slideshow as media`() {
        val collage = RichBlock.Collage(items = persistentListOf(photo()), caption = null)
        val blocks = listOf(collage, photo())
        val projected = doc(blocks).previewProjection(maxContentBlocks = 4, maxMediaBlocks = 1)

        assertEquals(1, projected.size, "collage spends the single media budget; the photo is dropped")
        assertSame(collage, projected.first())
    }
}
