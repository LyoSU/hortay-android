package dev.lyo.hortay.ui.media

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * Telegram-style spoiler / sensitive-content cover for media. The visual differs by [SpoilerKind]:
 *
 *  * [SpoilerKind.Spoiler] — the particle field alone. Like Telegram, the field itself is the
 *    affordance: tap to reveal.
 *  * [SpoilerKind.Sensitive] — the field plus a centred icon-and-label pill, so the user knows
 *    *why* the cover is there and that tapping is a deliberate consent step.
 */
enum class SpoilerKind { Spoiler, Sensitive }

/**
 * Reveal state of one media spoiler, shared by the content's [mediaSpoilerBlur] and its
 * [SpoilerOverlay] so both cut the same circle. Covered → (tap) Revealing → Revealed.
 *
 * Telegram (`ChatMessageCell.startRevealMedia` / `drawBlurredPhoto`, DrKLO/Telegram) draws the
 * sharp media, then the blurred copy and its particles clipped OUT of a circle that grows from the
 * tap point to the media's diagonal in `clamp(radius * 0.3, 250, 550)` ms, `EASE_BOTH`. Telegram
 * Web A grows it to the farthest corner instead, which ends the front exactly at the edge; that is
 * what [SpoilerRipple.farthestCorner] does here.
 */
@Stable
class MediaSpoilerState internal constructor(covered: Boolean) {
    internal var phase by mutableIntStateOf(if (covered) PHASE_COVERED else PHASE_REVEALED)
        private set
    internal var originX = 0f
        private set
    internal var originY = 0f
        private set
    internal var startMs = 0L
        private set
    internal var durationMs = 0L
        private set
    internal var maxRadius = 0f
        private set
    internal var reducedMotion = false
        private set

    /** Layout size of the covered media, kept by the overlay; not state (read on tap only). */
    internal var width = 0f
    internal var height = 0f

    /** The cover is gone: autoplay may start and taps open the media. */
    val isRevealed: Boolean get() = phase == PHASE_REVEALED

    internal fun startReveal(x: Float, y: Float, reduced: Boolean) {
        if (phase != PHASE_COVERED) return
        originX = if (x.isNaN()) width / 2f else x
        originY = if (y.isNaN()) height / 2f else y
        reducedMotion = reduced
        maxRadius = SpoilerRipple.farthestCorner(originX, originY, 0f, 0f, width, height)
        durationMs = if (reduced) SpoilerRipple.REDUCED_MOTION_FADE_MS else SpoilerRipple.durationMs(maxRadius)
        startMs = SpoilerParticles.nowMs()
        phase = PHASE_REVEALING
    }

    internal fun finish() {
        phase = PHASE_REVEALED
    }

    /** Linear time fraction of the running reveal, from the shared frame clock (draw phase). */
    internal fun fraction(): Float {
        SpoilerParticles.markRevealing()
        return SpoilerRipple.fraction(SpoilerParticles.frameTimeMs(), startMs, durationMs)
    }

    /** Outer radius of the reveal front (draw phase). */
    internal fun radius(): Float =
        SpoilerRipple.radius(SpoilerRipple.EaseBoth.transform(fraction()), maxRadius)

    internal companion object {
        const val PHASE_COVERED = 0
        const val PHASE_REVEALING = 1
        const val PHASE_REVEALED = 2
    }
}

/** [MediaSpoilerState] for one media slot; [key] resets it (a different file). */
@Composable
fun rememberMediaSpoilerState(key: Any?, covered: Boolean): MediaSpoilerState =
    remember(key) { MediaSpoilerState(covered) }

/**
 * Blurs the media under a spoiler (API 31+ RenderEffect, with Telegram's `getFancyBlurFilter`
 * tone: brightness ×0.9, saturation ×1.6) and, while revealing, draws it twice — sharp, then the
 * blurred copy outside the growing circle — so the photo un-blurs from the tap point. Below
 * API 31 there is no RenderEffect; [SpoilerOverlay]'s minithumb cover stands in for the blur.
 */
fun Modifier.mediaSpoilerBlur(state: MediaSpoilerState, radius: Dp): Modifier {
    // Plain media (and a revealed spoiler) keep a bare chain: no layer is ever allocated.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || state.isRevealed) return this
    return drawWithCache {
        val r = radius.toPx()
        val layer = obtainGraphicsLayer().apply {
            renderEffect = BlurEffect(r, r, TileMode.Clamp)
            colorFilter = SpoilerBlurTone
            clip = true
        }
        val hole = Path()
        onDrawWithContent {
            when (state.phase) {
                MediaSpoilerState.PHASE_REVEALED -> drawContent()
                MediaSpoilerState.PHASE_COVERED -> {
                    layer.record { this@onDrawWithContent.drawContent() }
                    drawLayer(layer)
                }
                else -> {
                    drawContent()
                    if (state.reducedMotion) return@onDrawWithContent
                    holeOf(state, hole)
                    clipPath(hole, ClipOp.Difference) {
                        layer.record { this@onDrawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                }
            }
        }
    }
}

private val SpoilerBlurTone: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(1.6f)
        timesAssign(ColorMatrix().apply { setToScale(0.9f, 0.9f, 0.9f, 1f) })
    },
)

