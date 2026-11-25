package dev.lyo.hortay.ui.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure particle maths behind the spoiler cover (no `android.*`, JVM-testable). The renderer in
 * [SpoilerParticles] steps one of these per tile kind on a background thread and rasterises the
 * result into a seamless, repeating ALPHA_8 tile that every spoiler on screen samples.
 *
 * Ported from Telegram's own spoilers (both GPL, Hortay is GPL-3.0-or-later):
 *  * text — `org.telegram.ui.Components.spoilers.SpoilerEffect` (DrKLO/Telegram): particles
 *    fly in a straight line at 4–10 px per 500 ms for 1–3 s, each one of three alphas
 *    (0.3 / 0.6 / 1.0), then respawn at a random spot. One shared tile
 *    (`SpoilerEffectBitmapFactory`) instead of a simulation per spoiler.
 *  * media — `SpoilerEffect2` + `res/raw/spoiler_vertex.glsl`: particles steered by a curl-noise
 *    flow field, `velocity += normalize(curl) * 0.6 * dt * size * 0.1`, capped at 6 % of the size
 *    per second, life `1.4 / (0.5 + 2 * rand)` s, `alpha = sin(t * π) * (0.6 + 0.4 * rand)`.
 *    Telegram Web A ports the same simulation into a seamless tile (`particles.wgsl`).
 *
 * Deviation: the GLSL curl noise (36 gradient-noise taps per particle) is too costly for a CPU
 * step, so [MediaParticleSim] uses the curl of a sum of [CURL_TERMS] travelling sine waves with
 * integer wave numbers — still divergence-free (particles swirl rather than bunch up) and exactly
 * periodic over the tile, so the tile repeats seamlessly. Text particles also get a short fade in
 * and out instead of Telegram's pop, which reads as a twinkle once the tile is shared.
 */
internal abstract class SpoilerParticleSim(val tileSizePx: Float, val count: Int, seed: Int) {
    protected val rng = Random(seed)
    val x = FloatArray(count)
    val y = FloatArray(count)

    /** Advances every particle by [dtSec] seconds. */
    abstract fun step(dtSec: Float)

    /** Current opacity of particle [i] in `[0, 1]`. */
    abstract fun alphaOf(i: Int): Float

    /**
     * Buckets every visible particle into [SPOILER_ALPHA_LEVELS] point batches
     * (`[x0, y0, x1, y1, …]`, bucket `b` is drawn at alpha `(b + 1) / LEVELS`) and writes the
     * float count of each into [counts]. A particle within [halfStrokePx] of an edge is also
     * written at its wrapped position so the repeating tile has no seams. Grows a batch in place
     * when it runs out of room, so steady-state frames allocate nothing.
     */
    fun fill(batches: Array<FloatArray>, counts: IntArray, halfStrokePx: Float) {
        counts.fill(0)
        val size = tileSizePx
        for (i in 0 until count) {
            val level = alphaLevel(alphaOf(i))
            if (level < 0) continue
            val px = x[i]
            val py = y[i]
            val wrapX = when {
                px < halfStrokePx -> size
                px > size - halfStrokePx -> -size
                else -> 0f
            }
            val wrapY = when {
                py < halfStrokePx -> size
                py > size - halfStrokePx -> -size
                else -> 0f
            }
            put(batches, counts, level, px, py)
            if (wrapX != 0f) put(batches, counts, level, px + wrapX, py)
            if (wrapY != 0f) put(batches, counts, level, px, py + wrapY)
            if (wrapX != 0f && wrapY != 0f) put(batches, counts, level, px + wrapX, py + wrapY)
        }
    }

    private fun put(batches: Array<FloatArray>, counts: IntArray, level: Int, px: Float, py: Float) {
        var batch = batches[level]
        val n = counts[level]
        if (n + 2 > batch.size) {
            batch = batch.copyOf(maxOf(64, batch.size * 2))
            batches[level] = batch
        }
        batch[n] = px
        batch[n + 1] = py
        counts[level] = n + 2
    }

    protected fun wrap(v: Float): Float {
        val size = tileSizePx
        var r = v % size
        if (r < 0f) r += size
        return r
    }
}

