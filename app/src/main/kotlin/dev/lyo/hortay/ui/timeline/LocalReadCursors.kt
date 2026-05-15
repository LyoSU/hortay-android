package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.repeatOnLifecycle
import dev.lyo.hortay.data.ReadCursors
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * Live per-chat cursor holder propagated via composition. Backed by a
 * [SnapshotStateMap], so each `holder[chatId]` read registers a Compose
 * snapshot dependency on *that key alone* — an `UpdateChatReadInbox` for
 * chat Y does not invalidate PostCard X.
 *
 * Why a holder class (and not the raw map): provided via
 * [staticCompositionLocalOf], which requires a stable identity for its
 * value — the SnapshotStateMap inside is mutated in place by [apply], so
 * the CompositionLocal value reference never changes and no subtree-wide
 * recomposition is triggered by cursor traffic. The previous
 * `staticCompositionLocalOf<ReadCursors>` provider swapped a fresh
 * PersistentMap identity on every put, which invalidated the entire
 * MainScaffold subtree (including the LazyColumn) for every dwell-ack and
 * external read sync.
 *
 * Snapshot-style consumers (UnreadBoundaryRow latch, ChannelUiState anchor
 * picker, TimelineUiState.frozenCursors) call [snapshot] to freeze the
 * current entries into an immutable [ReadCursors] without subscribing to
 * future puts; the freeze escapes Compose snapshot read tracking so the
 * call site does not re-run on subsequent cursor advances.
 *
 * Stability: marked `@Stable` because the holder identity is stable across
 * recompositions and observable mutations flow through Compose's snapshot
 * machinery via the underlying [SnapshotStateMap]. Both the per-key read
 * path (`holder[chatId]`) and the snapshot path ([snapshot]) preserve
 * Compose's skippability contract for downstream composables that accept
 * the holder as a parameter.
 */
@Stable
class CursorHolder internal constructor() {
    internal val map: SnapshotStateMap<Long, Long> = mutableStateMapOf()

    /**
     * Per-key snapshot-tracked read. The Compose snapshot system registers
     * a dependency on this specific key only, so a downstream
     * recomposition fires iff the cursor *for this chat* changes value.
     */
    operator fun get(chatId: Long): Long? = map[chatId]

    /**
     * True when the holder has received at least one entry. Used as the
     * cold-start "cursors are usable for the boundary picker" signal,
     * mirroring the previous `cursorsState.value.isNotEmpty()` check. The
     * read on [SnapshotStateMap.isNotEmpty] is snapshot-tracked, so a
     * `snapshotFlow { holder.cursorsHaveLanded }.first { it }` settles in
     * the same composition pass the first put lands.
     */
    val cursorsHaveLanded: Boolean get() = map.isNotEmpty()

    /**
     * Frozen [ReadCursors] snapshot for boundary computations that must
     * NOT migrate under live cursor advances (UnreadBoundaryRow,
     * ChannelUiState anchor). The body reads the map under
     * [Snapshot.withoutReadObservation] so the calling composable / effect
     * does not register dependencies on the underlying entries — the
     * returned PersistentMap is captured at the call moment and explicitly
     * does not react to subsequent puts. Latch sites that need to refresh
     * the snapshot must re-invoke [snapshot] at their explicit trigger
     * (cold-start landing, pull-to-refresh completion).
     */
    fun snapshot(): ReadCursors =
        Snapshot.withoutReadObservation { map.toMap().toPersistentMap() }

    /**
     * Apply a pre-computed delta patch. The diff (O(N) over the upstream
     * map) is intentionally computed *off main* by [rememberCursorHolder]
     * before reaching this method, so the only work that lands on the UI
     * thread is the SnapshotStateMap mutation itself (typically a single
     * `put` for the common case — `_chatReadCursors.update` is called
     * once per `UpdateChatReadInbox`).
     */
    internal fun applyChanges(changes: List<CursorChange>) {
        for (c in changes) when (c) {
            is CursorChange.Put ->
                if (map[c.chatId] != c.cursor) map[c.chatId] = c.cursor
            is CursorChange.Remove ->
                map.remove(c.chatId)
        }
    }
}

