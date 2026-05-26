package dev.lyo.hortay.ui.media

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shared "shimmering dot field" used by both media spoilers ([SpoilerOverlay]) and inline
 * text spoilers (rendered inside `LinkAwareText`). Telegram covers spoiler content with a
 * cloud of small white particles that gently drift and twinkle — this helper reproduces
 * that look in a single Canvas pass per spoiler region.
 *
 * Resource model — the cloud animates at display refresh while visible, so per-frame cost
 * is the whole budget. [SpoilerField] is the optimisation:
 *  * **Zero per-frame allocation.** The static per-particle data (base position, scatter
 *    angle, speed, size/brightness bucket) is deterministic from `seed` — it never changes
 *    between frames. [SpoilerField.ensure] builds it once into primitive arrays and only
 *    rebuilds when seed/size/density change; each frame writes live coordinates into
 *    pre-sized reusable [FloatArray] buffers. No `Offset` boxing, no `ArrayList`, no
 *    re-seeded `Random` per frame.
 *  * **Six batched draws.** Points are stamped in 3 size buckets × 2 twinkle phases via
 *    `Canvas.drawPoints(FloatArray)` through one reused [Paint] (`StrokeCap.Round` turns a
 *    zero-length stroke into a round dot) — six native draw calls per spoiler instead of
 *    one `drawCircle` per particle.
 *  * Vertical drift uses one shared offset that travels exactly one `wrap` per cycle, so
 *    the Restart-mode loop is seamless (at drift = 1 each wrapped y equals its drift = 0
 *    value, making the snap-back a no-op).
 *  * The phase ([rememberSpoilerDrift]) is hoisted to a single `InfiniteTransition` shared
 *    by every spoiler on screen, so 20 visible cards = 1 animation timer.
 *  * Thanos-style reveal: when the parent passes a non-zero `dispersionProgress` the same
 *    six draws disintegrate the field. Mirrors TG-Android's `thanos_vertex.glsl`: a
 *    left→right *sweep* (each particle starts dissolving only once the wave front reaches
 *    its column — not a uniform random-delay pop), a *fixed-pixel* travel per particle so
 *    big photos crumble evenly instead of exploding from the centre, per-particle speed
 *    jitter, and an accelerating upward wind drift (t²) so dots keep moving through the
 *    whole fade instead of easing to a dead stop.
 *
 * Density target: ~1 dot per 220 px² (media) with floor/ceiling so tiny inline-text runs
 * and huge album photos both look balanced; text passes a denser value.
 */

/** Phase in [0, 1) shared by every spoiler on the current composition. */
@Composable
fun rememberSpoilerDrift(): State<Float> {
    val transition = rememberInfiniteTransition(label = "spoiler-shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spoiler-drift",
    )
}

/**
 * Per-spoiler particle cache. `remember { SpoilerField() }` one instance per spoiler region
 * and feed it to [drawSpoilerShimmer] every frame. Holds the deterministic static layout
 * plus reusable coordinate buffers and a single [Paint], so steady-state drawing allocates
 * nothing. Not thread-safe — only ever touched on the draw thread of its owning Canvas.
 */
class SpoilerField {
    private var seed = Int.MIN_VALUE
    private var w = -1f
    private var h = -1f
    private var density = -1f
    private var n = 0

    // Static, rebuilt only by [ensure]: base x (sway added per frame), base y in [0,1),
    // scatter angle, speed jitter, and the combined size×brightness bucket (0..5).
    private var baseX = FloatArray(0)
    private var baseYFraction = FloatArray(0)
    private var angle = FloatArray(0)
    private var speed = FloatArray(0)
    private var bucketOf = IntArray(0)

