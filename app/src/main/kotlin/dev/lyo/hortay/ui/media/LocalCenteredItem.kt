package dev.lyo.hortay.ui.media

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-feed "is this item the one closest to the viewport centre?" flag, propagated
 * via composition so media renderers don't need to know about
 * [androidx.compose.foundation.lazy.LazyListState] directly. `true` for the single
 * item whose centre is closest to the visible viewport centre, `false` for every
 * other item including off-centre visible cards.
 *
 * Why this exists: TDLib serves only ~4 simultaneous downloads per DC on
 * [org.drinkless.tdlib.TdApi.NetworkTypeMobile] (per
 * [tdlib/td#786](https://github.com/tdlib/td/issues/786)) and same-priority files
 * are served in LIFO order. With every visible card issuing
 * [dev.lyo.hortay.data.MediaCache.ensure] at
 * [dev.lyo.hortay.data.DownloadPriority.VisibleMedia] (16), 3 simultaneously-on-
 * screen cards on a 4-slot pool put the dominant card in a coin-flip race against
 * its neighbours. Telegram-Android handles this by *priority decay from viewport
 * centre*: the most-centred cell gets the highest priority, edges fall to lower
 * priorities. We approximate the same with a binary split — centre gets
 * [dev.lyo.hortay.data.DownloadPriority.VisibleCenter] (24), edges keep
 * [dev.lyo.hortay.data.DownloadPriority.VisibleMedia] (16) — which is enough to
 * guarantee TDLib's priority-aware scheduler always serves the centre card first
 * regardless of LIFO ordering inside lane 16.
 *
 * The default (provider not installed) is permanently `false`, which preserves
 * pre-feature behaviour for any renderer outside a list that explicitly marks a
 * centre. Standalone surfaces — fullscreen viewer, channel info sheet, comments
 * thread — keep their existing priority semantics.
 *
 * `staticCompositionLocalOf` is correct here for the same reason as
 * [LocalScrollGate]: the *State reference* we provide is stable across
 * recompositions (Compose-driven `mutableStateOf` per-item), and the actual
 * `true ↔ false` transitions propagate to readers via snapshot tracking on
 * `State.value` independently of the local's flavour. Static avoids the per-
 * reader bookkeeping the non-static variant carries.
 */
val LocalIsCenteredItem = staticCompositionLocalOf<State<Boolean>> { NeverCentered }

private val NeverCentered = mutableStateOf(false)
