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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
 * and the anchor cell spans `colspan` columns × `rowspan` rows from there. [buildPlacements]
 * is the pure function that turns the ragged row/cell lists into that grid (row, column, spans);
 * it drives both the measure pass and the TalkBack `collectionItemInfo` indices.
 *
 * Editorial styling: a flat rounded-12dp neutral container (no elevation, no heavy grid); the
 * header row sits on a slightly higher neutral surface; body rows stripe at a barely-there
 * `onSurface` tint; rows are parted by hairline horizontal separators only. When the content is
 * wider than the viewport it scrolls inside its own [horizontalScroll] and a gradient edge fade
 * scrims whichever side is clipped — which doubles as a stateless swipe hint (no persisted
 * one-time affordance).
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

    val separatorColor = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val stripeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = STRIPE_ALPHA)

    Column {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxColumnWidth = maxWidth * COLUMN_WIDTH_FRACTION
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .clip(TABLE_CONTAINER_SHAPE)
                    .background(containerColor)
                    // Trailing (and, once scrolled, leading) edge fade drawn OVER the scrolled
                    // content: the content dissolves into the container colour at whichever side
                    // still has clipped columns, so the fade reads as a live "more this way"
                    // swipe hint derived purely from the scroll position.
                    .drawWithContent {
                        drawContent()
                        val fadeW = EDGE_FADE_WIDTH.toPx()
                        if (scrollState.value > 0) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(containerColor, Color.Transparent),
                                    startX = 0f,
                                    endX = fadeW,
                                ),
                            )
                        }
                        if (scrollState.value < scrollState.maxValue) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, containerColor),
                                    startX = size.width - fadeW,
                                    endX = size.width,
                                ),
                            )
                        }
                    }
                    .semantics {
                        collectionInfo = CollectionInfo(
                            rowCount = placements.rows,
                            columnCount = placements.columns,
                        )
                    },
            ) {
                Box(modifier = Modifier.horizontalScroll(scrollState)) {
                    Layout(
                        content = {
                            placements.cells.forEach { span ->
                                RichTableCellContent(
                                    span = span,
                                    rows = placements.rows,
                                    isStriped = block.isStriped,
                                    separatorColor = separatorColor,
                                    headerColor = headerColor,
                                    stripeColor = stripeColor,
                                )
                            }
                        },
                        measurePolicy = tableMeasurePolicy(placements, maxColumnWidth),
                    )
                }
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
    rows: Int,
    isStriped: Boolean,
    separatorColor: Color,
    headerColor: Color,
    stripeColor: Color,
) {
    val cell = span.cell
    val background = when {
        cell.isHeader -> headerColor
        isStriped && span.row % 2 == 1 -> stripeColor
        else -> Color.Transparent
    }
    // A hairline row separator only — the flat editorial grid drops vertical rules; the header
    // shade + row stripes carry column scanability. Skipped on the last row so it doesn't ride
    // the container's rounded bottom edge.
    val drawsSeparator = span.row + span.rowspan < rows
    Box(
        modifier = Modifier
            .background(background)
            .then(
                if (drawsSeparator) {
                    Modifier.drawBehind {
                        val w = HAIRLINE.toPx()
                        drawRect(
                            color = separatorColor,
                            topLeft = Offset(0f, size.height - w),
                            size = Size(size.width, w),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = span.row,
                    rowSpan = span.rowspan,
                    columnIndex = span.col,
                    columnSpan = span.colspan,
                )
                if (cell.isHeader) heading()
            }
            .padding(horizontal = CELL_PADDING_H, vertical = CELL_PADDING_V),
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

/** Barely-there stripe on odd body rows — a 3.5% onSurface wash over the neutral container. */
private const val STRIPE_ALPHA = 0.035f

private val TABLE_CONTAINER_SHAPE = RoundedCornerShape(12.dp)
private val HAIRLINE = 0.5.dp
private val EDGE_FADE_WIDTH = 28.dp
private val CELL_PADDING_H = 12.dp
private val CELL_PADDING_V = 10.dp
private val MIN_COLUMN_WIDTH = 44.dp
private val MIN_ROW_HEIGHT = 44.dp

/** A visible (non-continuation) cell placed at its anchor grid position. */
internal data class CellSpan(
    val row: Int,
    val col: Int,
    val colspan: Int,
    val rowspan: Int,
    val cell: RichTableCell,
)

/** The pure placement grid of a [RichBlock.Table]: the visible [cells] with their grid anchors,
 *  and the total [columns] / [rows] extent. */
internal class TablePlacements(val cells: List<CellSpan>, val columns: Int, val rows: Int)

/**
 * Pure projection of a [RichBlock.Table] onto its placement grid — the visible cells (text
 * non-null) at their `(row, col)` anchors with their `colspan` / `rowspan`, plus the table's
 * total column / row extent. A cell's list index within its row IS its physical column, because
 * TDLib emits a dropped `text == null` placeholder for every slot a neighbour's span covers (see
 * the [RichTable] KDoc). Extracted so the grid index math the TalkBack `collectionItemInfo`
 * relies on is unit-testable without a Compose layout pass.
 */
internal fun buildPlacements(block: RichBlock.Table): TablePlacements {
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
