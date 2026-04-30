package dev.lyo.hortay.ui.media

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-list "is it OK to start downloads right now?" gate, propagated via composition so
 * media composables don't need to know about [androidx.compose.foundation.lazy.LazyListState]
 * directly. `true` = ensure freely, `false` = the host is mid-scroll, defer until settle.
 *
 * Why this exists: a tap on "scroll to top" sweeps `animateScrollToItem(0)` past dozens of
 * intermediate posts in 500-1000 ms. With no gate, every transitory mount fires
 * [dev.lyo.hortay.data.MediaCache.ensure] → TDLib enqueues a download → the post unmounts →
 * `cancelDeferred` schedules a 250 ms-debounced cancel. By the time the user lands at the
 * top, TDLib's 4-slot pool is saturated with intermediate-post downloads about to be
 * cancelled, while the genuinely-visible top posts wait their turn. Telegram-Android avoids
 * this with `RecyclerView.SCROLL_STATE_IDLE`-gated `loadFile` — same idea, different shape.
 *
 * The default (provider not installed) is permanently `true`, which preserves pre-gate
 * behavior for any composable that doesn't sit under a list providing this local. This
 * matters for fullscreen viewers, channel info sheets, and other static surfaces — they
 * should continue to ensure unconditionally.
 *
 * `staticCompositionLocalOf` is correct here for the same reason as the rest of the
 * `Local*` family in this package: the *State reference* we provide is stable across
 * recompositions (a `derivedStateOf` wrapped in `remember(listState)`), and the
 * actual `true ↔ false` transitions propagate to readers via Compose's snapshot
 * tracking on `State.value` — independent of the local's flavor. Static avoids the
 * per-reader bookkeeping the non-static variant carries.
 */
val LocalScrollGate = staticCompositionLocalOf<State<Boolean>> { AlwaysOpen }

private val AlwaysOpen = mutableStateOf(true)
