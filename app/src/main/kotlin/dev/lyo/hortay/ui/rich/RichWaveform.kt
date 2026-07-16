package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

/** 5 bits per waveform sample — Telegram's `voiceNote.waveform` packing. */
private const val WAVEFORM_BITS_PER_SAMPLE = 5

/** Max sample value in a 5-bit field. */
private const val WAVEFORM_MAX = 31f

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
 * Static waveform progress track for a voice note. Draws evenly-spaced rounded bars, the
 * played prefix (up to [fraction]) tinted with the accent, the rest muted. Falls back to a
 * flat centred baseline when [bars] is empty (TDLib shipped no waveform).
 */
@Composable
internal fun RichWaveform(
    bars: IntArray,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val count = bars.size
        if (count == 0) {
            val y = size.height / 2f
            drawLine(unplayed, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            return@Canvas
        }
        // Bar geometry: 1/3 of each slot is the gap, so bars read as bars, not a solid block.
        val slot = size.width / count
        val barWidth = max(1f, slot * 0.66f)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedCount = (fraction.coerceIn(0f, 1f) * count).roundToInt()
        for (i in 0 until count) {
            val amp = bars[i] / WAVEFORM_MAX
            // Floor keeps a silent sample visible as a dot rather than vanishing.
            val barHeight = max(2f, amp * size.height)
            val left = i * slot + (slot - barWidth) / 2f
            val top = (size.height - barHeight) / 2f
            drawBar(
                color = if (i < playedCount) played else unplayed,
                left = left,
                top = top,
                width = barWidth,
                height = barHeight,
                radius = radius,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: CornerRadius,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = radius,
    )
}
