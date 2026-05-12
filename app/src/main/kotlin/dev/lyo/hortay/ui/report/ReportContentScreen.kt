// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.report

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/** Regex matching valid Telegram handles: 2–32 alphanumeric + underscore characters. */
private val HANDLE_REGEX = Regex("^[a-zA-Z0-9_]{2,32}$")

/**
 * Simple form screen for reporting a channel by handle (from Settings → Safety →
 * Report content). The user types an @-handle and an optional post ID, then taps
 * "Continue".
 *
 * In authenticated mode: resolves the handle to a chatId via [onResolveHandle] and
 * opens [ReportFlowSheet].
 * In guest mode: [onResolveHandle] is null; the callback [onGuestReport] is used
 * instead, which delegates via [GuestReportDelegator].
 *
 * Why a separate screen rather than a bottom sheet: the user comes here via Settings,
 * not from a long-press on a post. The field + validation fits a full screen naturally
 * (keyboard + potential error text); a sheet would feel cramped.
 */
@Composable
fun ReportContentScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** Auth mode: resolve handle → (chatId, messageId) → show ReportFlowSheet. Null in guest mode. */
    onResolveHandle: ((handle: String, postId: Long?) -> Unit)?,
    /** Guest mode: delegate via GuestReportDelegator. Null in auth mode. */
    onGuestReport: ((handle: String, postId: Long?) -> Unit)?,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()

    var handle by remember { mutableStateOf("") }
    var postIdText by remember { mutableStateOf("") }
    var handleError by remember { mutableStateOf(false) }

    // Strip leading @ so the user can paste @channel or channel interchangeably.
    val cleanHandle = handle.trimStart('@').trim()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.report_content_title),
                size = HortayTopBarSize.Compact,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                            size = 24.dp,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
        ) {
            OutlinedTextField(
                value = handle,
                onValueChange = {
                    handle = it
                    handleError = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.report_handle_label)) },
                prefix = { Text("@") },
                isError = handleError,
                supportingText = if (handleError) {
                    { Text(stringResource(R.string.report_invalid_handle)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = postIdText,
                onValueChange = { v -> if (v.all { it.isDigit() }) postIdText = v },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.report_post_id_label)) },
                placeholder = { Text(stringResource(R.string.report_post_id_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(
                onClick = {
                    if (!HANDLE_REGEX.matches(cleanHandle)) {
                        handleError = true
                        return@FilledTonalButton
                    }
                    val postId = postIdText.toLongOrNull()
                    scope.launch {
                        if (onResolveHandle != null) {
                            onResolveHandle(cleanHandle, postId)
                        } else {
                            onGuestReport?.invoke(cleanHandle, postId)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = cleanHandle.isNotEmpty(),
            ) {
                Text(stringResource(R.string.report_continue))
            }
        }
    }
}
