package dev.lyo.hortay.data.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-open report target. The `token` is a monotonic timestamp that lets
 * `ReportFlowSheet`'s `viewModel(key = …)` create a FRESH ViewModel instance
 * each time the user taps Report — otherwise the Activity-scoped ViewModelStore
 * caches the prior session's terminal state (Success / Error / FloodWait), and
 * reopening the sheet on the same (chatId, messageId) auto-dismisses or shows
 * stale errors. New tap → new `nanoTime` → distinct key → clean state machine.
 */
data class ReportTarget(val chatId: Long, val messageId: Long?, val token: Long)

/**
 * Process-wide holder for the in-app report flow target. Hoisted off
 * `MainScaffold`'s local `remember { mutableStateOf(...) }` for the same reason
 * [dev.lyo.hortay.data.LinkDialogState] is hoisted: a mid-flow report can
 * outlive a single composition (rotation, configuration change, brief
 * recreation) and the bottom sheet's TDLib roundtrips are even more
 * sensitive to losing in-progress state than a one-shot link dialog —
 * `ReportFlowViewModel` keeps partial answers, and re-creating the sheet
 * from null erases the user's progress visibly.
 *
 * Survives rotation; explicitly cleared on logout via the fan-out in
 * [dev.lyo.hortay.AppGraph.runLogoutCleanup]. Process-death is NOT preserved —
 * a killed mid-flow report is treated as user abandonment, matching the
 * boundary set by [dev.lyo.hortay.data.LinkDialogState].
 */
class ReportDialogState {

    private val _target = MutableStateFlow<ReportTarget?>(null)
    val target: StateFlow<ReportTarget?> = _target.asStateFlow()

    fun open(t: ReportTarget) { _target.value = t }
    fun close() { _target.value = null }
}
