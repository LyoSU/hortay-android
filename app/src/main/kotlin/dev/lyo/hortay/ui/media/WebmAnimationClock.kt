package dev.lyo.hortay.ui.media

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One time source shared by every on-screen WebM animation. Composables read [nowMs] in their draw
 *  phase and compute their own frame via DecodedWebm.frameAt; a single frame loop drives them all,
 *  so N inline emoji cost N cheap draws off one clock — not N players. Mount once high in the tree:
 *  `LaunchedEffect(clock) { clock.run() }`. The loop only ticks while at least one animating
 *  composable holds a [acquire]d subscription, so an idle session costs no frame callbacks. */
class WebmAnimationClock {
    private val _nowMs = mutableLongStateOf(0L)
    private val subscribers = MutableStateFlow(0)
    val nowMs: Long get() = _nowMs.longValue

    fun acquire() { subscribers.value += 1 }

    fun release() { subscribers.value -= 1 }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        subscribers.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
            if (active) {
                while (true) { withFrameMillis { _nowMs.longValue = it } }
            }
        }
    }
}

val LocalWebmClock = staticCompositionLocalOf { WebmAnimationClock() }
