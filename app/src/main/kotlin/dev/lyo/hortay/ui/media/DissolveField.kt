package dev.lyo.hortay.ui.media

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure math of the dissolve effect (see [DissolveEffect] for the engine). No Android types, so
 * the grid, the timing curve and the particle motion are unit-tested on the JVM.
 *
 * Model — after Telegram's ThanosEffect (DrKLO/Telegram `thanos_vertex.glsl`), reimplemented in
 * closed form so a frame is a pure function of time instead of an integrated GPU state:
 *  * the captured picture is cut into a grid of cells; each cell is one particle;
 *  * a dissolve front sweeps across the picture in reading direction over [DISSOLVE_SWEEP_S]:
 *    a cell starts moving when the front reaches it (plus a little jitter), easing in over
 *    [DISSOLVE_RAMP_S];
 *  * a moving cell flies off in a random direction at 26–52 dp/s, drifts with the sweep,
 *    rises under a constant upward pull, wobbles, and fades and shrinks out over its own
 *    lifetime. Until the front reaches it a cell sits exactly in place at full opacity, so the
 *    untouched part of the picture reads as the original row.
 * Time below is "effect time": wall time × [DISSOLVE_TIME_SCALE].
 */

/** Effect time runs this much faster than wall time (Telegram's `timeScale`). */
internal const val DISSOLVE_TIME_SCALE = 1.15f

/** How long the dissolve front takes to cross the picture, in effect time. */
internal const val DISSOLVE_SWEEP_S = 0.6f

/** The front starts this far ahead of the leading edge, so the first column moves at once. */
internal const val DISSOLVE_LEAD_S = 0.1f

/** Per-cell random delay on top of the sweep, so the front is ragged, not a ruler line. */
internal const val DISSOLVE_JITTER_S = 0.08f

/** A cell's speed ramps from rest to full over this long once the front reaches it. */
internal const val DISSOLVE_RAMP_S = 0.2f

/** Cell lifetime range, in units of decayed motion time. */
internal const val DISSOLVE_LIFE_MIN_S = 0.7f / DISSOLVE_TIME_SCALE
internal const val DISSOLVE_LIFE_MAX_S = 1.5f / DISSOLVE_TIME_SCALE

/** Lifetime drains this much faster than motion time. */
internal const val DISSOLVE_DECAY = 1.2f

/** The last stretch of lifetime over which a cell fades from opaque to gone. */
internal const val DISSOLVE_FADE_S = 0.55f

private const val SPEED_MIN_DP = 26f
private const val SPEED_MAX_DP = 52f
private const val DRIFT_DP = 18f
private const val RISE_DP = 65f
private const val WOBBLE_DP = 3f
private const val WOBBLE_FREQ_MIN = 5f
private const val WOBBLE_FREQ_MAX = 9f
private const val MIN_SCALE = 0.2f

/** When the front reaches a cell at normalised reading position [u] (0 = leading edge). */
internal fun dissolveStart(u: Float, jitter: Float): Float =
    u * DISSOLVE_SWEEP_S + jitter * DISSOLVE_JITTER_S - DISSOLVE_LEAD_S

/**
 * Motion time of a cell at effect time [t]: the integral of its speed ramp, which goes linearly
 * from 0 at [start] to 1 at [start] + [DISSOLVE_RAMP_S]. Zero before the front arrives,
 * quadratic while easing in, then linear — continuous and monotonic.
 */
internal fun dissolveMotionTime(t: Float, start: Float): Float {
    val d = t - start
    return when {
        d <= 0f -> 0f
        d < DISSOLVE_RAMP_S -> d * d / (2f * DISSOLVE_RAMP_S)
        else -> d - DISSOLVE_RAMP_S / 2f
    }
}

/** Opacity of a cell with lifetime [life] after [motionTime] of motion: 1 at rest, then fades. */
internal fun dissolveAlpha(life: Float, motionTime: Float): Float =
    ((life - DISSOLVE_DECAY * motionTime) / DISSOLVE_FADE_S).coerceIn(0f, 1f)

/** Effect time at which a cell that starts at [start] with lifetime [life] is fully gone. */
internal fun dissolveEndTime(start: Float, life: Float): Float {
    // Lifetimes are always longer than the ramp's catch-up, so the cell dies on the linear
    // stretch of [dissolveMotionTime]: motionTime = t - start - RAMP/2.
    return start + DISSOLVE_RAMP_S / 2f + life / DISSOLVE_DECAY
}

