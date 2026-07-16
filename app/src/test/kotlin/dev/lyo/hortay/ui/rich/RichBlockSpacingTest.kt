package dev.lyo.hortay.ui.rich

import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichInline
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [blockSpacingBetween] — the pure asymmetric-rhythm rule between two sibling rich
 * blocks. A heading must "belong" to what follows it: generous space before it, tight after.
 */
class RichBlockSpacingTest {

    private fun paragraph(i: Int = 0): RichBlock = RichBlock.Paragraph(RichInline.Plain("p$i"))
    private fun heading(size: Int = 1): RichBlock = RichBlock.SectionHeading(RichInline.Plain("h"), size)
    private fun photo(): RichBlock = RichBlock.Photo(media = null, fullscreen = null, caption = null, hasSpoiler = false)
    private fun table(): RichBlock = RichBlock.Table(caption = null, rows = persistentListOf(), isBordered = false, isStriped = false)
    private fun details(): RichBlock = RichBlock.Details(RichInline.Plain("d"), persistentListOf(paragraph()), isOpen = false)
    private fun quote(): RichBlock = RichBlock.BlockQuote(persistentListOf(paragraph()), credit = null)

    @Test
    fun `space before a heading is larger than after it`() {
        val before = blockSpacingBetween(paragraph(), heading())
        val after = blockSpacingBetween(heading(), paragraph())
        assertEquals(22.dp, before, "a new section opens with air above its title")
        assertEquals(8.dp, after, "the heading hugs the block it introduces")
        assertTrue(before > after, "before-heading must exceed after-heading")
    }

    @Test
    fun `paragraph pair uses the default reading rhythm`() {
        assertEquals(RICH_BLOCK_GAP, blockSpacingBetween(paragraph(0), paragraph(1)))
        assertEquals(12.dp, blockSpacingBetween(paragraph(0), paragraph(1)))
    }

    @Test
    fun `a large-section boundary earns a wider gap`() {
        assertEquals(16.dp, blockSpacingBetween(paragraph(), photo()), "text → media")
        assertEquals(16.dp, blockSpacingBetween(photo(), paragraph()), "media → text")
        assertEquals(16.dp, blockSpacingBetween(paragraph(), table()), "text → table")
        assertEquals(16.dp, blockSpacingBetween(paragraph(), details()), "text → details")
        assertEquals(16.dp, blockSpacingBetween(paragraph(), quote()), "text → quote")
    }

    @Test
    fun `after-heading beats the section-boundary rule`() {
        // A media block right after a title still binds tightly to it.
        assertEquals(8.dp, blockSpacingBetween(heading(), photo()))
    }

    @Test
    fun `spacing is only defined between siblings so first and last carry no outer padding`() {
        // The renderer inserts a gap only for index in 1 until size — a list of N blocks yields
        // exactly N-1 gaps, leaving the first block's top and last block's bottom untouched.
        val blocks = listOf(heading(), paragraph(0), photo(), paragraph(1))
        val gaps = blocks.indices.drop(1).map { blockSpacingBetween(blocks[it - 1], blocks[it]) }
        assertEquals(blocks.size - 1, gaps.size)
        assertEquals(listOf(8.dp, 16.dp, 16.dp), gaps)
    }
}
