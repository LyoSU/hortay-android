@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.settings.SectionLabel
import dev.lyo.hortay.ui.settings.SettingsRow
import kotlinx.coroutines.launch

/**
 * Settings → Post archive management screen.
 *
 * Layout follows the project's existing settings idiom: [SectionLabel] +
 * [SegmentedListItem] sub-rows grouped per section, so the visual rhythm
 * matches Feed / Storage / Privacy etc. Toggle rows use SegmentedListItem
 * directly (the shared [SettingsRow] is chevron-or-nothing trailing).
 *
 * Sections:
 *   1. [archive_section_capture]    — master enable toggle
 *   2. [archive_section_retention]  — TTL + cap dropdowns
 *   3. [archive_section_events]     — what kinds of events to capture
 *   4. [archive_section_archive]    — open archive list, storage size, export, clear
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSettingsScreen(
    viewModel: ArchiveSettingsViewModel,
    onBack: () -> Unit,
    onOpenArchive: () -> Unit = {},
) {
    val s by viewModel.settings.collectAsState()
    val count by viewModel.snapshotCount.collectAsState()
    val bytes by viewModel.storageBytes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOnboarding by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    viewModel.exportTo(out)
                }
            }
        }
    }

    Scaffold(
        topBar = {
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
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(stringResource(R.string.archive_section_capture))
            MasterToggleRow(
                enabled = s.enabled,
                onChange = { wantOn ->
                    if (wantOn && !s.onboardingSeen) showOnboarding = true
                    else if (wantOn) viewModel.confirmEnableFromOnboarding()
                    else showDisableDialog = true
                },
            )

            if (s.enabled) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.archive_section_retention))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DropdownRow(
                        symbol = "timer",
                        title = stringResource(R.string.archive_retention_label),
                        value = retentionLabel(s.retentionDays),
                        options = ArchiveSettings.RETENTION_OPTIONS,
                        labelOf = ::retentionLabel,
                        onPick = viewModel::setRetentionDays,
                        index = 0,
                        count = 2,
                    )
                    DropdownRow(
                        symbol = "storage",
                        title = stringResource(R.string.archive_max_records_label),
                        value = recordsLabel(s.maxRecords),
                        options = ArchiveSettings.MAX_RECORDS_OPTIONS,
                        labelOf = ::recordsLabel,
                        onPick = viewModel::setMaxRecords,
                        index = 1,
                        count = 2,
                    )
                }

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.archive_section_events))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToggleRow(
                        symbol = "edit",
                        title = stringResource(R.string.archive_capture_edits),
                        checked = s.captureEdits,
                        onCheckedChange = viewModel::setCaptureEdits,
                        index = 0,
                        count = 2,
                    )
                    ToggleRow(
                        symbol = "delete",
                        title = stringResource(R.string.archive_capture_deletes),
                        checked = s.captureDeletes,
                        onCheckedChange = viewModel::setCaptureDeletes,
                        index = 1,
                        count = 2,
                    )
                }

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.archive_section_archive))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsRow(
                        symbol = "delete_sweep",
                        title = stringResource(R.string.archive_open_browser),
                        subtitle = pluralStringResource(
                            R.plurals.archive_snapshot_count, count, count,
                        ),
                        chevron = true,
                        index = 0,
                        count = 4,
                        onClick = onOpenArchive,
                    )
                    SettingsRow(
                        symbol = "storage",
                        title = stringResource(R.string.archive_storage_label),
                        subtitle = formatBytes(bytes),
                        index = 1,
                        count = 4,
                    )
                    SettingsRow(
                        symbol = "ios_share",
                        title = stringResource(R.string.archive_export_json),
                        chevron = true,
                        index = 2,
                        count = 4,
                        onClick = { showExportConfirm = true },
                    )
                    SettingsRow(
                        symbol = "delete",
                        title = stringResource(R.string.archive_clear_all),
                        tint = MaterialTheme.colorScheme.error,
                        index = 3,
                        count = 4,
                        onClick = { showClearConfirm = true },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
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
                        (bytes / 1024 / 1024).coerceAtLeast(1L),
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
private fun MasterToggleRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(0, 1, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { onChange(!enabled) },
        shapes = shapes,
        leadingContent = {
            Symbol(
                name = "delete_sweep",
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.archive_master_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onChange) },
        content = {
            Text(
                text = stringResource(R.string.archive_master_toggle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun ToggleRow(
    symbol: String,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, size = 22.dp) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun DropdownRow(
    symbol: String,
    title: String,
    value: String,
    options: List<Int>,
    labelOf: @Composable (Int) -> String,
    onPick: (Int) -> Unit,
    index: Int,
    count: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    val shapes = ListItemDefaults.segmentedShapes(index, count, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { expanded = true },
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, size = 22.dp) },
        trailingContent = {
            Box {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(labelOf(o)) },
                            onClick = { onPick(o); expanded = false },
                        )
                    }
                }
            }
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
    else java.text.NumberFormat.getIntegerInstance().format(n)

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        bytes < 1024 -> "$bytes B"
        kb < 1024 -> "%.1f KB".format(kb)
        else -> "%.1f MB".format(kb / 1024.0)
    }
}
