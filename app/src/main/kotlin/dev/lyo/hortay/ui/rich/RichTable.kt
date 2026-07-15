package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichHorizontalAlignment
import dev.lyo.hortay.data.rich.RichTableCell
import dev.lyo.hortay.data.rich.RichVerticalAlignment

/**
 * Renders a [RichBlock.Table] as a custom-measured grid.
 *
 * Layout model: TDLib emits one [RichTableCell] per grid column in every row; a cell covered
 * by a neighbour's `colspan` / `rowspan` arrives with `text == null` and is dropped — the
 * spanning cell paints over its slot. So a visible cell's list index IS its physical column,
 * and the anchor cell spans `colspan` columns × `rowspan` rows from there.
 *
 * Sizing (single measure pass, driven by intrinsics so no child is measured twice):
 *  - Column width = max intrinsic width of the single-column cells in that column, capped at
 *    [COLUMN_WIDTH_FRACTION] of the viewport; a spanning cell whose content is wider than its
 *    columns' sum widens the last column it covers.
 *  - Row height = max intrinsic height (at the assigned width) of the single-row cells in that
 *    row; a taller spanning cell grows the last row it covers.
 *  - Total table wider than the viewport scrolls inside its own [horizontalScroll] container;
 *    vertical drags pass through to the feed (a horizontal scroller consumes horizontal only).
 */
