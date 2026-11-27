@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.channels

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.subscriptions.ImportSheetState
import dev.lyo.hortay.data.subscriptions.SubscriptionListFormat
import dev.lyo.hortay.data.subscriptions.SubscriptionTransfer
import dev.lyo.hortay.data.subscriptions.TransferMode
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Overflow button for the Channels tab (both modes): import a channel list, share the
 * list as text, or save it as a file through the system file picker. The list is plain
 * text, one `https://t.me/<name>` per line ([SubscriptionListFormat]).
 *
 * Export items are disabled while there is nothing to export ([hasChannels]); import
 * stays available on an empty list — a fresh install is exactly where it is needed.
 */
@Composable
fun ChannelListTransferMenu(
    transfer: SubscriptionTransfer,
    mode: TransferMode,
    hasChannels: Boolean,
    userMessages: UserMessageBus,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val emptyMessage = stringResource(R.string.channel_export_empty)
    val shareTitle = stringResource(R.string.channel_export_share)
    val saveFailed = stringResource(R.string.channel_export_failed)
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SubscriptionListFormat.MIME_TYPE),
    ) { uri ->
        // The application's resolver: the write can outlive this screen's Activity context.
        if (uri != null) transfer.writeExport(context.applicationContext.contentResolver, uri, mode)
    }

    Box {
        IconButton(onClick = { open = true }) {
            Symbol(name = "more_horiz", contentDescription = stringResource(R.string.channel_transfer_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_import_action)) },
                leadingIcon = { Symbol(name = "add", contentDescription = null) },
                onClick = {
                    open = false
                    transfer.openImport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_export_share)) },
                leadingIcon = { Symbol(name = "share", contentDescription = null) },
                enabled = hasChannels,
                onClick = {
                    open = false
                    scope.launch {
                        val list = transfer.exportList(mode)
                        if (list.usernames.isEmpty()) {
                            userMessages.post(emptyMessage, UserMessageBus.Severity.Info)
                            return@launch
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = SubscriptionListFormat.MIME_TYPE
                            putExtra(Intent.EXTRA_TEXT, SubscriptionListFormat.format(list.usernames))
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        } catch (_: ActivityNotFoundException) {
                            return@launch
                        }
                        transfer.reportSkipped(list)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_export_save)) },
                leadingIcon = { Symbol(name = "description", contentDescription = null) },
                enabled = hasChannels,
                onClick = {
                    open = false
                    scope.launch {
                        // Checked before the picker opens, so the user never names a file
                        // that would come out empty (a signed-in list of private channels).
                        if (transfer.exportList(mode).usernames.isEmpty()) {
                            userMessages.post(emptyMessage, UserMessageBus.Severity.Info)
                            return@launch
                        }
                        try {
                            saveLauncher.launch(SubscriptionListFormat.exportFileName(LocalDate.now()))
                        } catch (_: ActivityNotFoundException) {
                            userMessages.post(saveFailed)
                        }
                    }
                },
            )
        }
    }
}

/**
 * Hosts the import sheet whenever [SubscriptionTransfer.sheet] is open. Mounted once per
 * mode by `MainActivity`, so the Channels tab menu and text shared to Hortay land on the
 * same sheet.
 */
@Composable
fun ChannelImportSheetHost(transfer: SubscriptionTransfer, mode: TransferMode) {
    val state by transfer.sheet.collectAsStateWithLifecycle()
    state?.let { ChannelImportSheet(transfer = transfer, mode = mode, state = it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelImportSheet(
    transfer: SubscriptionTransfer,
    mode: TransferMode,
    state: ImportSheetState,
) {
    val sheetState = rememberExpandedSheetState()
    val scope = rememberCoroutineScope()
    val joining = state is ImportSheetState.Joining
    ModalBottomSheet(
        onDismissRequest = {
            // While joining, dismiss gestures are off; if one slips through, keep the sheet
            // up so the progress stays visible — Stop is the way out.
            if (!joining) transfer.dismiss() else scope.launch { sheetState.show() }
        },
        sheetState = sheetState,
        sheetGesturesEnabled = !joining,
        properties = ModalBottomSheetProperties(!joining, !joining),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.channel_import_title),
                style = MaterialTheme.typography.titleLarge,
            )
            when (state) {
                is ImportSheetState.Input -> ImportInput(transfer, state)
                is ImportSheetState.Preview -> ImportPreview(transfer, mode, state)
                is ImportSheetState.Joining -> ImportJoining(transfer, state)
            }
        }
    }
}

