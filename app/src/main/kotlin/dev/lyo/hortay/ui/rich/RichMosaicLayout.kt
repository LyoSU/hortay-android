package dev.lyo.hortay.ui.rich

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Pure geometry for a Telegram-style photo collage mosaic. Given an item [count] and the
 * available [width], produces the placement of each visible cell — position, size, per-corner
 * rounding, and (for the overflow tile) a "+N" count — with no Compose dependency, so the
 * layout rules are unit-testable in isolation.
 *
 * Templates by count (matching Telegram's IV collage):
 *  - 2 → two equal cells side by side;
 *  - 3 → one large cell on the start side + two stacked on the end side;
 *  - 4 → a 2×2 grid;
 *  - 5+ → a 2×2 grid where the last (bottom-end) tile shows item 3 with a "+(count-4)" scrim;
 *  - 1 → a single full-bleed cell (not a real collage; callers should render a plain photo,
 *    but the geometry degrades gracefully rather than throwing).
 *
 * Only the mosaic's four OUTER corners are rounded (shared outer radius applied by the
 * renderer); every inner seam stays square. Corner rounding is derived from each cell's
 * fractional position, so it is automatically correct after the RTL horizontal mirror.
 *
 * Coordinates are in the same units as [width] (pixels at the call site). Inner seams are
 * split so adjacent cells sit [gap] apart — each side of a seam gives up `gap / 2`.
 */
internal fun mosaicLayout(
    count: Int,
    width: Float,
    gap: Float,
    isRtl: Boolean,
): RichMosaicLayout {
    require(count >= 1) { "mosaic needs at least one item, was $count" }
    val template = mosaicTemplate(count)
    val height = width / template.aspectRatio
    val cells = template.cells.map { raw ->
        // Physical fractional bounds: mirror x for RTL so item 0 lands on the start (right) edge.
        val leftFrac = if (isRtl) 1f - raw.rightFrac else raw.leftFrac
        val rightFrac = if (isRtl) 1f - raw.leftFrac else raw.rightFrac
        val topFrac = raw.topFrac
        val bottomFrac = raw.bottomFrac
        // A corner is rounded iff it coincides with one of the mosaic's four outer corners.
        val roundTopLeft = leftFrac == 0f && topFrac == 0f
        val roundTopRight = rightFrac == 1f && topFrac == 0f
        val roundBottomLeft = leftFrac == 0f && bottomFrac == 1f
        val roundBottomRight = rightFrac == 1f && bottomFrac == 1f
        RichMosaicCell(
            left = leftFrac * width + if (leftFrac > 0f) gap / 2f else 0f,
            top = topFrac * height + if (topFrac > 0f) gap / 2f else 0f,
            right = rightFrac * width - if (rightFrac < 1f) gap / 2f else 0f,
            bottom = bottomFrac * height - if (bottomFrac < 1f) gap / 2f else 0f,
            roundTopLeft = roundTopLeft,
            roundTopRight = roundTopRight,
            roundBottomLeft = roundBottomLeft,
            roundBottomRight = roundBottomRight,
            sourceIndex = raw.sourceIndex,
            overflow = raw.overflow,
        )
    }
    return RichMosaicLayout(width = width, height = height, cells = cells.toImmutableList())
}

/** Number of collage items rendered as tiles before the "+N" overflow tile takes over. */
internal const val MOSAIC_VISIBLE_CAP = 4

/**
 * The viewer index a tap on [cell] should open: the item it shows, or — for the overflow
 * tile — the first hidden item, so tapping "+N" reveals the remainder from the top.
 */
internal fun RichMosaicCell.viewerIndex(): Int =
    if (overflow > 0) MOSAIC_VISIBLE_CAP else sourceIndex

/** One placed tile of a [RichMosaicLayout]. Bounds are absolute in the layout's own units. */
@Immutable
internal data class RichMosaicCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val roundTopLeft: Boolean,
    val roundTopRight: Boolean,
    val roundBottomLeft: Boolean,
    val roundBottomRight: Boolean,
    /** Index into the collage's item list this tile draws. */
    val sourceIndex: Int,
    /** When > 0, this tile carries a "+N" overflow scrim for the hidden remainder. */
    val overflow: Int,
) {
    val widthPx: Float get() = right - left
    val heightPx: Float get() = bottom - top
}

/** Resolved mosaic geometry: overall box plus every placed cell. */
@Immutable
internal data class RichMosaicLayout(
    val width: Float,
    val height: Float,
    val cells: ImmutableList<RichMosaicCell>,
)

// ---- Templates (fractional, LTR) ---------------------------------------------

private data class RawMosaicCell(
    val leftFrac: Float,
    val topFrac: Float,
    val rightFrac: Float,
    val bottomFrac: Float,
    val sourceIndex: Int,
    val overflow: Int,
)

private data class MosaicTemplate(val aspectRatio: Float, val cells: List<RawMosaicCell>)

private fun mosaicTemplate(count: Int): MosaicTemplate = when (count) {
    1 -> MosaicTemplate(
        aspectRatio = 1.5f,
        cells = listOf(RawMosaicCell(0f, 0f, 1f, 1f, sourceIndex = 0, overflow = 0)),
    )
    2 -> MosaicTemplate(
        aspectRatio = 1.5f,
        cells = listOf(
            RawMosaicCell(0f, 0f, 0.5f, 1f, sourceIndex = 0, overflow = 0),
            RawMosaicCell(0.5f, 0f, 1f, 1f, sourceIndex = 1, overflow = 0),
        ),
    )
    3 -> MosaicTemplate(
        aspectRatio = 1.5f,
        cells = listOf(
            RawMosaicCell(0f, 0f, 0.5f, 1f, sourceIndex = 0, overflow = 0),
            RawMosaicCell(0.5f, 0f, 1f, 0.5f, sourceIndex = 1, overflow = 0),
            RawMosaicCell(0.5f, 0.5f, 1f, 1f, sourceIndex = 2, overflow = 0),
        ),
    )
    else -> MosaicTemplate(
        aspectRatio = 1f,
        cells = listOf(
            RawMosaicCell(0f, 0f, 0.5f, 0.5f, sourceIndex = 0, overflow = 0),
            RawMosaicCell(0.5f, 0f, 1f, 0.5f, sourceIndex = 1, overflow = 0),
            RawMosaicCell(0f, 0.5f, 0.5f, 1f, sourceIndex = 2, overflow = 0),
            RawMosaicCell(0.5f, 0.5f, 1f, 1f, sourceIndex = 3, overflow = (count - MOSAIC_VISIBLE_CAP).coerceAtLeast(0)),
        ),
    )
}