/** The latest moment any cell can still be visible, whatever its jitter and lifetime. */
internal val DISSOLVE_MAX_END_S: Float =
    dissolveEndTime(dissolveStart(1f, 1f), DISSOLVE_LIFE_MAX_S)

/** A grid of [cols] × [rows] cells over a picture. */
internal class DissolveGrid(val cols: Int, val rows: Int) {
    val count: Int get() = cols * rows
}

/**
 * The grid for a [widthPx] × [heightPx] picture: cells as close to square as the picture
 * allows, never smaller than [minCellPx], and never more than [maxCells] of them.
 */
internal fun dissolveGrid(widthPx: Float, heightPx: Float, minCellPx: Float, maxCells: Int): DissolveGrid {
    if (widthPx < 1f || heightPx < 1f || maxCells < 1) return DissolveGrid(0, 0)
    var cell = max(minCellPx, sqrt(widthPx * heightPx / maxCells))
    while (true) {
        val cols = ceil(widthPx / cell).toInt().coerceAtLeast(1)
        val rows = ceil(heightPx / cell).toInt().coerceAtLeast(1)
        if (cols * rows <= maxCells) return DissolveGrid(cols, rows)
        cell *= 1.05f
    }
}

/**
 * Index buffer for [quads] quads laid out as 4 consecutive vertices each (TL, TR, BR, BL),
 * two triangles per quad. Indices are 16-bit (Skia reads them unsigned), so [quads] must not
 * exceed [DISSOLVE_MAX_QUADS].
 */
internal fun dissolveQuadIndices(quads: Int): ShortArray {
    require(quads in 0..DISSOLVE_MAX_QUADS)
    val out = ShortArray(quads * 6)
    for (q in 0 until quads) {
        val v = q * 4
        val i = q * 6
        out[i] = v.toShort()
        out[i + 1] = (v + 1).toShort()
        out[i + 2] = (v + 2).toShort()
        out[i + 3] = v.toShort()
        out[i + 4] = (v + 2).toShort()
        out[i + 5] = (v + 3).toShort()
    }
    return out
}

/** 65536 vertices / 4 per quad: the most one 16-bit index buffer can address. */
internal const val DISSOLVE_MAX_QUADS = 16384

/**
 * The particles of one dissolving picture: a [grid] over the picture placed at ([left], [top])
 * with size [width] × [height] in draw coordinates, sampled from a bitmap captured at
 * [texScale] × draw resolution. [dp] is the density (px per dp); [rtl] sweeps and drifts from
 * right to left.
 *
 * [texs] is constant; [update] rewrites [verts] (4 vertices per cell, TL TR BR BL) and
 * [colors] (white at the cell's opacity, modulating the sampled picture) for an effect time.
 *
 * Backing: the list reflows the moment the row leaves, so a neighbour slides in under the
 * picture, and the snapshot is transparent around glyphs. Cells the front has not released
 * yet must hide what is behind them, so [backVerts] holds one solid quad per AT-REST cell in
 * [backingColor] (the surface the rows sit on), collapsed to nothing once the cell is
 * released — a released particle never carries a block of background with it. The backing
 * is drawn first, the particles over it; [restCount] is 0 once the sweep has passed, and the
 * backing pass is skipped. One more preallocated vertex array, not a second, opaque-backed
 * snapshot: half the bitmap memory, and the same index buffer.
 *
 * Nothing is allocated after construction.
 */