@Composable
private fun ImportInput(transfer: SubscriptionTransfer, state: ImportSheetState.Input) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keyed on the state: a submit that found nothing comes back with the same text.
    var text by remember(state) { mutableStateOf(state.initialText) }
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolver = context.applicationContext.contentResolver
            scope.launch { transfer.submitFile(resolver, uri) }
        }
    }
    Text(
        text = stringResource(R.string.channel_import_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.channel_import_field_label)) },
        isError = state.nothingFound && text == state.initialText,
        supportingText = if (state.nothingFound && text == state.initialText) {
            { Text(stringResource(R.string.channel_import_nothing_found)) }
        } else {
            null
        },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { transfer.submitText(text) },
            enabled = text.isNotBlank(),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.channel_import_find))
        }
        OutlinedButton(
            onClick = {
                // text/* covers .txt from any file manager. A device without a document
                // picker has nothing to open; the paste field still works there.
                runCatching { openLauncher.launch(arrayOf("text/*")) }
            },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Symbol(name = "description", contentDescription = null, size = 18.dp)
            Text(
                text = stringResource(R.string.channel_import_open_file),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ImportPreview(
    transfer: SubscriptionTransfer,
    mode: TransferMode,
    state: ImportSheetState.Preview,
) {
    // Null until the followed set is read — the confirm button waits for it so an
    // already-followed channel is never joined twice.
    val followed by produceState<Set<String>?>(initialValue = null, state, mode) {
        value = transfer.followedUsernames(mode)
    }
    val known = followed
    val fresh = remember(state, known) {
        if (known == null) emptyList() else state.usernames.filter { it !in known }
    }
    val total = state.usernames.size
    Text(
        text = if (known == null) {
            pluralStringResource(R.plurals.channel_import_found_only, total, total)
        } else {
            pluralStringResource(R.plurals.channel_import_found, total, total, fresh.size)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.truncated) {
        Text(
            text = pluralStringResource(
                R.plurals.channel_import_truncated,
                SubscriptionListFormat.MAX_IMPORT_CHANNELS,
                SubscriptionListFormat.MAX_IMPORT_CHANNELS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (mode == TransferMode.Account) {
        // Joining is visible to channel admins and paced to stay under Telegram's limits.
        Text(
            text = stringResource(R.string.channel_import_account_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
    ImportList(usernames = state.usernames, followed = known)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { transfer.apply(mode, fresh) },
            enabled = fresh.isNotEmpty(),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                text = if (known != null && fresh.isEmpty()) {
                    stringResource(R.string.channel_import_nothing_new)
                } else {
                    pluralStringResource(R.plurals.channel_import_confirm, fresh.size, fresh.size)
                },
            )
        }
        OutlinedButton(
            onClick = { transfer.backToInput() },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun ImportList(usernames: ImmutableList<String>, followed: Set<String>?) {
    val alreadyLabel = stringResource(R.string.channel_import_already_following)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = usernames, key = { it }) { username ->
            val already = followed != null && username in followed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Symbol(
                    name = if (already) "check_circle" else "campaign",
                    contentDescription = null,
                    size = 20.dp,
                    tint = if (already) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (already) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (already) {
                    Text(
                        text = alreadyLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportJoining(transfer: SubscriptionTransfer, state: ImportSheetState.Joining) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Wavy: the run moves one channel per second, so a plain bar would look stuck
        // between ticks.
        LinearWavyProgressIndicator(
            progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.channel_import_progress, state.processed, state.total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        state.current?.let { current ->
            Text(
                text = "@$current",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    OutlinedButton(
        onClick = { transfer.stopJoining() },
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.channel_import_stop))
    }
}
