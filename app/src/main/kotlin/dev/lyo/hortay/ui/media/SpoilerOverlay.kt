package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.lyo.hortay.R
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol
import kotlin.random.Random

/**
 * Telegram-style spoiler / sensitive-content cover. Sits on top of an already-loaded
 * media composable; tap dismisses it and reveals the underlying photo / video.
 *
 *  * The animated dot field mimics Telegram's "shimmering particle" effect — small white
 *    dots drift on a blurred dark background. Implemented as a single Canvas pass with
 *    pseudo-random positions seeded by the slot's stable id, so the same spoiler always
 *    looks the same across recompositions but two different spoilers don't sync up.
 *  * Clickable area covers the whole composable; a small "tap to view" hint sits in the
 *    bottom-center for discoverability.
 *
 * For [SpoilerKind.Sensitive] (TDLib's `isSecret`) the dismiss-required text reads
 * stronger ("конфіденційне"), matching Telegram's wording on time-limited self-destruct
 * media.
 */
enum class SpoilerKind { Spoiler, Sensitive }

@Composable
fun SpoilerOverlay(
    kind: SpoilerKind,
    seed: Int,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "spoiler-shimmer")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spoiler-drift",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onReveal),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(SHIMMER_BLUR)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            drawShimmerField(seed = seed, drift = drift)
        }
        // Bottom-center hint: short copy + lock icon. Telegram puts a similar pill
        // in the same position; keeps the user aware they have to tap.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Symbol(
                    name = if (kind == SpoilerKind.Sensitive) "lock" else "visibility_off",
                    tint = Color.White,
                    size = 20.dp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (kind) {
                    SpoilerKind.Spoiler -> stringResource(R.string.spoiler_tap_to_view)
                    SpoilerKind.Sensitive -> stringResource(R.string.spoiler_sensitive_tap)
                },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun DrawScope.drawShimmerField(seed: Int, drift: Float) {
    val rng = Random(seed)
    val w = size.width
    val h = size.height
    val area = (w * h).toLong()
    // Roughly one dot per ~700 device-px² — same density as the official client at
    // typical spoiler sizes. Caps prevent absurd counts on giant or tiny photos.
    val count = ((area / 700f).toInt()).coerceIn(40, 240)
    repeat(count) {
        val baseX = rng.nextFloat() * w
        val baseY = rng.nextFloat() * h
        // Each dot drifts in its own small circle — keeps motion gentle and avoids a
        // distracting global parallax. Phase offset per dot prevents synchronization.
        val phase = rng.nextFloat()
        val angle = (drift + phase) * 2f * Math.PI.toFloat()
        val dx = kotlin.math.cos(angle) * DOT_DRIFT_PX
        val dy = kotlin.math.sin(angle) * DOT_DRIFT_PX
        val r = DOT_RADIUS_PX + rng.nextFloat() * DOT_RADIUS_VARIANCE_PX
        val a = 0.35f + rng.nextFloat() * 0.45f
        drawCircle(
            color = Color.White.copy(alpha = a),
            radius = r,
            center = Offset(baseX + dx, baseY + dy),
        )
    }
}

private val SHIMMER_BLUR = 6.dp
private const val DOT_DRIFT_PX = 4f
private const val DOT_RADIUS_PX = 0.8f
private const val DOT_RADIUS_VARIANCE_PX = 1.2f
