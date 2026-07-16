package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichHorizontalAlignment
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichTableCell
import dev.lyo.hortay.data.rich.RichTableRow
import dev.lyo.hortay.data.rich.RichVerticalAlignment
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the pure helpers behind the compact feed-preview table: [compactPreviewRows] (the
 * rectangular slot projection that keeps columns aligned under colspan / rowspan without a custom
 * measure pass) and [leadingHeaderRowCount].
 */
class RichCompactTablePreviewTest {

    private fun cell(
        text: String?,
        header: Boolean = false,
        colspan: Int = 1,
        rowspan: Int = 1,
    ): RichTableCell = RichTableCell(
        text = text?.let { RichInline.Plain(it) },
        isHeader = header,
        colspan = colspan,
        rowspan = rowspan,
        align = RichHorizontalAlignment.Left,
        valign = RichVerticalAlignment.Top,
    )

    private fun row(vararg cells: RichTableCell) = RichTableRow(cells.toList().toImmutableList())

    private fun table(vararg rows: RichTableRow): RichBlock.Table = RichBlock.Table(
        caption = null,
        rows = rows.toList().toImmutableList(),
        isBordered = true,
        isStriped = true,
    )

    private fun textOf(slot: CompactSlot): String? =
        (slot as? CompactSlot.Anchor)?.let { (it.cell.text as RichInline.Plain).text }

    @Test
    fun `caps rows to the limit and keeps every row spanning the full column count`() {
        val block = table(
            row(cell("H1", header = true), cell("H2", header = true), cell("H3", header = true)),
            row(cell("a"), cell("b"), cell("c")),
            row(cell("d"), cell("e"), cell("f")),
            row(cell("g"), cell("h"), cell("i")),
            row(cell("j"), cell("k"), cell("l")),
        )
        val placements = buildPlacements(block)

        val preview = compactPreviewRows(placements, rowLimit = 3)
        assertEquals(3, preview.size, "row count is capped at the limit")
        // Every rendered row's slot weights sum to the column count (3), so columns stay aligned.
        preview.forEach { slots ->
            val weight = slots.sumOf { if (it is CompactSlot.Anchor) it.colspan else 1 }
            assertEquals(3, weight)
        }
        assertEquals(listOf("H1", "H2", "H3"), preview[0].map { textOf(it) })
    }

    @Test
    fun `colspan anchor covers its columns and rowspan emits a filler below`() {
        val block = table(
            row(cell("wide", header = true, colspan = 2), cell(null), cell("C", header = true)),
            row(cell("tall", rowspan = 2), cell("e"), cell("f")),
            row(cell(null), cell("h"), cell("i")),
        )
        val placements = buildPlacements(block)
        val preview = compactPreviewRows(placements, rowLimit = 3)

        // Header row: a colspan-2 anchor + a single-column anchor → two slots, weight 2 + 1.
        assertEquals(2, preview[0].size)
        assertEquals(2, (preview[0][0] as CompactSlot.Anchor).colspan)
        assertEquals("wide", textOf(preview[0][0]))
        assertEquals("C", textOf(preview[0][1]))

        // The rowspan continuation row: the covered first column is a Filler, then the two cells.
        assertTrue(preview[2][0] is CompactSlot.Filler, "rowspan-covered slot is a filler")
        assertEquals("h", textOf(preview[2][1]))
        assertEquals("i", textOf(preview[2][2]))
    }

    @Test
    fun `leading header row count stops at the first non-header row`() {
        val block = table(
            row(cell("H1", header = true), cell("H2", header = true)),
            row(cell("a"), cell("b")),
            row(cell("c"), cell("d")),
        )
        assertEquals(1, leadingHeaderRowCount(buildPlacements(block)))

        val noHeader = table(row(cell("a"), cell("b")))
        assertEquals(0, leadingHeaderRowCount(buildPlacements(noHeader)))
    }
}
