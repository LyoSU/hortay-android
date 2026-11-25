package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.sqrt

/**
 * Reveal-ripple maths shared by text and media spoilers (pure, JVM-testable).
 *
 * Telegram reveals a spoiler with a circle that grows from the tap point:
 *  * `SpoilerEffect.startRipple` / `ChatMessageCell.startRevealMedia` (DrKLO/Telegram) — duration
 *    `clamp(maxRadius * 0.3, 250, 550)` ms with the radius in px; text eases in
 *    (`Easings.easeInQuad`), media eases both ways (`CubicBezierInterpolator.EASE_BOTH`).
 *  * Telegram Web A (`gpuCanvas.worker.ts` `calcRevealMaxRadius`) grows it to the corner of the
 *    spoiler farthest from the tap, so the front leaves the cover exactly when it ends, and
 *    softens the front (`RIPPLE_FEATHER`); [RIPPLE_FEATHER] is the share of the radius the
 *    content fades in over.
 */
internal object SpoilerRipple {
    const val MIN_DURATION_MS = 250L
    const val MAX_DURATION_MS = 550L
    private const val MS_PER_PX = 0.3f

    /** Share of the current radius over which revealed content fades in behind the front. */
    const val RIPPLE_FEATHER = 0.18f

    /** Reduced motion: no ripple, the cover just fades out this fast. */
    const val REDUCED_MOTION_FADE_MS = 150L

    val EaseInQuad: Easing = Easing { t -> t * t }

    /** `CubicBezierInterpolator.EASE_BOTH` — (0.42, 0, 0.58, 1). */
    val EaseBoth: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Distance from the origin to the farthest corner of the rect. */
    fun farthestCorner(ox: Float, oy: Float, left: Float, top: Float, right: Float, bottom: Float): Float {
        val dx = maxOf(ox - left, right - ox)
        val dy = maxOf(oy - top, bottom - oy)
        return sqrt(dx * dx + dy * dy)
    }

    /** Telegram's ripple duration for a front that travels [maxRadiusPx]. */
    fun durationMs(maxRadiusPx: Float): Long =
        (maxRadiusPx * MS_PER_PX).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    /** Linear time fraction of a reveal started at [startMs], clamped to `[0, 1]`. */
    fun fraction(nowMs: Long, startMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 1f
        return ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /**
     * Outer radius of the front at eased [progress]: the feathered band behind it must also
     * clear [maxRadius] by the end, so the circle overshoots by the feather share.
     */
    fun radius(progress: Float, maxRadius: Float): Float =
        progress * maxRadius / (1f - RIPPLE_FEATHER)
}
