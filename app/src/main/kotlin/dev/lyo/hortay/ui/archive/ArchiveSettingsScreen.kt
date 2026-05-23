package dev.lyo.hortay.ui.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSettingsScreen(viewModel: ArchiveSettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOnboarding by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var approxBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(s.enabled) {
        if (s.enabled) approxBytes = viewModel.peekStorageBytes()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = viewModel.export()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_archive_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Symbol(
                        name = "arrow_back",
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        size = 24.dp,
                    )
                }
            },
        )
    }) { paddingValues ->
        Column(
            Modifier.padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.archive_master_toggle)) },
                supportingContent = { Text(stringResource(R.string.archive_master_subtitle)) },
                trailingContent = {
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { wantOn ->
                            if (wantOn && !s.onboardingSeen) showOnboarding = true
                            else if (wantOn) viewModel.confirmEnableFromOnboarding()
                            else showDisableDialog = true
                        },
                    )
                },
            )

            if (s.enabled) {
                RetentionDropdown(s.retentionDays, onPick = viewModel::setRetentionDays)
                MaxRecordsDropdown(s.maxRecords, onPick = viewModel::setMaxRecords)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_capture_edits)) },
                    trailingContent = {
                        Switch(
                            checked = s.captureEdits,
                            onCheckedChange = viewModel::setCaptureEdits,
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_capture_deletes)) },
                    trailingContent = {
                        Switch(
                            checked = s.captureDeletes,
                            onCheckedChange = viewModel::setCaptureDeletes,
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.archive_export_json)) },
                    modifier = Modifier.clickable { showExportConfirm = true },
                )
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.archive_clear_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    modifier = Modifier.clickable { showClearConfirm = true },
                )
            }
        }
    }

    if (showOnboarding) {
        ArchiveOnboardingSheet(
            onDismiss = { showOnboarding = false },
            onEnable = {
                viewModel.confirmEnableFromOnboarding()
                showOnboarding = false
            },
        )
    }
    if (showDisableDialog) {
        DisableDialog(
            onKeep = { viewModel.disable(deleteArchive = false); showDisableDialog = false },
            onDelete = { viewModel.disable(deleteArchive = true); showDisableDialog = false },
            onCancel = { showDisableDialog = false },
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.archive_clear_all)) },
            text = { Text(stringResource(R.string.archive_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirm = false }) {
                    Text(stringResource(R.string.archive_clear_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.archive_onboarding_cancel))
                }
            },
        )
    }
    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(R.string.archive_export_json)) },
            text = {
                Text(
                    stringResource(
                        R.string.archive_export_size_warning,
                        (approxBytes / 1024 / 1024).coerceAtLeast(1),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    exportLauncher.launch("hortay-archive-${System.currentTimeMillis()}.json")
                }) { Text(stringResource(R.string.archive_export_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) {
                    Text(stringResource(R.string.archive_onboarding_cancel))
                }
            },
        )
    }
}

@Composable
private fun RetentionDropdown(value: Int, onPick: (Int) -> Unit) {
    val options = ArchiveSettings.RETENTION_OPTIONS
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.archive_retention_label)) },
        trailingContent = {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(retentionLabel(value))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(retentionLabel(d)) },
                            onClick = { onPick(d); expanded = false },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MaxRecordsDropdown(value: Int, onPick: (Int) -> Unit) {
    val options = ArchiveSettings.MAX_RECORDS_OPTIONS
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.archive_max_records_label)) },
        trailingContent = {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(recordsLabel(value))
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { n ->
                        DropdownMenuItem(
                            text = { Text(recordsLabel(n)) },
                            onClick = { onPick(n); expanded = false },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DisableDialog(onKeep: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.archive_disable_title)) },
        text = { Text(stringResource(R.string.archive_disable_body)) },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.archive_disable_keep))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.archive_disable_purge),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.archive_onboarding_cancel))
                }
            }
        },
    )
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days == Int.MAX_VALUE) stringResource(R.string.archive_retention_unlimited)
    else stringResource(R.string.archive_retention_days, days)

@Composable
private fun recordsLabel(n: Int): String =
    if (n == Int.MAX_VALUE) stringResource(R.string.archive_records_unlimited)
    else n.toString()
