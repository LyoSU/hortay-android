// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.data.report.ReportState
import dev.lyo.hortay.data.report.ReportStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Composable-scoped ViewModel driving the multi-step TDLib ReportChat flow.
 *
 * Instantiated via `viewModel(key = "report:$chatId:$messageId")` so each
 * (chat, message) pair gets independent state — reopening a report on the same
 * post restores progress. The ViewModel survives rotation; the Composable layer
 * only needs to observe [state].
 *
 * Why a ViewModel here rather than hoisting state into the Composable: the TDLib
 * calls are suspending and may span multiple recompositions (user picks an option,
 * server round-trip, new option list arrives). ViewModelScope outlives a single
 * composition; a `rememberCoroutineScope` call would be cancelled on every
 * configuration change.
 *
 * Thread safety: [_state] is only written from [viewModelScope] (single-threaded
 * Dispatchers.Main.immediate for StateFlow ops), so no additional synchronisation
 * is needed.
 */
class ReportFlowViewModel(
    private val repo: ReportRepository,
    private val chatId: Long,
    private val messageId: Long?,
) : ViewModel() {

    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    /**
     * Current optionId carried forward from the last [ReportState.TextRequired]
     * transition. Stored here so [submitText] can attach it without the Composable
     * layer needing to hold raw [ByteArray] state.
     *
     * NOTE: per spec, do NOT persist this across sessions — it is server-state that
     * must not survive a process restart or a new auth session.
     */
    private var pendingOptionId: ByteArray = byteArrayOf()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            _state.value = ReportState.Loading
            apply(repo.start(chatId, messageId))
        }
    }

    fun selectOption(option: TdApi.ReportOption) {
        viewModelScope.launch {
            _state.value = ReportState.Loading
            apply(repo.selectOption(chatId, messageId, option))
        }
    }

    /**
     * Submit user text (or empty string when text is optional and the user tapped Skip).
     * Attaches [pendingOptionId] captured from the previous [ReportState.TextRequired] step.
     */
    fun submitText(text: String) {
        viewModelScope.launch {
            _state.value = ReportState.Loading
            apply(repo.submitText(chatId, messageId, pendingOptionId, text))
        }
    }

    fun retry() {
        start()
    }

    private fun apply(step: ReportStep) {
        step.pendingOptionId?.let { pendingOptionId = it }
        _state.value = step.state
    }
}