    // Reusable per-bucket coordinate buffers ([x0,y0,x1,y1,…]) sized to each bucket's exact
    // population, plus a per-frame write cursor. Six buckets = 3 sizes × 2 twinkle phases.
    private val coords = Array(BUCKETS) { FloatArray(0) }
    private val cursor = IntArray(BUCKETS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun ensure(seed: Int, w: Float, h: Float, density: Float) {
        if (seed == this.seed && w == this.w && h == this.h && density == this.density) return
        this.seed = seed; this.w = w; this.h = h; this.density = density

        n = (w * h / density).toInt().coerceIn(MIN_DOTS, MAX_DOTS)
        if (baseX.size < n) {
            baseX = FloatArray(n); baseYFraction = FloatArray(n)
            angle = FloatArray(n); speed = FloatArray(n); bucketOf = IntArray(n)
        }

        val rng = Random(seed)
        val perBucket = IntArray(BUCKETS)
        for (i in 0 until n) {
            baseX[i] = rng.nextFloat() * w
            baseYFraction[i] = rng.nextFloat()
            angle[i] = rng.nextFloat() * TWO_PI
            speed[i] = DISPERSE_SPEED_MIN + rng.nextFloat() * DISPERSE_SPEED_SPAN
            val sizeBucket = rng.nextInt(8).let { if (it < 5) 0 else if (it < 7) 1 else 2 }
            val bucket = sizeBucket * 2 + if (rng.nextBoolean()) 1 else 0
            bucketOf[i] = bucket
            perBucket[bucket]++
        }
        for (b in 0 until BUCKETS) {
            val need = perBucket[b] * 2
            if (coords[b].size != need) coords[b] = FloatArray(need)
        }
    }

    fun draw(scope: DrawScope, drift: Float, color: Color, dispersionProgress: Float) {
        if (n == 0 || dispersionProgress >= 1f) return

        val wrap = h + VERTICAL_DRIFT_PX
        val driftYPx = drift * wrap
        val swayXPx = sin(drift * TWO_PI) * HORIZONTAL_SWAY_PX

        val twinkle = (sin(drift * TWO_PI) + 1f) * 0.5f
        val alphaBright = 0.55f + twinkle * 0.35f
        val alphaDim = 0.90f - twinkle * 0.35f

        // `1 - p²` holds the cloud near-opaque while the left→right sweep front is still
        // crossing (so late-starting right-hand dots don't ghost out before they move),
        // then drops fast in the back half.
        val dispersionAlphaScale = (1f - dispersionProgress * dispersionProgress).coerceIn(0f, 1f)
        val radiusScale = 1f - 0.4f * dispersionProgress

        cursor.fill(0)
        val dispersing = dispersionProgress > 0f
        for (i in 0 until n) {
            val px = baseX[i] + swayXPx
            val py = ((baseYFraction[i] * wrap + driftYPx) % wrap) - VERTICAL_DRIFT_PX * 0.5f

            var fx = px
            var fy = py
            if (dispersing) {
                // Left→right sweep: a particle only starts dissolving once the wave front
                // reaches its column. Mirrors TG's
                // `particleFraction = clamp(.1 + t - uv.x*uvOffset, 0, .2)/.2`.
                val uvx = (px / w).coerceIn(0f, 1f)
                val start = uvx * DISPERSE_SWEEP_FRACTION
                val t = ((dispersionProgress - start) / (1f - DISPERSE_SWEEP_FRACTION))
                    .coerceIn(0f, 1f)
                if (t > 0f) {
                    // Fixed-pixel travel (NOT size-proportional) so big photos crumble
                    // evenly instead of exploding from the centre; an accelerating (t²)
                    // upward wind drift keeps every dot moving through the fade.
                    val ease = 1f - (1f - t) * (1f - t)
                    val travel = DISPERSE_TRAVEL_PX * speed[i] * ease
                    fx = px + cos(angle[i]) * travel
                    fy = py + sin(angle[i]) * travel - DISPERSE_DRIFT_PX * t * t
                }
            }

            val b = bucketOf[i]
            val arr = coords[b]
            val j = cursor[b]
            arr[j] = fx
            arr[j + 1] = fy
            cursor[b] = j + 2
        }

        val canvas = scope.drawContext.canvas.nativeCanvas
        val argb = color.toArgb()
        // Buckets: 0/1 = small dim/bright, 2/3 = mid, 4/5 = big.
        stamp(canvas, argb, 0, DOT_SMALL_PX, alphaDim, dispersionAlphaScale, radiusScale)
        stamp(canvas, argb, 1, DOT_SMALL_PX, alphaBright, dispersionAlphaScale, radiusScale)
        stamp(canvas, argb, 2, DOT_MID_PX, alphaDim, dispersionAlphaScale, radiusScale)
        stamp(canvas, argb, 3, DOT_MID_PX, alphaBright, dispersionAlphaScale, radiusScale)
        stamp(canvas, argb, 4, DOT_BIG_PX, alphaDim, dispersionAlphaScale, radiusScale)
        stamp(canvas, argb, 5, DOT_BIG_PX, alphaBright, dispersionAlphaScale, radiusScale)
    }

    private fun stamp(
        canvas: android.graphics.Canvas,
        argb: Int,
        bucket: Int,
        dotPx: Float,
        alpha: Float,
        dispersionAlphaScale: Float,
        radiusScale: Float,
    ) {
        val count = cursor[bucket]
        if (count == 0) return
        val outAlpha = (alpha * dispersionAlphaScale).coerceIn(0f, 1f)
        if (outAlpha <= 0f) return
        paint.color = argb
        paint.alpha = (outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        paint.strokeWidth = dotPx * radiusScale
        canvas.drawPoints(coords[bucket], 0, count, paint)
    }

    private companion object {
        const val BUCKETS = 6
    }
}

/**
 * Stamps [field]'s particle cloud into the current [DrawScope]. The field is rebuilt only
 * when seed/size/density change; steady-state frames allocate nothing.
 */
fun DrawScope.drawSpoilerShimmer(
    field: SpoilerField,
    seed: Int,
    drift: Float,
    color: Color = Color.White,
    dispersionProgress: Float = 0f,
    densityPxPerDot: Float = DEFAULT_DENSITY_PX_PER_DOT,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    if (dispersionProgress >= 1f) return
    field.ensure(seed, w, h, densityPxPerDot)
    field.draw(this, drift, color, dispersionProgress)
}

private const val MIN_DOTS = 60
private const val MAX_DOTS = 1400
const val DEFAULT_DENSITY_PX_PER_DOT = 220f
/** Text spoiler density — narrower strips need more per-area particles to read as a
 *  cloud, and TG-Android itself uses per-character density on text (10–30/char). */
const val TEXT_DENSITY_PX_PER_DOT = 90f
// Stroke widths == dot diameters (StrokeCap.Round turns a 0-length stroke into a circle of
// width = strokeWidth). Telegram-Android uses chunky particles — these device PX values
// read as ~1-2dp blobs on xxhdpi.
private const val DOT_SMALL_PX = 3.6f
private const val DOT_MID_PX = 5.4f
private const val DOT_BIG_PX = 7.2f
private const val VERTICAL_DRIFT_PX = 28f
private const val HORIZONTAL_SWAY_PX = 3f
// Disintegration tuning. Travel/drift are fixed device-px (≈ TG's constant px/s velocity),
// independent of spoiler size. SWEEP_FRACTION is the share of the timeline the left→right
// wave front consumes before the rightmost column begins to dissolve.
private const val DISPERSE_TRAVEL_PX = 120f
private const val DISPERSE_DRIFT_PX = 70f
private const val DISPERSE_SWEEP_FRACTION = 0.4f
private const val DISPERSE_SPEED_MIN = 0.6f
private const val DISPERSE_SPEED_SPAN = 0.8f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