private fun holeOf(state: MediaSpoilerState, out: Path) {
    val r = state.radius()
    out.rewind()
    out.addOval(Rect(state.originX - r, state.originY - r, state.originX + r, state.originY + r))
}

/**
 * The cover over a spoiler media slot: Telegram's drifting particle field (shared tile, see
 * [SpoilerParticles]) over the blurred media, revealed by a circle growing from the tap point.
 * The caller applies [mediaSpoilerBlur] with the same [state] to the media underneath and drops
 * this overlay once [MediaSpoilerState.isRevealed].
 *
 * @param coverThumb TDLib minithumbnail, drawn as the blurred stand-in below API 31 (an upscaled
 *   40 px JPEG reads as a blur), where RenderEffect doesn't exist.
 */
@Composable
fun SpoilerOverlay(
    state: MediaSpoilerState,
    kind: SpoilerKind,
    seed: Int,
    modifier: Modifier = Modifier,
    coverThumb: ByteArray? = null,
) {
    val motion = rememberSpoilerMotion()
    val reducedMotion = rememberReducedMotion()
    val particles = remember(seed) { SpoilerTilePaint(SpoilerTileKind.Media, seed) }
    val hole = remember { Path() }
    val tap = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val thumbCover = !blurSupported && coverThumb != null
    // Below API 31 nothing blurs the media; without a minithumb a heavier veil is the only cover.
    val veil = when {
        blurSupported -> 0f
        thumbCover -> STANDIN_VEIL_ALPHA
        else -> UNBLURRED_VEIL_ALPHA
    }

    if (state.phase == MediaSpoilerState.PHASE_REVEALING) {
        LaunchedEffect(state) {
            while (SpoilerRipple.fraction(SpoilerParticles.nowMs(), state.startMs, state.durationMs) < 1f) {
                withFrameMillis { }
            }
            state.finish()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                state.width = it.width.toFloat()
                state.height = it.height.toFloat()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    tap[0] = down.position.x
                    tap[1] = down.position.y
                }
            }
            // No ripple indication: the reveal circle is the feedback.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = state.phase == MediaSpoilerState.PHASE_COVERED,
                role = Role.Button,
            ) {
                state.startReveal(tap[0], tap[1], reducedMotion)
                tap[0] = Float.NaN
                tap[1] = Float.NaN
            },
    ) {
        if (thumbCover) {
            MinithumbImage(
                bytes = coverThumb,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = coverAlpha(state) }
                    .drawWithContent {
                        val content = this
                        drawOutsideReveal(state, hole) { content.drawContent() }
                    },
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOutsideReveal(state, hole) {
                if (veil > 0f) drawRect(Color.Black.copy(alpha = veil * coverAlpha(state)))
                drawSpoilerParticles(particles, motion, Color.White, alpha = coverAlpha(state))
            }
        }
        if (kind == SpoilerKind.Sensitive) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = if (state.phase == MediaSpoilerState.PHASE_COVERED) 1f else 1f - state.fraction()
                    }
                    .padding(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(
                    name = "visibility_off",
                    tint = Color.White,
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.spoiler_sensitive_tap),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * A static-position media particle field over [modifier]'s bounds, for covers that open
 * elsewhere instead of revealing in place (the channel media grid opens the viewer).
 */
@Composable
fun SpoilerParticleField(seed: Int, modifier: Modifier = Modifier) {
    val motion = rememberSpoilerMotion()
    val particles = remember(seed) { SpoilerTilePaint(SpoilerTileKind.Media, seed) }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawSpoilerParticles(particles, motion, Color.White)
    }
}

/** Opacity of the cover: 1 while covered or rippling, fading out under reduced motion. */
private fun coverAlpha(state: MediaSpoilerState): Float = when {
    state.phase == MediaSpoilerState.PHASE_COVERED -> 1f
    state.reducedMotion -> 1f - state.fraction()
    else -> 1f
}

/** Runs [block] clipped out of the reveal circle while it grows; unclipped otherwise. */
private inline fun DrawScope.drawOutsideReveal(state: MediaSpoilerState, hole: Path, block: DrawScope.() -> Unit) {
    if (state.phase != MediaSpoilerState.PHASE_REVEALING || state.reducedMotion) {
        block()
        return
    }
    holeOf(state, hole)
    clipPath(hole, ClipOp.Difference) { block() }
}

private const val STANDIN_VEIL_ALPHA = 0.1f
private const val UNBLURRED_VEIL_ALPHA = 0.35f
