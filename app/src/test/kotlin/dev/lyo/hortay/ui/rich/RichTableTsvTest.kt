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
import org.junit.jupiter.api.Test

/**
 * Covers [tableToTsv] — the pure clipboard serializer the fullscreen table viewer's "Copy table"
 * action puts on the clipboard. Contract: rows joined by `\n`, columns by `\t`; a span's text
 * lands only in its anchor column and covered columns emit a blank; newlines / tabs in a cell are
 * flattened to spaces.
 */
class RichTableTsvTest {

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
        isStriped = false,
    )

    @Test
    fun `plain grid tabs columns and newlines rows`() {
        val tsv = tableToTsv(
            table(
                row(cell("Name", header = true), cell("Age", header = true)),
                row(cell("Ada"), cell("36")),
                row(cell("Alan"), cell("41")),
            ),
        )
        assertEquals("Name\tAge\nAda\t36\nAlan\t41", tsv)
    }

    @Test
    fun `colspan and rowspan covered columns emit blanks`() {
        // Row 0: A spans 2 cols (null placeholder), then C.
        // Row 1: D spans 2 rows, then e, f.
        // Row 2: D's covered slot (null placeholder), then h, i.
        val tsv = tableToTsv(
            table(
                row(cell("A", colspan = 2), cell(null), cell("C")),
                row(cell("D", rowspan = 2), cell("e"), cell("f")),
                row(cell(null), cell("h"), cell("i")),
            ),
        )
        // A only in its anchor column; its covered second column is blank. D only in row 1;
        // its rowspan-covered slot in row 2 is blank. Every row keeps all three columns.
        assertEquals("A\t\tC\nD\te\tf\n\th\ti", tsv)
    }

    @Test
    fun `flattens newlines and tabs inside a cell`() {
        val tsv = tableToTsv(
            table(row(cell("line1\nline2\tx"), cell("ok"))),
        )
        assertEquals("line1 line2 x\tok", tsv)
    }

    @Test
    fun `empty table serializes to empty string`() {
        val empty = RichBlock.Table(
            caption = null,
            rows = persistentListOf(),
            isBordered = false,
            isStriped = false,
        )
        assertEquals("", tableToTsv(empty))
    }
}
