package dev.lyo.hortay.ui.text

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.lyo.hortay.ui.media.SpoilerRipple

/** One line's slice of a spoiler group, in text-layout px. */
internal data class SpoilerLineRect(
    val line: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rtl: Boolean,
)

/**
 * Unions the slices of one spoiler group per line, in line order. A group can reach a line through
 * several ranges (TDLib splits one cover around custom emoji) or as bidi fragments; Telegram
 * covers each line of a run with one box (`SpoilerEffect.addSpoilerRangesInternal` walks
 * `Layout.getSelectionPath` rects), so one rect per line it is.
 */
internal fun mergeSpoilerLineRects(segments: List<SpoilerLineRect>): List<SpoilerLineRect> {
    if (segments.isEmpty()) return emptyList()
    val byLine = sortedMapOf<Int, SpoilerLineRect>()
    for (s in segments) {
        val prev = byLine[s.line]
        byLine[s.line] = if (prev == null) {
            s
        } else {
            prev.copy(
                left = minOf(prev.left, s.left),
                top = minOf(prev.top, s.top),
                right = maxOf(prev.right, s.right),
                bottom = maxOf(prev.bottom, s.bottom),
            )
        }
    }
    return byLine.values.toList()
}

/**
 * `Path.addRoundRect` radii (`tl, tr, br, bl` × `x, y`) of one line box of a run: only the run's
 * own ends are rounded — its start side on the first line, its end side on the last — like an
 * inline element sliced across lines. The start side is the right edge in RTL.
 */
internal fun spoilerRunRadii(isFirst: Boolean, isLast: Boolean, rtl: Boolean, radius: Float): FloatArray {
    val leftRounded = if (rtl) isLast else isFirst
    val rightRounded = if (rtl) isFirst else isLast
    val l = if (leftRounded) radius else 0f
    val r = if (rightRounded) radius else 0f
    return floatArrayOf(l, l, r, r, r, r, l, l)
}

/**
 * Draw geometry of one spoiler group: [cover] is where particles are drawn (line boxes inset
 * vertically, rounded run ends), [erase] the full line boxes the revealed glyphs are masked in
 * while the ripple runs, and the bounds the ripple must sweep.
 */
internal class SpoilerGeometry(
    val groupId: Int,
    val seed: Int,
    val cover: Path,
    val erase: Path,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Builds [SpoilerGeometry] for every group that has a visible glyph in [layout] (ranges past a
 * `maxLines` clamp are dropped). Per-glyph bounding boxes give each line's horizontal extent,
 * which is RTL-correct; `getHorizontalPosition` at a wrap offset is ambiguous.
 */
internal fun buildSpoilerGeometry(
    layout: TextLayoutResult,
    groups: List<SpoilerGroupInfo>,
    cornerPx: Float,
    insetPx: Float,
): List<SpoilerGeometry> {
    val len = layout.layoutInput.text.length
    if (len == 0 || layout.lineCount == 0 || groups.isEmpty()) return emptyList()
    val lastVisible = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
    val out = ArrayList<SpoilerGeometry>(groups.size)
    val segments = ArrayList<SpoilerLineRect>()
    val box = RectF()
    for (group in groups) {
        segments.clear()
        for (range in group.ranges) {
            val start = range.first.coerceIn(0, len)
            val end = (range.last + 1).coerceIn(start, minOf(len, lastVisible))
            if (start >= end) continue
            for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
                val segStart = maxOf(start, layout.getLineStart(line))
                val segEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
                if (segStart >= segEnd) continue
                val first = layout.getBoundingBox(segStart)
                val last = layout.getBoundingBox(segEnd - 1)
                segments += SpoilerLineRect(
                    line = line,
                    left = minOf(first.left, last.left),
                    top = layout.getLineTop(line),
                    right = maxOf(first.right, last.right),
                    bottom = layout.getLineBottom(line),
                    rtl = layout.getParagraphDirection(segStart) == ResolvedTextDirection.Rtl,
                )
            }
        }
        val rects = mergeSpoilerLineRects(segments)
        if (rects.isEmpty()) continue
        val cover = Path()
        val erase = Path()
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        rects.forEachIndexed { i, r ->
            erase.addRect(r.left, r.top, r.right, r.bottom, Path.Direction.CW)
            val inset = if (r.bottom - r.top > insetPx * 3f) insetPx else 0f
            box.set(r.left, r.top + inset, r.right, r.bottom - inset)
            val radius = minOf(cornerPx, box.height() / 2f)
            cover.addRoundRect(box, spoilerRunRadii(i == 0, i == rects.lastIndex, r.rtl, radius), Path.Direction.CW)
            left = minOf(left, r.left)
            top = minOf(top, r.top)
            right = maxOf(right, r.right)
            bottom = maxOf(bottom, r.bottom)
        }
        out += SpoilerGeometry(group.groupId, group.seed, cover, erase, left, top, right, bottom)
    }
    return out
}

/**
 * Reveal-time drawing helpers of one [LinkAwareText]: the tap origin per group and the
 * allocation-free masks of a running ripple. Draw-thread only.
 *
 * Text appears behind the front like Telegram Android (`SpoilerEffect.renderWithRipple`: glyphs
 * clipped to the ripple circle, particles cleared inside it), with Telegram Web A's soft front:
 * the glyphs fade in over [SpoilerRipple.RIPPLE_FEATHER] of the radius instead of a hard cut.
 */
internal class SpoilerTextFx {
    /** Last pointer-down in text-layout px (NaN when none), recorded before the tap reveals. */
    val lastDown = floatArrayOf(Float.NaN, Float.NaN)
    private val origins = HashMap<Int, FloatArray>()
    private val matrix = Matrix()
    private val circle = Path()
    private val gradient = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
        floatArrayOf(0f, 1f - SpoilerRipple.RIPPLE_FEATHER, 1f),
        Shader.TileMode.CLAMP,
    )
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = gradient
    }

    /**
     * The ripple origin of [geo], fixed on its first reveal frame: the tap if it landed on (or
     * near) the group, else the group's centre (accessibility activation, keyboard, and the
     * other groups of a text, which all reveal on the same frame as the tapped one).
     *
     * The tap is consumed only by the group it landed on: groups are visited in document order,
     * so clearing it on a miss would hand the tapped group a centre origin whenever an earlier
     * group was visited first.
     */
    fun origin(geo: SpoilerGeometry, slopPx: Float): FloatArray = origins.getOrPut(geo.groupId) {
        val x = lastDown[0]
        val y = lastDown[1]
        val near = !x.isNaN() &&
            x >= geo.left - slopPx && x <= geo.right + slopPx &&
            y >= geo.top - slopPx && y <= geo.bottom + slopPx
        if (near) {
            lastDown[0] = Float.NaN
            lastDown[1] = Float.NaN
            floatArrayOf(x, y)
        } else {
            floatArrayOf((geo.left + geo.right) / 2f, (geo.top + geo.bottom) / 2f)
        }
    }

    /** Masks the glyphs of [geo] outside the front of radius [radius] (soft edge). */
    fun erase(canvas: Canvas, geo: SpoilerGeometry, origin: FloatArray, radius: Float) {
        matrix.setScale(radius, radius)
        matrix.postTranslate(origin[0], origin[1])
        gradient.setLocalMatrix(matrix)
        canvas.drawPath(geo.erase, erasePaint)
    }

    /** The ripple disc, reused across frames. */
    fun circle(origin: FloatArray, radius: Float): Path {
        circle.rewind()
        circle.addCircle(origin[0], origin[1], radius, Path.Direction.CW)
        return circle
    }
}