@Composable
internal fun RichTable(block: RichBlock.Table) {
    val placements = remember(block) { buildPlacements(block) }
    if (placements.cells.isEmpty()) {
        block.caption?.let { RichInlineText(it, MaterialTheme.typography.bodyLarge) }
        return
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val stripeColor = MaterialTheme.colorScheme.surfaceContainerLow

    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxColumnWidth = maxWidth * COLUMN_WIDTH_FRACTION
            val hairline = HAIRLINE
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .then(
                        if (block.isBordered) {
                            Modifier.drawBehind {
                                // Outer top + left edges; each cell paints its own right + bottom,
                                // so the two together close every internal + outer grid line.
                                val w = hairline.toPx()
                                drawRect(color = borderColor, topLeft = Offset.Zero, size = Size(size.width, w))
                                drawRect(color = borderColor, topLeft = Offset.Zero, size = Size(w, size.height))
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Layout(
                    content = {
                        placements.cells.forEach { span ->
                            RichTableCellContent(
                                span = span,
                                isBordered = block.isBordered,
                                isStriped = block.isStriped,
                                borderColor = borderColor,
                                headerColor = headerColor,
                                stripeColor = stripeColor,
                            )
                        }
                    },
                    measurePolicy = tableMeasurePolicy(placements, maxColumnWidth),
                )
            }
        }
        block.caption?.let {
            Spacer(Modifier.height(8.dp))
            RichInlineText(
                it,
                MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun RichTableCellContent(
    span: CellSpan,
    isBordered: Boolean,
    isStriped: Boolean,
    borderColor: Color,
    headerColor: Color,
    stripeColor: Color,
) {
    val cell = span.cell
    val background = when {
        cell.isHeader -> headerColor
        isStriped && span.row % 2 == 1 -> stripeColor
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .background(background)
            .then(
                if (isBordered) {
                    Modifier.drawBehind {
                        val w = HAIRLINE.toPx()
                        // Right edge.
                        drawRect(
                            color = borderColor,
                            topLeft = Offset(size.width - w, 0f),
                            size = Size(w, size.height),
                        )
                        // Bottom edge.
                        drawRect(
                            color = borderColor,
                            topLeft = Offset(0f, size.height - w),
                            size = Size(size.width, w),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = verticalAlignmentOf(cell.valign),
    ) {
        RichInlineText(
            inline = cell.text!!,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                textAlign = textAlignOf(cell.align),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Vertical alignment inside the cell; the child is [Modifier.fillMaxWidth], so the box's
 *  horizontal component is inert and horizontal alignment is carried by `textAlign`. */
private fun verticalAlignmentOf(valign: RichVerticalAlignment): Alignment = when (valign) {
    RichVerticalAlignment.Top -> Alignment.TopStart
    RichVerticalAlignment.Middle -> Alignment.CenterStart
    RichVerticalAlignment.Bottom -> Alignment.BottomStart
}

/** TDLib cell alignment is VISUAL, not logical: `Left` stays left-of-cell even in an RTL
 *  document. Absolute [TextAlign.Left] / [TextAlign.Right] (not Start / End, which flip with
 *  the ambient direction) keep that promise. */
private fun textAlignOf(align: RichHorizontalAlignment): TextAlign = when (align) {
    RichHorizontalAlignment.Left -> TextAlign.Left
    RichHorizontalAlignment.Center -> TextAlign.Center
    RichHorizontalAlignment.Right -> TextAlign.Right
}

/** Fraction of the viewport a single column may occupy before its text starts wrapping. */
private const val COLUMN_WIDTH_FRACTION = 0.6f
private val HAIRLINE = 0.5.dp
private val MIN_COLUMN_WIDTH = 44.dp
private val MIN_ROW_HEIGHT = 28.dp

/** A visible (non-continuation) cell placed at its anchor grid position. */
private data class CellSpan(
    val row: Int,
    val col: Int,
    val colspan: Int,
    val rowspan: Int,
    val cell: RichTableCell,
)

private class TablePlacements(val cells: List<CellSpan>, val columns: Int, val rows: Int)

private fun buildPlacements(block: RichBlock.Table): TablePlacements {
    val columns = block.rows.maxOfOrNull { it.cells.size } ?: 0
    val cells = buildList {
        block.rows.forEachIndexed { r, row ->
            row.cells.forEachIndexed { c, cell ->
                if (cell.text != null) {
                    add(
                        CellSpan(
                            row = r,
                            col = c,
                            colspan = cell.colspan.coerceAtLeast(1),
                            rowspan = cell.rowspan.coerceAtLeast(1),
                            cell = cell,
                        ),
                    )
                }
            }
        }
    }
    return TablePlacements(cells, columns, block.rows.size)
}

private fun tableMeasurePolicy(
    placements: TablePlacements,
    maxColumnWidth: Dp,
) = MeasurePolicy { measurables, _ ->
    val columns = placements.columns
    val rows = placements.rows
    val spans = placements.cells
    // In an RTL document (RichMessageBody flipped LocalLayoutDirection) the first logical
    // column must sit rightmost; Placeable.place is absolute, so mirror x manually.
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val maxColPx = maxColumnWidth.roundToPx()
    val minColPx = MIN_COLUMN_WIDTH.roundToPx()
    val minRowPx = MIN_ROW_HEIGHT.roundToPx()

    // 1. Column widths from single-column cells' intrinsic width, capped at the viewport share.
    val colWidth = IntArray(columns)
    measurables.forEachIndexed { i, m ->
        val span = spans[i]
        if (span.colspan == 1 && span.col < columns) {
            val natural = m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxColPx)
            if (natural > colWidth[span.col]) colWidth[span.col] = natural
        }
    }
    for (c in 0 until columns) if (colWidth[c] == 0) colWidth[c] = minColPx

    // 2. A spanning cell wider than the sum of its columns widens the last column it covers.
    measurables.forEachIndexed { i, m ->
        val span = spans[i]
        if (span.colspan > 1) {
            val end = (span.col + span.colspan).coerceAtMost(columns)
            if (end <= span.col) return@forEachIndexed
            val sum = (span.col until end).sumOf { colWidth[it] }
            val natural = m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxColPx * span.colspan)
            if (natural > sum) colWidth[end - 1] += natural - sum
        }
    }

    val colX = IntArray(columns)
    for (c in 1 until columns) colX[c] = colX[c - 1] + colWidth[c - 1]
    val totalWidth = if (columns == 0) 0 else colX[columns - 1] + colWidth[columns - 1]

    // 3. Assigned width per cell → intrinsic height at that width.
    val cellWidth = IntArray(spans.size)
    val cellHeight = IntArray(spans.size)
    measurables.forEachIndexed { i, m ->
        val span = spans[i]
        val end = (span.col + span.colspan).coerceAtMost(columns)
        val w = (span.col until end).sumOf { colWidth[it] }
        cellWidth[i] = w
        cellHeight[i] = m.maxIntrinsicHeight(w)
    }

    // 4. Row heights from single-row cells; a taller spanning cell grows its last row.
    val rowHeight = IntArray(rows)
    measurables.forEachIndexed { i, _ ->
        val span = spans[i]
        if (span.rowspan == 1 && span.row < rows && cellHeight[i] > rowHeight[span.row]) {
            rowHeight[span.row] = cellHeight[i]
        }
    }
    for (r in 0 until rows) if (rowHeight[r] == 0) rowHeight[r] = minRowPx
    measurables.forEachIndexed { i, _ ->
        val span = spans[i]
        if (span.rowspan > 1) {
            val end = (span.row + span.rowspan).coerceAtMost(rows)
            if (end <= span.row) return@forEachIndexed
            val sum = (span.row until end).sumOf { rowHeight[it] }
            if (cellHeight[i] > sum) rowHeight[end - 1] += cellHeight[i] - sum
        }
    }

    val rowY = IntArray(rows)
    for (r in 1 until rows) rowY[r] = rowY[r - 1] + rowHeight[r - 1]
    val totalHeight = if (rows == 0) 0 else rowY[rows - 1] + rowHeight[rows - 1]

    // 5. Final measure at the exact cell rect (fixed w + h) so vertical alignment can resolve.
    val placeables = measurables.mapIndexed { i, m ->
        val span = spans[i]
        val end = (span.row + span.rowspan).coerceAtMost(rows)
        val h = (span.row until end).sumOf { rowHeight[it] }.coerceAtLeast(rowHeight.getOrElse(span.row) { minRowPx })
        m.measure(Constraints.fixed(cellWidth[i], h))
    }

    layout(totalWidth, totalHeight) {
        placeables.forEachIndexed { i, placeable ->
            val span = spans[i]
            val x = if (isRtl) totalWidth - colX[span.col] - placeable.width else colX[span.col]
            placeable.place(x, rowY[span.row])
        }
    }
}
