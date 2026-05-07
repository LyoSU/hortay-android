package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Compose-side facade over [MediaCache] that bundles the four mandatory operations
 * every TDLib-backed media renderer must perform:
 *
 *   1. **Observe** the file's [MediaState] as a Compose [androidx.compose.runtime.State],
 *      so recomposition fires on download progress / Ready / Failed transitions.
 *   2. **Ensure** keyed on (fileId, priority, scroll-gate, isRemote) so a re-mount
 *      or scroll-settle picks up the download immediately while a mid-fling mount
 *      defers to the gate. The keying tuple matters: a stale tuple would skip
 *      ensure() during the very transition that should rescue a queued cancel.
 *   3. **Cancel** on Composable dispose via [MediaCache.cancelDeferred] so a
 *      scrolled-past slot is released back to TDLib's per-DC download pool.
 *      Web-mode call sites skip this (no slot to release).
 *   4. **Web-mode no-op** shape: when [fileId] is null OR [isRemote] is true the
 *      binding stays in [MediaState.Idle] and the renderer falls through to its
 *      own URL-streaming path (Coil / ExoPlayer HTTP DataSource / LottieUrlStore).
 *
 * Replaces 4-step boilerplate previously duplicated across [TdMediaImage],
 * [TdVideoPlayer], [LottieStickerView], [WebmStickerPlayer] and
 * [CustomEmojiInlineView]. An audit found subtle drift across those copies —
 * each renderer keyed `ensure()` on a slightly different tuple, two renderers
 * tracked their own per-instance `MutableStateFlow(Idle)` web-mode sentinel
 * with slightly different keying, and the four-step contract was held only by
 * convention. Centralising lets one place hold the contract and every renderer
 * inherit it identically; future renderers can't re-derive a subtly-broken
 * variant of the same 25 lines.
 *
 * @param fileId TDLib fileId. Pass `null` when the renderer is in web
 *  (anonymous) mode and intends to fall through to a remote URL.
 * @param priority Initial download priority. Subsequent calls with higher
 *  priority promote the in-flight job in place; lower priorities are no-ops.
 * @param isRemote Whether the parent is in web mode and will use a remote URL
 *  instead of MediaCache. When true, no `ensure` / `cancelDeferred` traffic is
 *  generated and the binding stays at [MediaState.Idle]. Distinct from
 *  `fileId == null` because some call sites have a non-null fileId AND a
 *  remoteUrl (TDLib mode where the post also carries a CDN URL); only the
 *  parent knows which path is canonical for that call.
 */
@Composable
fun rememberMediaBinding(
    fileId: Int?,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    isRemote: Boolean = false,
): MediaBinding {
    val cache = LocalMediaCache.current
    val state by remember(fileId, isRemote) {
        if (fileId != null && !isRemote) cache.observe(fileId)
        else MutableStateFlow(MediaState.Idle)
    }.collectAsStateWithLifecycle()

    // Defensive resync on first mount of this fileId. Asks TDLib *"what is this
    // file, really?"* via [MediaCache.resync] which routes a fresh GetFile
    // through the reducer. Closes the user-described bug "shows not loaded
    // until I scroll away and back": a slot can drift to a stale
    // [MediaState.Downloading] with [activePriority] still set (lost
    // UpdateFile, background-while-completing race, debounced cancel firing
    // right before TDLib's tail Ready event). Without this, [ensure] would
    // short-circuit the stale state on re-mount because `currentPriority >=
    // priority.tdValue`. With this, the GetFile authority answer rides the
    // same FileEvent channel as inbound UpdateFile and the reducer flips the
    // slot to Ready on its own. Cost: one JNI roundtrip on mount per renderer
    // (~10-50 µs), which on a 30-card viewport totals well under a millisecond.
    // No-op in web mode (no MediaCache slot exists).
    LaunchedEffect(fileId, isRemote) {
        if (!isRemote) fileId?.let(cache::resync)
    }

    // Scroll-gate-aware ensure. Re-runs on gate flips so a fling-then-settle
    // landing the user on a new viewport burst-issues ensure() in one frame.
    // The (fileId, priority, gateOpen, isRemote) tuple is the canonical keying
    // that survived several iterations: dropping `gateOpen` made ensure() fire
    // mid-fling and saturate the per-DC pool with cancelled-on-dispose ghost
    // downloads; dropping `priority` masked the in-place upgrade path; dropping
    // `isRemote` made web-mode call sites still hit the cache for a no-op
    // ensure().
    val gate = LocalScrollGate.current
    val gateOpen = gate.value
    LaunchedEffect(fileId, priority, gateOpen, isRemote) {
        if (gateOpen && !isRemote) fileId?.let { cache.ensure(it, priority) }
    }
    DisposableEffect(fileId, isRemote) {
        onDispose { if (!isRemote) fileId?.let(cache::cancelDeferred) }
    }

    return remember(state, fileId, isRemote, cache) {
        MediaBinding(state = state, fileId = fileId, isRemote = isRemote, cache = cache)
    }
}

/**
 * Stable handle returned by [rememberMediaBinding]. Exposes the observed
 * [MediaState] plus convenience accessors for the Ready path and user-initiated
 * cancel / retry / invalidate actions. Renderers receive this instead of the
 * raw `MediaCache` reference: the smaller surface keeps per-fileId scope
 * explicit (no accidental cross-fileId mutations) and lets the binding own the
 * `fileId == null` short-circuiting once instead of at every call site.
 *
 * `@Immutable` because every field is read-only and the whole handle gets
 * rebuilt by [rememberMediaBinding] on state change — Compose's stability
 * inference can correctly skip recomposition of any reader that doesn't
 * depend on this particular handle.
 */
@Immutable
class MediaBinding internal constructor(
    val state: MediaState,
    val fileId: Int?,
    val isRemote: Boolean,
    private val cache: MediaCache,
) {
    /** Convenience: file is on disk and the path is non-empty. */
    val isReady: Boolean get() = state is MediaState.Ready

    /**
     * The on-disk path TDLib reports for this file, or null if the slot is
     * not in [MediaState.Ready] or the path is empty (TDLib's transient
     * "completed but not yet renamed" snapshot). Use this as the keying
     * value for renderer side-effects (Coil request build, ExoPlayer
     * setMediaItem, Lottie composition load) so that the rename-race's
     * empty-path snapshot doesn't trigger a doomed prepare.
     */
    val readyPath: String? get() = (state as? MediaState.Ready)?.path?.takeIf { it.isNotEmpty() }

    /**
     * User-initiated cancel: forces the slot to [MediaState.Idle] immediately,
     * bypassing the debounce window and observer-count guard. Call from
     * tap-to-cancel affordances on loading overlays.
     */
    fun cancelExplicit() {
        fileId?.let(cache::cancelExplicit)
    }

    /**
     * Force a retry on a [MediaState.Failed] slot. Suspends until [MediaCache]
     * has accepted the new ensure(); the resulting state transition arrives
     * via the bound [state] flow.
     */
    suspend fun retry(priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        fileId?.let { cache.retry(it, priority) }
    }

    /**
     * Invalidate a [MediaState.Ready] slot whose on-disk file has gone missing.
     * Wires straight to [MediaCache.invalidate] which routes the ResetIfReady
     * event through the single-writer reducer — race-safe against concurrent
     * UpdateFile drains. Call from Coil / ExoPlayer error listeners when the
     * Ready path turned out to be unreadable (TDLib storage optimiser
     * silently evicted it; tdlib/td#3178).
     */
    fun invalidate(priority: DownloadPriority = DownloadPriority.VisibleMedia) {
        fileId?.let { cache.invalidate(it, priority) }
    }
}
