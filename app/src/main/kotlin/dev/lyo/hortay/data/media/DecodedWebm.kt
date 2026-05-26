package dev.lyo.hortay.data.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Frame index for a looping playback of [delaysMs] at [elapsedMs]. Pure; unit-tested. */
fun frameIndexFor(delaysMs: IntArray, elapsedMs: Long): Int {
    if (delaysMs.size <= 1) return 0
    val total = delaysMs.sum().coerceAtLeast(1)
    var t = (elapsedMs % total).toInt()
    for (i in delaysMs.indices) { t -= delaysMs[i]; if (t < 0) return i }
    return delaysMs.lastIndex
}

/** A fully-decoded short WebM loop, ready to draw. [frames] and [delaysMs] are 1:1. */
@Immutable
class DecodedWebm(
    val frames: List<ImageBitmap>,
    val delaysMs: IntArray,
    val width: Int,
    val height: Int,
) {
    fun frameAt(elapsedMs: Long): Int =
        if (frames.isEmpty()) 0 else frameIndexFor(delaysMs, elapsedMs).coerceIn(0, frames.lastIndex)
}