/** Opacity levels a tile is rasterised in — one `drawPoints` batch per level. */
internal const val SPOILER_ALPHA_LEVELS = 8

/** Bucket of [alpha] (`-1` when it rounds to fully transparent). */
internal fun alphaLevel(alpha: Float): Int {
    val level = (alpha * SPOILER_ALPHA_LEVELS + 0.5f).toInt()
    return if (level <= 0) -1 else minOf(level, SPOILER_ALPHA_LEVELS) - 1
}

/** Opacity a bucket returned by [alphaLevel] is drawn at. */
internal fun levelAlpha(level: Int): Float = (level + 1).toFloat() / SPOILER_ALPHA_LEVELS

/**
 * Text spoiler particles (Telegram `SpoilerEffect`): straight drift in a random direction at
 * [speedMinPx]..[speedMaxPx] px/s, a 1–3 s life, one of three base alphas.
 */
internal class TextParticleSim(
    tileSizePx: Float,
    count: Int,
    private val speedMinPx: Float,
    private val speedMaxPx: Float,
    seed: Int,
) : SpoilerParticleSim(tileSizePx, count, seed) {
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val age = FloatArray(count)
    private val life = FloatArray(count)
    private val baseAlpha = FloatArray(count)

    init {
        for (i in 0 until count) {
            spawn(i)
            // Desynchronise the first generation so the field never blinks in unison.
            age[i] = rng.nextFloat() * life[i]
        }
    }

    private fun spawn(i: Int) {
        x[i] = rng.nextFloat() * tileSizePx
        y[i] = rng.nextFloat() * tileSizePx
        val rf = rng.nextFloat()
        val angle = rf * TWO_PI - PI.toFloat()
        val speed = speedMinPx + rf * (speedMaxPx - speedMinPx)
        vx[i] = cos(angle) * speed
        vy[i] = sin(angle) * speed
        age[i] = 0f
        life[i] = TEXT_LIFE_MIN_S + rng.nextFloat() * TEXT_LIFE_SPAN_S
        baseAlpha[i] = TEXT_ALPHAS[rng.nextInt(TEXT_ALPHAS.size)]
    }

    override fun step(dtSec: Float) {
        for (i in 0 until count) {
            val a = age[i] + dtSec
            if (a >= life[i]) {
                spawn(i)
                continue
            }
            age[i] = a
            x[i] = wrap(x[i] + vx[i] * dtSec)
            y[i] = wrap(y[i] + vy[i] * dtSec)
        }
    }

    override fun alphaOf(i: Int): Float =
        baseAlpha[i] * fadeEnvelope(age[i], life[i], TEXT_FADE_S)

    companion object {
        /** `SpoilerEffect.ALPHAS`. */
        val TEXT_ALPHAS = floatArrayOf(0.3f, 0.6f, 1.0f)
        const val TEXT_LIFE_MIN_S = 1f
        const val TEXT_LIFE_SPAN_S = 2f
        const val TEXT_FADE_S = 0.18f
    }
}

/** `min(1, age / fade, (life - age) / fade)`, clamped to `[0, 1]`. */
internal fun fadeEnvelope(age: Float, life: Float, fade: Float): Float {
    val e = minOf(age / fade, (life - age) / fade)
    return e.coerceIn(0f, 1f)
}

/**
 * Media spoiler particles (Telegram `SpoilerEffect2`): accelerated along a swirling flow field,
 * velocity capped, each particle fading in and out over its life (`sin(t·π)`).
 *
 * @param refSizePx Telegram scales force and speed by the spoiler texture's size; this is that
 *   reference size, so motion keeps Telegram's on-screen speed independently of the tile size.
 */
