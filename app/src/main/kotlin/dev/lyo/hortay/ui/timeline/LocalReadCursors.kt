package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.ReadCursors
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.Flow

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
     * Diff-apply an upstream [ReadCursors] emission. Only entries whose
     * value actually changed are written through to the
     * [SnapshotStateMap], so unaffected readers are not invalidated.
     */
    internal fun apply(upstream: ReadCursors) {
        for ((k, v) in upstream) {
            if (map[k] != v) map[k] = v
        }
        // Two-step removal: collect first, mutate second. Iterating
        // `map.keys` while removing would throw ConcurrentModification.
        val toRemove = map.keys.filter { it !in upstream }
        for (k in toRemove) map.remove(k)
    }
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
 * enclosing composition. The holder is `remember`-allocated once; each
 * upstream emission is diff-applied so per-key Compose snapshot
 * subscribers are invalidated only when their own chat's cursor changes.
 */
@Composable
fun rememberCursorHolder(flow: Flow<ReadCursors>): CursorHolder {
    val holder = remember { CursorHolder() }
    LaunchedEffect(holder, flow) {
        flow.collect { holder.apply(it) }
    }
    return holder
}
