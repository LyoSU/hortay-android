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

/**
 * Per-feed "is this item the deep-link / quote-tap scroll target right now?" flag.
 * Drives the brief surface-tint highlight on the linked-to PostCard (Telegram-iOS
 * idiom — a primary-container glow on the bubble the user just jumped to, so they
 * can locate it after the auto-scroll). Default `false` for any reader outside a
 * feed that explicitly threads a highlight signal.
 *
 * Static is fine for the same reason as [LocalIsCenteredItem] — value changes are
 * cheap booleans and reader-side recomposition is bounded to the PostCard.
 */
val LocalIsHighlightedItem = staticCompositionLocalOf<Boolean> { false }

/**
 * "Is the media under this subtree passive (observe-only)?" flag. `true` for the
 * body of a deleted-post tombstone, `false` everywhere else.
 *
 * Why this exists: a deleted post keeps its original [dev.lyo.hortay.data.PostContent]
 * (with valid TDLib `fileId`s) so the feed can still show what was there — but TDLib
 * has removed the message server-side, and deleted-message media is *not* recoverable
 * through TDLib (Lev Lam, [tdlib/td#3493](https://github.com/tdlib/td/issues/3493);
 * see also [dev.lyo.hortay.data.archive.ArchivedMediaStore]). Issuing
 * [dev.lyo.hortay.data.MediaCache.ensure] for those files spins a doomed download
 * through the single-writer reducer, and the viewport-centre promotion
 * ([LocalIsCenteredItem]) makes it jump the queue *ahead* of live posts on the tight
 * per-DC pool — so scrolling past a tombstone starves the cards the user is actually
 * looking at. That was the user-reported "scroll hangs / everything loads slowly near
 * deleted posts" symptom.
 *
 * When `true`, [dev.lyo.hortay.ui.media.rememberMediaBinding] degrades to *observe-only*:
 * it still reports the file's [dev.lyo.hortay.data.MediaState] (so a slot that happens
 * to be on disk renders), but issues no `ensure` / `resync` / `cancelDeferred` and skips
 * the centre-of-viewport priority bump. Renderers additionally suppress download/retry
 * affordances and inline autoplay, falling back to the inline minithumb as a blurred
 * preview. The contract: *a tombstone never initiates network I/O.*
 *
 * Scoped to the post body only — [dev.lyo.hortay.ui.timeline.PostCard] provides it around
 * [dev.lyo.hortay.ui.timeline.PostBody], leaving the channel avatar (small, shared across
 * the channel's live posts, already cached) on the normal download path.
 *
 * Static for the same reason as [LocalIsCenteredItem]/[LocalIsHighlightedItem]: the value
 * is a cheap per-card boolean and reader-side recomposition is bounded to one card.
 */
val LocalMediaPassive = staticCompositionLocalOf<Boolean> { false }
