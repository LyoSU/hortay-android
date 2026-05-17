package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Process-wide polymorphic back-stack for the drill-overlay surfaces.
 *
 * Replaces three previously-disjoint navigation states:
 *   1. `channelStack: List<Long>` — channel-drill overlay
 *   2. `commentsForPost` / `pendingCommentsKey` — comments ModalBottomSheet
 *   3. `pendingScrollTarget` — orthogonal deep-link scroll target
 *
 * Each push is a fresh layer; no dedup on repeated chatIds. Permits unlimited
 * nesting: channel → comments → channel → comments → … (the Telegram-Android
 * pattern, where every drill is its own back-stack entry).
 *
 * `entryId` is a stable per-instance UUID used as the key for
 * [androidx.compose.runtime.saveable.SaveableStateProvider] and `viewModel(key)`
 * so each entry has an isolated saveable state bag and a dedicated ViewModel —
 * pushing the same channel twice produces two distinct screens with their own
 * scroll positions and view-models. This also fixes the per-chatId
 * ViewModelStore leak (previously, [androidx.lifecycle.viewmodel.compose.viewModel]
 * keyed on "channel:$chatId" accumulated VMs for the whole Activity lifetime).
 *
 * Lifetime: held by [dev.lyo.hortay.AppGraph]; cleared on logout via the
 * graph's `runLogoutCleanup`. Not saveable across process death — same
 * boundary [LinkDialogState] and [dev.lyo.hortay.data.report.ReportDialogState]
 * draw: a killed process represents user abandonment of the drill path, not
 * pending intent.
 */
@Immutable
sealed interface NavEntry {

    val entryId: String

    /**
     * Single-channel drill. `scrollToMessageId` lets a deep-link
     * `t.me/<chan>/<msg>` push the channel pre-targeted at a specific post;
     * the screen consumes it once and scrolls on first composition.
     *
     * [preloadTimedOut] is set by the push-site when a "wait-for-content"
     * preload exceeded its grace window. When true, [ChannelScreen] paints
     * the skeleton immediately instead of running the standard
     * `rememberDeferredLoading` grace — the user already waited the
     * push-side grace, so an additional grace inside the screen would
     * stack two waits ("blocked source view, then blank target view"). When
     * false, the channel slice is already warm on mount and the standard
     * grace applies for residual cases (e.g. deep-link around-load still
     * resolving the exact target row).
     */
    @Immutable
    data class Channel(
        val chatId: Long,
        val scrollToMessageId: Long? = null,
        val preloadTimedOut: Boolean = false,
        override val entryId: String = UUID.randomUUID().toString(),
    ) : NavEntry

    /**
     * Comments thread anchored at the given feed post. The anchor carries
     * `chatId` + `id` (the message id used as discussion-thread root by
     * TDLib `GetMessageThread`).
     */
    @Immutable
    data class Comments(
        val anchor: TimelinePost,
        override val entryId: String = UUID.randomUUID().toString(),
    ) : NavEntry

    /**
     * Guest-mode single-channel drill. Identified by t.me/s/ handle rather
     * than a TDLib chatId — guest mode has no TDLib session to mint chatIds.
     * Only consumed by [dev.lyo.hortay.ui.web.WebModeScaffold]; auth-mode
     * [dev.lyo.hortay.ui.main.MainScaffold] never sees this variant because
     * the two scaffolds never compose simultaneously
     * ([dev.lyo.hortay.MainActivity] routes between them).
     */
    @Immutable
    data class WebChannel(
        val username: String,
        override val entryId: String = UUID.randomUUID().toString(),
    ) : NavEntry
}

class NavStack {

    private val _stack = MutableStateFlow<PersistentList<NavEntry>>(persistentListOf())

    val stack: StateFlow<PersistentList<NavEntry>> = _stack.asStateFlow()

    val top: NavEntry? get() = _stack.value.lastOrNull()

    fun push(entry: NavEntry) {
        _stack.update { it.add(entry) }
    }

    /** Pop the top entry. Returns it, or null if the stack is already empty. */
    fun pop(): NavEntry? {
        val current = _stack.value
        if (current.isEmpty()) return null
        val top = current.last()
        _stack.value = current.removeAt(current.lastIndex)
        return top
    }

    /**
     * Atomically pop the top entry and push [entry] in its place. Single
     * [_stack] update = single emission to subscribers — no transient
     * `stack.size - 1` intermediate state where the "below" entry briefly
     * becomes the top (which would otherwise fire a recomposition with the
     * top-entry graphicsLayer modifier flipping on, the layer underneath
     * unmounting / re-mounting because [MainScaffold.visibleEntries] takes
     * the last 2 items, and any per-`isTop` side effects firing twice).
     *
     * Used by "go to original" affordances on overlay surfaces: tapping a
     * reply quote card / a channel name / an author chat in a Comments
     * overlay drills into that destination instead of stacking it on top of
     * the conversation the user just finished reading. Telegram-X / Twitter
     * / Reddit all treat the reply-card tap as a NAVIGATION (replaces the
     * current detail view), not a stacked drill — Hortay follows the same
     * idiom here.
     *
     * If the stack is empty, falls back to a plain push so the call is
     * defensive against unusual call-site state.
     */
    fun replaceTop(entry: NavEntry) {
        _stack.update { current ->
            if (current.isEmpty()) current.add(entry)
            else current.removeAt(current.lastIndex).add(entry)
        }
    }

    fun clear() {
        _stack.value = persistentListOf()
    }
}