/**
 * Off-main delta computation output: either a put (new value for a chat)
 * or a remove (chat absent from the new upstream map). Batched into a
 * `List<CursorChange>` per upstream emission so the main-thread apply
 * pass iterates a small list (typically size 1) without re-entering the
 * Flow context.
 */
internal sealed class CursorChange {
    data class Put(val chatId: Long, val cursor: Long) : CursorChange()
    data class Remove(val chatId: Long) : CursorChange()
}

private val EmptyCursorHolder = CursorHolder()

/**
 * Process-wide cursor holder, installed by [dev.lyo.hortay.ui.main.MainScaffold]
 * (TDLib mode) and [dev.lyo.hortay.ui.web.WebModeScaffold] (guest mode).
 * The default is an empty holder — safe fallback for surfaces that don't
 * sit under a scaffold provider (auth screen, link-preview dialog) and for
 * the cold-start race where the scaffold hasn't yet collected its first
 * emission.
 */
val LocalReadCursors = staticCompositionLocalOf<CursorHolder> { EmptyCursorHolder }

/**
 * Collect [flow] into a stable [CursorHolder] for the lifetime of the
 * enclosing composition. The holder is `remember`-allocated once.
 *
 * **Off-main delta computation.** [PostsRepository.chatReadCursors] and
 * [WebRepository.observeReadCursors] are `MutableStateFlow<ReadCursors>`
 * that get a *new* PersistentMap on every put (`_chatReadCursors.update {
 * current.put(...) }`), so the flow emits full-map snapshots even though
 * each TDLib `UpdateChatReadInbox` typically changes only one entry.
 * Diffing those snapshots back to per-key deltas is O(N) in the upstream
 * map size — doing it on the UI thread (as the previous version did)
 * would add a small but real per-emission cost to scroll-time frames
 * when external acks land in bursts.
 *
 * Pipeline:
 *   1. `.scan(...)` keeps the previous map in the operator state and
 *      computes the delta list (Put/Remove). Tiny — usually 1 entry.
 *   2. `.flowOn(Dispatchers.Default)` moves steps 1 + diff allocation
 *      off the UI thread. Upstream collection happens there too.
 *   3. `.collect { applyChanges(it) }` lands on the LaunchedEffect's
 *      main dispatcher and writes the tiny patch into the
 *      SnapshotStateMap — the only main-thread work per emission.
 *
 * No data-layer API change needed: the diff is reconstructed entirely on
 * the consumer side by carrying the previous emission in the scan
 * accumulator (PersistentMap structural sharing means the previous-ref
 * carry is essentially free).
 *
 * Collection is lifecycle-gated to [Lifecycle.State.STARTED] — when the
 * host Activity goes to STOPPED (backgrounded, screen off), the upstream
 * flow is unsubscribed and resumes on the next foreground. Matches the
 * old `collectAsStateWithLifecycle()` behaviour that the previous
 * `staticCompositionLocalOf<ReadCursors>` provider relied on; the
 * MainScaffold / WebModeScaffold composables can stay in composition
 * while backgrounded, but cursor traffic doesn't.
 */
@Composable
fun rememberCursorHolder(flow: Flow<ReadCursors>): CursorHolder {
    val holder = remember { CursorHolder() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(holder, flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val seed: ReadCursors = persistentMapOf()
            flow
                .scan<ReadCursors, Pair<ReadCursors, List<CursorChange>>>(
                    seed to emptyList(),
                ) { acc, curr ->
                    val (prev, _) = acc
                    // PersistentMap put returns a new root identity, so
                    // structural identity here means "no change at all"
                    // — the flow re-emitted the same instance (rare but
                    // free to short-circuit).
                    if (prev === curr) return@scan curr to emptyList()
                    val changes = buildList<CursorChange> {
                        for ((k, v) in curr) {
                            if (prev[k] != v) add(CursorChange.Put(k, v))
                        }
                        for (k in prev.keys) {
                            if (k !in curr) add(CursorChange.Remove(k))
                        }
                    }
                    curr to changes
                }
                .drop(1)  // discard seed emission
                .map { it.second }
                .filter { it.isNotEmpty() }
                .flowOn(Dispatchers.Default)
                .collect { changes -> holder.applyChanges(changes) }
        }
    }
    return holder
}
