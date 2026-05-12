// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lyo.hortay.R
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.data.report.ReportState
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full reporting flow rendered inside a [ModalBottomSheet].
 *
 * Gate: if the user has never seen the one-time explainer (stored in
 * [ReportExplainerStore]), shows [ReportAboutDialog] first. On its dismissal the
 * explainer flag is persisted and the sheet body opens.
 *
 * The ViewModel is keyed on (chatId, messageId) so a reopened report on the same
 * post restores progress across recompositions and configuration changes.
 *
 * Why [produceState] for the explainer check: DataStore reads are suspending and
 * must not block the first composition frame. [produceState] suspends in the
 * background while the UI renders [LoadingContent] as the initial value; the
 * gate flips to the real value on the next recomposition.
 */
@Composable
fun ReportFlowSheet(
    chatId: Long,
    messageId: Long?,
    @Suppress("UNUSED_PARAMETER") channelUsername: String?,
    onDismiss: () -> Unit,
    reportRepository: ReportRepository,
    explainerStore: ReportExplainerStore,
) {
    val scope = rememberCoroutineScope()

    // true = already shown (skip explainer), false = must show explainer first.
    // Initial value = true (optimistic: skip explainer until we know otherwise).
    val explainerShown by produceState(initialValue = true) {
        value = explainerStore.shown.first()
    }

    var explainerDismissed by remember { mutableStateOf(false) }
    val showExplainer = !explainerShown && !explainerDismissed

    if (showExplainer) {
        ReportAboutDialog(
            onConfirm = {
                scope.launch { runCatching { explainerStore.markShown() } }
                explainerDismissed = true
            },
        )
        // Don't render the bottom sheet until the explainer is dismissed.
        return
    }

    val vmKey = "report:$chatId:${messageId ?: 0}"
    val vm: ReportFlowViewModel = viewModel(
        key = vmKey,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ReportFlowViewModel(reportRepository, chatId, messageId) as T
        },
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by vm.state.collectAsState()

    // Auto-dismiss on Success.
    LaunchedEffect(state) {
        if (state is ReportState.Success) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            when (val s = state) {
                is ReportState.Idle, is ReportState.Loading -> LoadingContent()
                is ReportState.OptionSelection -> OptionSelectionContent(
                    title = s.title,
                    options = s.options,
                    onSelect = { vm.selectOption(it) },
                )
                is ReportState.TextRequired -> TextRequiredContent(
                    isOptional = s.isOptional,
                    onSubmit = { text -> vm.submitText(text) },
                    onSkip = if (s.isOptional) ({ vm.submitText("") }) else null,
                )
                is ReportState.Success -> {
                    // LaunchedEffect above handles dismiss; brief success text in case
                    // of a recomposition between the state flip and the dismiss.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.report_success),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is ReportState.Error -> ErrorContent(
                    message = s.message,
                    onRetry = { vm.retry() },
                )
                is ReportState.FloodWait -> FloodWaitContent(seconds = s.retryAfterSeconds)
            }
        }
    }
}

// ---- Sub-composables -------------------------------------------------------

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator(modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.report_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionSelectionContent(
    title: String,
    options: List<org.drinkless.tdlib.TdApi.ReportOption>,
    onSelect: (org.drinkless.tdlib.TdApi.ReportOption) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    for (option in options) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onSelect(option) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Symbol(
                name = "chevron_right",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun TextRequiredContent(
    isOptional: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: (() -> Unit)?,
) {
    var text by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.report_text_hint),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= 1024) text = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 6,
        placeholder = {
            Text(
                text = stringResource(R.string.report_text_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    Spacer(Modifier.height(16.dp))
    Row {
        if (onSkip != null) {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.report_skip))
            }
            Spacer(Modifier.width(8.dp))
        }
        FilledTonalButton(
            onClick = { onSubmit(text) },
            enabled = isOptional || text.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.report_submit))
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "error",
            tint = MaterialTheme.colorScheme.error,
            size = 40.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) {
            Text(stringResource(R.string.report_retry))
        }
    }
}

@Composable
private fun FloodWaitContent(seconds: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "hourglass_empty",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 40.dp,
        )
        Spacer(Modifier.height(12.dp))
        val message = if (seconds > 0) {
            pluralStringResource(R.plurals.report_flood_wait_plural, seconds, seconds)
        } else {
            stringResource(R.string.report_flood_wait_generic)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
