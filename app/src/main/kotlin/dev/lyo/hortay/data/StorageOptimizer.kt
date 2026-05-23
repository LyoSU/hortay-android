package dev.lyo.hortay.data

import dev.lyo.hortay.data.archive.ArchiveSweep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Runs [TdClient.maybeOptimizeStorage] and [ArchiveSweep.run] only after the
 * post-auth storm window has settled (`StartupCoordinator.Phase.Active`) AND
 * the user is foregrounded.
 *
 * Why this is its own component, not a few lines in [TdLifecycleBridge]:
 *   - `maybeOptimizeStorage` is non-interactive housekeeping. Running it
 *     during `goOnline()` (the previous shape) put a `GetStorageStatisticsFast`
 *     JNI hop + a potential file-table walk on TDLib's RPC queue alongside
 *     the cold-start `LoadChats(ChatListMain)` burst. The probe is cheap,
 *     but it still competes with the very work the user is waiting on.
 *   - `StartupCoordinator` already owns the "first storm has drained" signal.
 *     This wire-up is what that signal is for: speculative work gated behind
 *     [StartupCoordinator.Phase.Active].
 *
 * Trigger semantics: edge-triggered when (active && foreground) flips to true.
 * The optimiser itself is idempotent (skips when within the 24h timer AND
 * under the 80%-of-cap threshold), so re-firing on every transition is fine.
 *
 * [archiveSweep] is nullable so existing test constructors that omit it
 * continue to compile without changes.
 */
class StorageOptimizer(
    private val td: TdClient,
    startupPhase: StateFlow<StartupCoordinator.Phase>,
    foreground: StateFlow<Boolean>,
    scope: CoroutineScope,
    private val archiveSweep: ArchiveSweep? = null,
) {
    init {
        combine(startupPhase, foreground) { phase, fg ->
            phase == StartupCoordinator.Phase.Active && fg
        }
            .distinctUntilChanged()
            .filter { it }
            .onEach {
                td.maybeOptimizeStorage()
                runCatching { archiveSweep?.run() }
            }
            .launchIn(scope)
    }
}