internal class DissolveField(
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
    texScale: Float,
    private val grid: DissolveGrid,
    private val dp: Float,
    rtl: Boolean,
    seed: Int,
    backingColor: Int,
) {
    val count: Int = grid.count
    val texs = FloatArray(count * 8)
    val verts = FloatArray(count * 8)
    val colors = IntArray(count * 4)
    val backVerts = FloatArray(count * 8)
    val backColors = IntArray(count * 4) { backingColor or OPAQUE }

    /** Cells still at rest after the last [update]; their backing quads are live. */
    var restCount: Int = 0
        private set

    private val cellW = if (grid.cols > 0) width / grid.cols else 0f
    private val cellH = if (grid.rows > 0) height / grid.rows else 0f
    private val start = FloatArray(count)
    private val life = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val wobbleFreq = FloatArray(count)
    private val wobblePhase = FloatArray(count)

    /** Effect time after which nothing of this picture is visible. */
    val endTime: Float

    init {
        var rng = seed.toLong() xor 0x5DEECE66DL
        fun next(): Float {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            return ((rng ushr 40).toInt() and 0xFFFFFF) / 16777216f
        }
        val drift = if (rtl) -DRIFT_DP else DRIFT_DP
        var end = 0f
        for (r in 0 until grid.rows) {
            for (c in 0 until grid.cols) {
                val i = r * grid.cols + c
                val u = (c + 0.5f) / grid.cols
                start[i] = dissolveStart(if (rtl) 1f - u else u, next())
                life[i] = DISSOLVE_LIFE_MIN_S + next() * (DISSOLVE_LIFE_MAX_S - DISSOLVE_LIFE_MIN_S)
                val angle = next() * (2f * PI.toFloat())
                val speed = (SPEED_MIN_DP + next() * (SPEED_MAX_DP - SPEED_MIN_DP)) * dp
                vx[i] = cos(angle) * speed + drift * dp
                vy[i] = sin(angle) * speed
                wobbleFreq[i] = WOBBLE_FREQ_MIN + next() * (WOBBLE_FREQ_MAX - WOBBLE_FREQ_MIN)
                wobblePhase[i] = next() * (2f * PI.toFloat())
                end = max(end, dissolveEndTime(start[i], life[i]))

                val t = i * 8
                val tx0 = c * cellW * texScale
                val ty0 = r * cellH * texScale
                val tx1 = (c + 1) * cellW * texScale
                val ty1 = (r + 1) * cellH * texScale
                texs[t] = tx0; texs[t + 1] = ty0
                texs[t + 2] = tx1; texs[t + 3] = ty0
                texs[t + 4] = tx1; texs[t + 5] = ty1
                texs[t + 6] = tx0; texs[t + 7] = ty1
            }
        }
        endTime = end
    }

    /** Rewrites [verts] and [colors] for effect time [t]. */
    fun update(t: Float) {
        val rise = RISE_DP * dp
        val wobble = WOBBLE_DP * dp
        val halfW = cellW / 2f
        val halfH = cellH / 2f
        var rest = 0
        for (r in 0 until grid.rows) {
            val baseY = top + (r + 0.5f) * cellH
            for (c in 0 until grid.cols) {
                val i = r * grid.cols + c
                val baseX = left + (c + 0.5f) * cellW
                val m = dissolveMotionTime(t, start[i])
                val v = i * 8
                val atRest = m <= 0f
                val alpha = if (atRest) 1f else dissolveAlpha(life[i], m)
                when {
                    atRest -> writeQuad(verts, v, baseX, baseY, halfW, halfH)
                    alpha <= 0f -> writeQuad(verts, v, baseX, baseY, 0f, 0f)
                    else -> writeMovingQuad(i, v, m, alpha, baseX, baseY, rise, wobble)
                }
                fillColor(i, (alpha * 255f + 0.5f).toInt())
                if (atRest) rest++
                writeQuad(backVerts, v, baseX, baseY, if (atRest) halfW else 0f, if (atRest) halfH else 0f)
            }
        }
        restCount = rest
    }

    /** Whether cell [i] was still at rest (backed) after the last [update]. */
    fun isAtRest(i: Int): Boolean = backVerts[i * 8 + 2] > backVerts[i * 8]

    private fun writeMovingQuad(
        i: Int,
        v: Int,
        m: Float,
        alpha: Float,
        baseX: Float,
        baseY: Float,
        rise: Float,
        wobble: Float,
    ) {
        val phase = wobblePhase[i]
        val angle = wobbleFreq[i] * m + phase
        val wx = wobble * (fastSin(angle) - fastSin(phase))
        val wy = 0.5f * wobble * (fastSin(angle + HALF_PI) - fastSin(phase + HALF_PI))
        val x = baseX + vx[i] * m + wx
        val y = baseY + vy[i] * m - 0.5f * rise * m * m + wy
        // Shrinks over its whole flight, not only while fading, so a released cell reads as
        // dust rather than a flying square.
        val spent = (DISSOLVE_DECAY * m / life[i]).coerceIn(0f, 1f)
        val s = MIN_SCALE + (1f - MIN_SCALE) * (1f - spent) * (0.5f + 0.5f * alpha)
        writeQuad(verts, v, x, y, cellW / 2f * s, cellH / 2f * s)
    }

    /** Opacity 0..255 of cell [i] after the last [update]. */
    fun alphaOf(i: Int): Int = colors[i * 4] ushr 24

    private fun writeQuad(out: FloatArray, v: Int, cx: Float, cy: Float, hw: Float, hh: Float) {
        val l = cx - hw
        val t = cy - hh
        val r = cx + hw
        val b = cy + hh
        out[v] = l; out[v + 1] = t
        out[v + 2] = r; out[v + 3] = t
        out[v + 4] = r; out[v + 5] = b
        out[v + 6] = l; out[v + 7] = b
    }

    private fun fillColor(i: Int, alpha: Int) {
        val color = (alpha shl 24) or 0x00FFFFFF
        val o = i * 4
        colors[o] = color
        colors[o + 1] = color
        colors[o + 2] = color
        colors[o + 3] = color
    }
}