internal class MediaParticleSim(
    tileSizePx: Float,
    count: Int,
    refSizePx: Float,
    seed: Int,
) : SpoilerParticleSim(tileSizePx, count, seed) {
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val t = FloatArray(count)
    private val duration = FloatArray(count)
    private val brightness = FloatArray(count)
    private val phase = FloatArray(count)
    private val accelPx = MEDIA_FORCE_MULT * 0.1f * refSizePx
    private val maxVelocityPx = MEDIA_MAX_VELOCITY / 100f * refSizePx
    private val termPhase = FloatArray(CURL_TERMS) { rng.nextFloat() * TWO_PI }
    private val scratch = FloatArray(2)
    private var time = 0f

    init {
        for (i in 0 until count) {
            respawn(i)
            t[i] = rng.nextFloat()
            brightness[i] = 0.6f + 0.4f * rng.nextFloat()
            phase[i] = rng.nextFloat() * MEDIA_PHASE_SPREAD
        }
    }

    private fun respawn(i: Int) {
        x[i] = rng.nextFloat() * tileSizePx
        y[i] = rng.nextFloat() * tileSizePx
        vx[i] = 0f
        vy[i] = 0f
        t[i] = 0f
        duration[i] = 0.5f + 2f * rng.nextFloat()
    }

    override fun step(dtSec: Float) {
        time += dtSec
        val damping = 1f - MEDIA_DAMPING_PER_S * dtSec
        val inv = 1f / tileSizePx
        val drift = time * MEDIA_NOISE_DRIFT
        for (i in 0 until count) {
            val nt = t[i] + dtSec * duration[i] / MEDIA_LONGEVITY
            if (nt >= 1f) {
                respawn(i)
                continue
            }
            t[i] = nt
            curl(x[i] * inv + drift, y[i] * inv + drift, time + phase[i], termPhase, scratch)
            var fx = scratch[0]
            var fy = scratch[1]
            val len = sqrt(fx * fx + fy * fy)
            if (len > 1e-5f) {
                fx /= len
                fy /= len
            }
            var nvx = (vx[i] + fx * accelPx * dtSec) * damping
            var nvy = (vy[i] + fy * accelPx * dtSec) * damping
            val speed = sqrt(nvx * nvx + nvy * nvy)
            if (speed > maxVelocityPx) {
                val k = maxVelocityPx / speed
                nvx *= k
                nvy *= k
            }
            vx[i] = nvx
            vy[i] = nvy
            x[i] = wrap(x[i] + nvx * dtSec)
            y[i] = wrap(y[i] + nvy * dtSec)
        }
    }

    override fun alphaOf(i: Int): Float = sin(t[i] * PI.toFloat()) * brightness[i]

    companion object {
        const val MEDIA_FORCE_MULT = 0.6f
        const val MEDIA_MAX_VELOCITY = 6f
        const val MEDIA_LONGEVITY = 1.4f
        // `dampingMult .9999` per ~60 fps frame.
        const val MEDIA_DAMPING_PER_S = 0.006f
        // `noiseMovement 4.0`: the field slides by 4 % of the texture per second.
        const val MEDIA_NOISE_DRIFT = 0.04f
        // `rand(position) * 2.5` on the noise's time axis, divided by `noiseSpeed .6`.
        const val MEDIA_PHASE_SPREAD = 4f
    }
}

/** Travelling waves summed into the media flow's stream function. */
internal const val CURL_TERMS = 5
private val CURL_M = intArrayOf(1, 2, -3, 2, 4)
private val CURL_N = intArrayOf(2, -1, 1, 3, -1)
private val CURL_AMP = floatArrayOf(1f, 0.9f, 0.6f, 0.5f, 0.35f)
private val CURL_OMEGA = floatArrayOf(0.6f, 0.75f, 0.9f, 1.05f, 1.2f)

/**
 * Curl of the stream function `ψ(u, v, τ) = Σ aₖ sin(2π(mₖu + nₖv) + ωₖτ + φₖ)` at tile-relative
 * `(u, v)`, written into [out] as `(∂ψ/∂v, −∂ψ/∂u)`. Integer `(mₖ, nₖ)` make it exactly periodic
 * over one tile; being a curl it is divergence-free, so particles circulate without clumping.
 */
internal fun curl(u: Float, v: Float, tau: Float, termPhase: FloatArray, out: FloatArray) {
    var fx = 0f
    var fy = 0f
    for (k in 0 until CURL_TERMS) {
        val m = CURL_M[k]
        val n = CURL_N[k]
        val theta = TWO_PI * (m * u + n * v) + CURL_OMEGA[k] * tau + termPhase[k]
        val c = CURL_AMP[k] * TWO_PI * cos(theta)
        fx += c * n
        fy -= c * m
    }
    out[0] = fx
    out[1] = fy
}

private const val TWO_PI = (2.0 * PI).toFloat()
