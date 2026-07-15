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
 * Covers [previewProjection] — the pure block-list prefix a [RichMessageMode.Preview] renders,
 * so a feed card never composes tables / details / slideshows / media past the clamped fold.
 */
class RichPreviewProjectionTest {

    private fun paragraph(i: Int): RichBlock = RichBlock.Paragraph(RichInline.Plain("p$i"))
    private fun photo(): RichBlock = RichBlock.Photo(media = null, caption = null, hasSpoiler = false)

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
        val projected = doc(blocks).previewProjection(maxTextBlocks = 6, maxMediaBlocks = 1)

        assertEquals(7, projected.size, "6 text + 1 media cap")
        assertEquals(1, projected.count { it is RichBlock.Photo }, "media capped at 1")
        assertEquals(6, projected.count { it is RichBlock.Paragraph }, "text capped at 6")

        // Order preserved: block 0 is the leading photo (i == 0), then the first six paragraphs.
        assertTrue(projected.first() is RichBlock.Photo, "leading media stays first")
        val paraTexts = projected.filterIsInstance<RichBlock.Paragraph>()
            .map { (it.text as RichInline.Plain).text }
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), paraTexts, "front-of-document order preserved")
    }

    @Test
    fun `keeps a small document unchanged and returns the same instance`() {
        val original = doc(listOf(paragraph(0), photo(), paragraph(1)))
        val projected = original.previewProjection(maxTextBlocks = 6, maxMediaBlocks = 1)

        assertEquals(original.blocks.toList(), projected.toList())
        assertSame(original.blocks, projected, "no truncation → original list instance is reused")
    }

    @Test
    fun `admits text blocks after the media budget is spent, up to the text cap`() {
        // media, then text: the media budget fills on block 0, text keeps flowing until its cap.
        val blocks = buildList {
            add(photo())
            add(photo()) // dropped — media cap is 1
            repeat(10) { add(paragraph(it)) }
        }
        val projected = doc(blocks).previewProjection(maxTextBlocks = 6, maxMediaBlocks = 1)

        assertEquals(7, projected.size)
        assertEquals(1, projected.count { it is RichBlock.Photo })
        assertEquals(6, projected.count { it is RichBlock.Paragraph })
    }

    @Test
    fun `stops once both budgets are full`() {
        val heavyTail = RichBlock.Table(
            caption = null,
            rows = persistentListOf(),
            isBordered = false,
            isStriped = false,
        )
        val blocks = buildList {
            repeat(6) { add(paragraph(it)) }
            add(photo())
            add(heavyTail) // must not enter the projection — both budgets already full
        }
        val projected = doc(blocks).previewProjection(maxTextBlocks = 6, maxMediaBlocks = 1)

        assertEquals(7, projected.size)
        assertTrue(projected.none { it is RichBlock.Table }, "heavy tail past both caps is dropped")
    }

    @Test
    fun `treats collage and slideshow as media`() {
        val collage = RichBlock.Collage(items = persistentListOf(photo()), caption = null)
        val blocks = listOf(collage, photo())
        val projected = doc(blocks).previewProjection(maxTextBlocks = 6, maxMediaBlocks = 1)

        assertEquals(1, projected.size, "collage spends the single media budget; the photo is dropped")
        assertSame(collage, projected.first())
    }
}
