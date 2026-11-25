package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

/** 5 bits per waveform sample — Telegram's `voiceNote.waveform` packing. */
private const val WAVEFORM_BITS_PER_SAMPLE = 5

/** Unplayed bars / track: the accent at this alpha — tinted, not grey, so a resting voice note reads alive. */
private const val UNPLAYED_ALPHA = 0.32f

/** Quietest drawn bar as a fraction of the track height, so silence still reads as a dot row. */
private const val MIN_BAR_FRACTION = 0.12f

/**
 * Unpack Telegram's `voiceNote.waveform`: 5-bit little-endian samples (0..31) packed
 * across the byte array, LSB-first. Returns one amplitude per sample. An empty / null-ish
 * array yields an empty result (the renderer then draws a flat baseline).
 */
internal fun decodeWaveform(bytes: ByteArray): IntArray {
    if (bytes.isEmpty()) return IntArray(0)
    val count = bytes.size * 8 / WAVEFORM_BITS_PER_SAMPLE
    val out = IntArray(count)
    for (i in 0 until count) {
        val bitPos = i * WAVEFORM_BITS_PER_SAMPLE
        val byteIdx = bitPos / 8
        val bitIdx = bitPos % 8
        var value = (bytes[byteIdx].toInt() and 0xFF) ushr bitIdx
        if (bitIdx > 3 && byteIdx + 1 < bytes.size) {
            value = value or ((bytes[byteIdx + 1].toInt() and 0xFF) shl (8 - bitIdx))
        }
        out[i] = value and 0x1F
    }
    return out
}

/**
 * Fit [samples] into exactly [targetCount] bars, each 0..1 relative to the loudest sample.
 *
 * Telegram ships ~100 samples; drawn 1:1 across a phone-width row they became hair-thin
 * grey slivers (the "lifeless" look). Resampling to the number of bars that fit at a
 * readable width — max-pooling when shrinking so a short peak is never averaged away,
 * nearest-sample when stretching — and normalising to the peak keeps quiet recordings from
 * rendering as a flat line. All-silent input yields all zeros.
 */
internal fun resampleWaveform(samples: IntArray, targetCount: Int): FloatArray {
    if (targetCount <= 0) return FloatArray(0)
    val out = FloatArray(targetCount)
    if (samples.isEmpty()) return out
    val n = samples.size
    for (i in 0 until targetCount) {
        val start = i * n / targetCount
        val end = max(start + 1, (i + 1) * n / targetCount)
        var peak = 0
        for (j in start until end.coerceAtMost(n)) peak = max(peak, samples[j])
        out[i] = peak.toFloat()
    }
    val loudest = out.max()
    if (loudest <= 0f) return FloatArray(targetCount)
    for (i in out.indices) out[i] /= loudest
    return out
}

/**
 * Voice-note waveform progress track. Bars are 3 dp wide on a 5 dp pitch — as many as fit
 * the width, resampled from TDLib's samples ([resampleWaveform]); the played prefix is
 * drawn in `primary`, the rest in `primary` at [UNPLAYED_ALPHA]. Falls back to a thin
 * baseline when [bars] is empty (TDLib shipped no waveform).
 *
 * [progress] is read only in the draw phase: the 200 ms playback tick redraws this layer
 * without recomposing the row. The bar layout is cached per size and only rebuilt when the
 * width or [bars] change. Progress runs from the start edge, so it mirrors in RTL.
 */
@Composable
internal fun AudioWaveform(
    bars: IntArray,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = played.copy(alpha = UNPLAYED_ALPHA)
    Spacer(
        modifier = modifier.drawWithCache {
            val barWidth = 3.dp.toPx()
            val pitch = 5.dp.toPx()
            val count = floor((size.width + (pitch - barWidth)) / pitch).toInt().coerceAtLeast(0)
            val heights = resampleWaveform(bars, count)
            val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
            val rtl = layoutDirection == LayoutDirection.Rtl
            onDrawBehind {
                if (bars.isEmpty() || count == 0) {
                    val y = size.height / 2f
                    val stroke = 2.dp.toPx()
                    drawLine(unplayed, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                    return@onDrawBehind
                }
                val head = progress().coerceIn(0f, 1f) * size.width
                for (i in 0 until count) {
                    val h = max(MIN_BAR_FRACTION, heights[i]) * size.height
                    val startX = i * pitch
                    val left = if (rtl) size.width - startX - barWidth else startX
                    val centreFromStart = startX + barWidth / 2f
                    drawRoundRect(
                        color = if (centreFromStart <= head) played else unplayed,
                        topLeft = Offset(left, (size.height - h) / 2f),
                        size = Size(barWidth, h),
                        cornerRadius = radius,
                    )
                }
            }
        },
    )
}

/**
 * Thin linear progress track for an audio track. Replaces M3 `LinearProgressIndicator`,
 * whose end-of-track stop-indicator dot made a track at 0:00 read as "already finished".
 * A 4 dp rounded line in the accent at [UNPLAYED_ALPHA]; the played part in full accent;
 * a 10 dp thumb at the head only while [showThumb] (the track is loaded) — at rest there is
 * nothing at the end of the line. [progress] is a draw-phase read, like [AudioWaveform].
 */
@Composable
internal fun AudioProgressTrack(
    progress: () -> Float,
    showThumb: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = played.copy(alpha = UNPLAYED_ALPHA)
    Spacer(
        modifier = modifier.drawWithCache {
            val stroke = 4.dp.toPx()
            val thumbRadius = 5.dp.toPx()
            // Inset by the thumb radius so the thumb never clips at either end.
            val inset = thumbRadius
            val y = size.height / 2f
            val rtl = layoutDirection == LayoutDirection.Rtl
            val startX = if (rtl) size.width - inset else inset
            val endX = if (rtl) inset else size.width - inset
            onDrawBehind {
                drawLine(unplayed, Offset(startX, y), Offset(endX, y), stroke, StrokeCap.Round)
                val f = progress().coerceIn(0f, 1f)
                val headX = startX + (endX - startX) * f
                if (f > 0f) {
                    drawLine(played, Offset(startX, y), Offset(headX, y), stroke, StrokeCap.Round)
                }
                if (showThumb()) {
                    drawCircle(played, radius = thumbRadius, center = Offset(headX, y))
                }
            }
        },
    )
}
