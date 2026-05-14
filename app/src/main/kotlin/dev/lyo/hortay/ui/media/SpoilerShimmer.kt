package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shared "shimmering dot field" used by both media spoilers ([SpoilerOverlay]) and inline
 * text spoilers (rendered inside `LinkAwareText`). Telegram covers spoiler content with a
 * cloud of small white particles that gently drift and twinkle — this helper reproduces
 * that look in a single Canvas pass per spoiler region.
 *
 * Optimisation notes:
 *  * Points are drawn in 3 size buckets (S/M/L) via [DrawScope.drawPoints] with
 *    [StrokeCap.Round] — six batched draw calls per spoiler (3 sizes × 2 twinkle phases)
 *    instead of one `drawCircle` per particle. At the typical ~60-particle density this
 *    cuts display-list ops by ~10× versus the original per-circle loop.
 *  * Twinkle is implemented by stamping each size bucket twice with two complementary
 *    alphas derived from a single shared `sin(drift)` — no per-point trig.
 *  * Vertical drift uses one shared offset for all points, but it travels exactly one
 *    `wrap` per cycle. That makes the Restart-mode loop seamless: at drift = 1 each
 *    particle's wrapped y equals its y at drift = 0, so the 1-frame snap-back is a
 *    no-op instead of a global stutter.
 *  * The phase ([rememberSpoilerDrift]) is hoisted to a single `InfiniteTransition`
 *    shared by every spoiler on screen, so 20 visible cards = 1 animation timer, not 20.
 *  * Thanos-style reveal: when the parent passes a non-zero [dispersionProgress] the
 *    same six batched draw calls scatter each particle along a seeded radial vector
 *    with a seeded delay (waves, not a uniform pop), and fade alpha + shrink radius.
 *
 * Density target: ~1 dot per 600 px² with floor/ceiling so tiny inline-text runs and
 * huge album photos both look balanced.
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

fun DrawScope.drawSpoilerShimmer(
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

    val area = w * h
    // Default density is tuned against Telegram-Android's GPU spoiler
    // (SpoilerEffect2.particlesCount: ~1000 particles per 500×500 tile ≈ 1/250 px²).
    // Text spoilers pass a denser value (smaller px-per-dot) because TG's CPU spoiler
    // densities the cloud per-character there, not per-area.
    val count = (area / densityPxPerDot).toInt().coerceIn(MIN_DOTS, MAX_DOTS)

    val rng = Random(seed)
    val wrap = h + VERTICAL_DRIFT_PX
    val driftYPx = drift * wrap
    val swayXPx = sin(drift * TWO_PI) * HORIZONTAL_SWAY_PX

    val twinkle = (sin(drift * TWO_PI) + 1f) * 0.5f
    val alphaBright = 0.55f + twinkle * 0.35f
    val alphaDim = 0.90f - twinkle * 0.35f

    // Scatter magnitude scales with the spoiler area so particles always escape past the
    // bounds in the final 200ms. The floor keeps tiny inline-text spoilers from looking
    // anemic in the dispersion.
    val dispersionScale = maxOf(w, h) * 0.45f + DISPERSE_MIN_PX

    val small = ArrayList<Offset>(count / 2)
    val mid = ArrayList<Offset>(count / 3)
    val big = ArrayList<Offset>(count / 6 + 1)
    val smallBright = ArrayList<Offset>(count / 4)
    val midBright = ArrayList<Offset>(count / 6)
    val bigBright = ArrayList<Offset>(count / 8 + 1)

    // Per-call (not per-point) dispersion alpha / radius scaling. The seeded delay
    // distribution is tight enough that one shared multiplier reads correctly to the
    // eye — and a per-point alpha would force one drawPoints call per particle,
    // collapsing the whole batching win.
    val dispersionAlphaScale = (1f - dispersionProgress).coerceIn(0f, 1f)
    val radiusScale = 1f - 0.35f * dispersionProgress

    repeat(count) {
        val px = rng.nextFloat() * w + swayXPx
        val py = ((rng.nextFloat() * wrap + driftYPx) % wrap) - VERTICAL_DRIFT_PX * 0.5f

        // Seeded direction + delay per particle. Without the delay the cloud pops as a
        // single mass; with it dots leave the field in waves, which is the whole point
        // of the Thanos shot.
        val angle = rng.nextFloat() * TWO_PI
        val delay = rng.nextFloat() * DISPERSE_DELAY_FRACTION
        val localProgress = if (dispersionProgress <= delay) 0f
            else ((dispersionProgress - delay) / (1f - delay)).coerceIn(0f, 1f)

        val finalOffset = if (localProgress == 0f) {
            Offset(px, py)
        } else {
            // Ease-out (1 - (1-t)^2) so the surge is fast at first then settles — the
            // signature "pieces of you flying off" motion.
            val ease = 1f - (1f - localProgress) * (1f - localProgress)
            val dx = cos(angle) * dispersionScale * ease
            val dy = sin(angle) * dispersionScale * ease - localProgress * DISPERSE_LIFT_PX
            Offset(px + dx, py + dy)
        }

        val bucket = rng.nextInt(8)
        val brightHalf = rng.nextBoolean()
        when {
            bucket < 5 -> if (brightHalf) smallBright += finalOffset else small += finalOffset
            bucket < 7 -> if (brightHalf) midBright += finalOffset else mid += finalOffset
            else -> if (brightHalf) bigBright += finalOffset else big += finalOffset
        }
    }

    fun stamp(points: ArrayList<Offset>, widthPx: Float, alpha: Float) {
        if (points.isEmpty()) return
        val outAlpha = (alpha * dispersionAlphaScale).coerceIn(0f, 1f)
        if (outAlpha <= 0f) return
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = color.copy(alpha = outAlpha),
            strokeWidth = widthPx * radiusScale,
            cap = StrokeCap.Round,
        )
    }

    stamp(small, DOT_SMALL_PX, alphaDim)
    stamp(smallBright, DOT_SMALL_PX, alphaBright)
    stamp(mid, DOT_MID_PX, alphaDim)
    stamp(midBright, DOT_MID_PX, alphaBright)
    stamp(big, DOT_BIG_PX, alphaDim)
    stamp(bigBright, DOT_BIG_PX, alphaBright)
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
private const val DISPERSE_MIN_PX = 24f
private const val DISPERSE_LIFT_PX = 14f
private const val DISPERSE_DELAY_FRACTION = 0.55f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
