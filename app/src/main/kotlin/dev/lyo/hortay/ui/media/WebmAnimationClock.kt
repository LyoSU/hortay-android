package dev.lyo.hortay.ui.media

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis

/** One time source shared by every on-screen WebM animation. Composables read [nowMs] and compute
 *  their own frame via DecodedWebm.frameAt; a single frame loop drives them all, so N inline emoji
 *  cost N cheap draws off one clock — not N players. Mount once high in the tree:
 *  `LaunchedEffect(clock) { clock.run() }`. */
class WebmAnimationClock {
    private val _nowMs = mutableLongStateOf(0L)
    val nowMs: Long get() = _nowMs.longValue
    suspend fun run() { while (true) { withFrameMillis { _nowMs.longValue = it } } }
}

val LocalWebmClock = staticCompositionLocalOf { WebmAnimationClock() }