private const val OPAQUE = -0x1000000

private const val HALF_PI = (PI / 2).toFloat()
private const val TWO_PI = (2 * PI).toFloat()
private const val INV_TWO_PI = (1 / (2 * PI)).toFloat()

/**
 * Sine to within ~0.001 — a parabola with one refinement step. The wobble runs for every
 * particle on every frame; this keeps that off `Math.sin`.
 */
internal fun fastSin(x: Float): Float {
    // Wrap to [-PI, PI).
    var a = x - TWO_PI * kotlin.math.floor(x * INV_TWO_PI + 0.5f)
    if (a < -PI.toFloat()) a += TWO_PI
    val y = FAST_B * a + FAST_C * a * kotlin.math.abs(a)
    return FAST_P * (y * kotlin.math.abs(y) - y) + y
}

private const val FAST_B = (4 / PI).toFloat()
private const val FAST_C = (-4 / (PI * PI)).toFloat()
private const val FAST_P = 0.225f

/**
 * Whether the arguments of a `Canvas.drawVertices(TRIANGLES, vertexCount, verts, vertOffset,
 * texs, texOffset, colors, colorOffset, indices, indexOffset, indexCount, paint)` call are safe.
 * The platform copies the arrays natively from `array + offset` without checking a null array
 * or the index values, so a bad shape is a process crash rather than an exception (a null
 * `texs` with a non-zero offset segfaulted on Android 17); false means "skip the call". The
 * engine itself always passes offset 0.
 *
 * [vertexCount] counts float ELEMENTS (two per vertex), as the platform API does; [colors]
 * holds one int per vertex; [indexCount] counts index elements, each read as an unsigned
 * 16-bit vertex number relative to the offsets, which must name an existing vertex.
 */
internal fun dissolveVerticesArgsValid(
    vertexCount: Int,
    verts: FloatArray,
    vertOffset: Int,
    texs: FloatArray?,
    texOffset: Int,
    colors: IntArray?,
    colorOffset: Int,
    indices: ShortArray,
    indexOffset: Int,
    indexCount: Int,
): Boolean {
    if (vertexCount <= 0 || vertexCount % 2 != 0) return false
    val vertices = vertexCount / 2
    if (vertices > MAX_INDEXED_VERTICES) return false
    if (vertOffset < 0 || vertOffset + vertexCount > verts.size) return false
    val texsOk = if (texs == null) texOffset == 0 else texOffset >= 0 && texOffset + vertexCount <= texs.size
    val colorsOk = if (colors == null) colorOffset == 0 else colorOffset >= 0 && colorOffset + vertices <= colors.size
    if (!texsOk || !colorsOk) return false
    if (indexCount <= 0 || indexCount % 3 != 0) return false
    if (indexOffset < 0 || indexOffset + indexCount > indices.size) return false
    for (k in indexOffset until indexOffset + indexCount) {
        if ((indices[k].toInt() and 0xFFFF) >= vertices) return false
    }
    return true
}

private const val MAX_INDEXED_VERTICES = 65_536
