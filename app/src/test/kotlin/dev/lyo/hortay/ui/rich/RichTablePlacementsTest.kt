package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichHorizontalAlignment
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichTableCell
import dev.lyo.hortay.data.rich.RichTableRow
import dev.lyo.hortay.data.rich.RichVerticalAlignment
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [buildPlacements] — the pure table placement grid that feeds both the measure pass and
 * the TalkBack `collectionItemInfo` row/column indices. The invariant under test: a visible
 * cell's list index within its row IS its physical column, because TDLib emits a dropped
 * `text == null` placeholder for every slot a neighbour's colspan / rowspan covers.
 */
class RichTablePlacementsTest {

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

    @Test
    fun `accounts for colspan and rowspan when placing visible cells`() {
        // Row 0 (header): A spans 2 cols → its covered slot is a dropped null placeholder, then C.
        // Row 1: D spans 2 rows, then E, F.
        // Row 2: D's covered slot is a dropped null placeholder, then G, H.
        val block = table(
            row(cell("A", header = true, colspan = 2), cell(null), cell("C", header = true)),
            row(cell("D", rowspan = 2), cell("E"), cell("F")),
            row(cell(null), cell("G"), cell("H")),
        )

        val placements = buildPlacements(block)

        assertEquals(3, placements.columns, "widest row defines the column count")
        assertEquals(3, placements.rows, "one placement row per table row")

        val byText = placements.cells.associateBy { (it.cell.text as RichInline.Plain).text }
        assertEquals(7, byText.size, "the two null placeholder slots are dropped")

        // Header row: A anchors at (0,0) spanning 2 columns; C sits at physical column 2.
        with(byText.getValue("A")) {
            assertEquals(0, row); assertEquals(0, col)
            assertEquals(2, colspan); assertEquals(1, rowspan)
            assertTrue(cell.isHeader)
        }
        with(byText.getValue("C")) {
            assertEquals(0, row); assertEquals(2, col)
            assertEquals(1, colspan); assertEquals(1, rowspan)
        }
        // Rowspan anchor keeps its own column; the row below shifts its visible cells past the
        // covered slot so E/F and G/H stay column-aligned.
        with(byText.getValue("D")) {
            assertEquals(1, row); assertEquals(0, col)
            assertEquals(1, colspan); assertEquals(2, rowspan)
        }
        assertEquals(1, byText.getValue("E").col)
        assertEquals(2, byText.getValue("F").col)
        assertEquals(2, byText.getValue("G").row)
        assertEquals(1, byText.getValue("G").col)
        assertEquals(2, byText.getValue("H").col)
    }

    @Test
    fun `coerces non-positive spans to one`() {
        val block = table(row(cell("X", colspan = 0, rowspan = -3)))
        val span = buildPlacements(block).cells.single()
        assertEquals(1, span.colspan)
        assertEquals(1, span.rowspan)
    }

    @Test
    fun `empty table yields no cells`() {
        val block = RichBlock.Table(
            caption = null,
            rows = persistentListOf(),
            isBordered = false,
            isStriped = false,
        )
        val placements = buildPlacements(block)
        assertEquals(0, placements.columns)
        assertEquals(0, placements.rows)
        assertTrue(placements.cells.isEmpty())
    }
}
