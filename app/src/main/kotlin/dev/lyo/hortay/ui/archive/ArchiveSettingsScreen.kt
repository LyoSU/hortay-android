@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchivedChannelEntry
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.components.formatByteSize
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.discover.DiscoverSearchField
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.settings.SectionLabel
import dev.lyo.hortay.ui.settings.SettingsGroup
import dev.lyo.hortay.ui.settings.SettingsIconTile
import dev.lyo.hortay.ui.settings.SettingsRow
import dev.lyo.hortay.ui.settings.settingsGroupedShapes
import dev.lyo.hortay.ui.settings.settingsRowColors
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.timeline.READING_WIDTH_CAP
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.launch

/**
 * Settings → Post archive, in the grouped-block style of the rest of Settings.
 *
 * Top to bottom: the master switch with a plain-words description; "Open archive" with the
 * real numbers (messages, not stored rows); what to keep (edits, deletions); storage
 * (retention, entry limit, space used); channels the archive skips; export and clear.
 * While the archive is off only the master switch shows — plus, when something is still
 * stored, the way into it, its size, export and clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSettingsScreen(
    viewModel: ArchiveSettingsViewModel,
    onBack: () -> Unit,
    onOpenArchive: () -> Unit = {},
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val bytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOnboarding by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

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

    val hasData = (counts?.all ?: 0) > 0 || (bytes ?: 0L) > 0L

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.settings_archive_title),
                size = HortayTopBarSize.Large,
                scrollBehavior = scrollBehavior,
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = READING_WIDTH_CAP)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsGroup {
                    ArchiveToggleRow(
                        symbol = "history",
                        title = stringResource(R.string.archive_master_toggle),
                        subtitle = stringResource(R.string.archive_master_description),
                        checked = s.enabled,
                        onCheckedChange = { wantOn ->
                            when {
                                wantOn && !s.onboardingSeen -> showOnboarding = true
                                wantOn -> viewModel.confirmEnableFromOnboarding()
                                else -> showDisableDialog = true
                            }
                        },
                    )
                }

                if (s.enabled || hasData) {
                    SettingsGroup {
                        SettingsRow(
                            symbol = "delete_sweep",
                            title = stringResource(R.string.archive_open_browser),
                            subtitle = openArchiveSummary(counts),
                            chevron = true,
                            grouped = true,
                            onClick = onOpenArchive,
                        )
                    }
                }

                if (s.enabled) {
                    Column {
                        SectionLabel(stringResource(R.string.archive_section_events))
                        SettingsGroup {
                            ArchiveToggleRow(
                                symbol = "edit",
                                title = stringResource(R.string.archive_capture_edits),
                                subtitle = stringResource(R.string.archive_capture_edits_subtitle),
                                checked = s.captureEdits,
                                onCheckedChange = viewModel::setCaptureEdits,
                            )
                            ArchiveToggleRow(
                                symbol = "delete",
                                title = stringResource(R.string.archive_capture_deletes),
                                subtitle = stringResource(R.string.archive_capture_deletes_subtitle),
                                checked = s.captureDeletes,
                                onCheckedChange = viewModel::setCaptureDeletes,
                            )
                        }
                    }
                }

                if (s.enabled || hasData) {
                    Column {
                        SectionLabel(stringResource(R.string.archive_section_retention))
                        SettingsGroup {
                            if (s.enabled) {
                                DropdownRow(
                                    symbol = "timer",
                                    title = stringResource(R.string.archive_retention_label),
                                    subtitle = null,
                                    value = retentionLabel(s.retentionDays),
                                    options = ArchiveSettings.RETENTION_OPTIONS,
                                    labelOf = { retentionLabel(it) },
                                    onPick = viewModel::setRetentionDays,
                                )
                                DropdownRow(
                                    symbol = "storage",
                                    title = stringResource(R.string.archive_max_records_label),
                                    subtitle = stringResource(R.string.archive_max_records_subtitle),
                                    value = recordsLabel(s.maxRecords),
                                    options = ArchiveSettings.MAX_RECORDS_OPTIONS,
                                    labelOf = { recordsLabel(it) },
                                    onPick = viewModel::setMaxRecords,
                                )
                            }
                            StorageSizeRow(bytes)
                        }
                    }
                }

                if (s.enabled) {
                    Column {
                        SectionLabel(stringResource(R.string.archive_section_channels))
                        SettingsGroup {
                            val excluded = s.excludedChats.size
                            SettingsRow(
                                symbol = "visibility_off",
                                title = stringResource(R.string.archive_excluded_title),
                                subtitle = if (excluded == 0) {
                                    stringResource(R.string.archive_excluded_none)
                                } else {
                                    pluralStringResource(R.plurals.archive_excluded_count, excluded, excluded)
                                },
                                chevron = true,
                                grouped = true,
                                onClick = { showExcluded = true },
                            )
                        }
                    }
                }

                if (hasData) {
                    Column {
                        SectionLabel(stringResource(R.string.archive_section_archive))
                        SettingsGroup {
                            SettingsRow(
                                symbol = "ios_share",
                                title = stringResource(R.string.archive_export_json),
                                chevron = true,
                                grouped = true,
                                onClick = { showExportConfirm = true },
                            )
                            SettingsRow(
                                symbol = "delete",
                                title = stringResource(R.string.archive_clear_all),
                                tint = MaterialTheme.colorScheme.error,
                                grouped = true,
                                onClick = { showClearConfirm = true },
                            )
                        }
                    }
                }
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
    if (showExcluded) {
        ExcludedChannelsSheet(
            channels = channels,
            excluded = s.excludedChats,
            onToggle = viewModel::setExcluded,
            onDismiss = { showExcluded = false },
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.archive_clear_all)) },
            text = { Text(stringResource(R.string.archive_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirm = false }) {
                    Text(stringResource(R.string.archive_clear_yes), color = MaterialTheme.colorScheme.error)
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
                        ((bytes ?: 0L) / 1024 / 1024).coerceAtLeast(1L),
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

/** "N deleted · M edited", or a plain "nothing yet"; empty while the first count is loading. */
@Composable
private fun openArchiveSummary(counts: ArchiveCounts?): String? = when {
    counts == null -> null
    counts.all == 0 -> stringResource(R.string.archive_open_empty)
    else -> stringResource(
        R.string.archive_open_summary,
        pluralStringResource(R.plurals.archive_count_deleted, counts.deleted, counts.deleted),
        pluralStringResource(R.plurals.archive_count_edited, counts.edited, counts.edited),
    )
}

/** A Settings switch row whose icon tile lights up while the switch is on. */
@Composable
private fun ArchiveToggleRow(
    symbol: String,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = settingsGroupedShapes(),
        colors = settingsRowColors(),
        leadingContent = { SettingsIconTile(symbol = symbol, active = checked) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
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
    subtitle: String?,
    value: String,
    options: ImmutableList<Int>,
    labelOf: @Composable (Int) -> String,
    onPick: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { expanded = true },
        shapes = settingsGroupedShapes(),
        colors = settingsRowColors(),
        leadingContent = { SettingsIconTile(symbol = symbol) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Symbol(
                        name = "keyboard_arrow_down",
                        tint = MaterialTheme.colorScheme.primary,
                        size = 18.dp,
                    )
                }
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

/**
 * "Space used" with its value on the trailing side. The value is read off the main thread;
 * until it lands the slot stays empty, and a loading indicator appears only if the read
 * outlasts the usual grace — never a "0 B" placeholder.
 */
@Composable
private fun StorageSizeRow(bytes: Long?) {
    val showLoading = rememberDeferredLoading(pending = bytes == null, key = Unit)
    val units = stringArrayResource(R.array.size_units)
    SegmentedListItem(
        onClick = {},
        shapes = settingsGroupedShapes(),
        colors = settingsRowColors(),
        leadingContent = { SettingsIconTile(symbol = "storage") },
        trailingContent = {
            when {
                bytes != null -> Text(
                    text = formatByteSize(bytes, units),
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                showLoading -> LoadingIndicator(Modifier.size(24.dp))
            }
        },
        content = {
            Text(
                text = stringResource(R.string.archive_storage_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Channels the archive skips. Lists every channel the archive has seen (that is where a
 * channel's name and photo come from), with a checkbox per channel; skipped channels the
 * archive no longer knows by name are kept at the end so they can still be let back in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcludedChannelsSheet(
    channels: ImmutableList<ArchivedChannelEntry>,
    excluded: ImmutableSet<ChatRef>,
    onToggle: (ChatRef, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val unknownLabel = stringResource(R.string.archive_unknown_channel)
    val rows = remember(channels, excluded, unknownLabel) {
        val known = channels.map { it.chat to it }
        val knownRefs = known.mapTo(HashSet()) { it.first }
        known.map { (chat, c) -> ExcludedRow(chat, c.title, c.photoMinithumb) } +
            excluded.filterNot { it in knownRefs }.map { ExcludedRow(it, unknownLabel, null) }
    }
    val shown = remember(rows, query) {
        val q = query.trim()
        if (q.isEmpty()) rows else rows.filter { it.title.contains(q, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.archive_excluded_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.archive_excluded_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.size > SEARCH_THRESHOLD) {
                DiscoverSearchField(
                    value = query,
                    onValueChange = { query = it },
                    hint = stringResource(R.string.channels_search_hint),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.archive_excluded_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(items = shown, key = { "${it.chat.kind.name}:${it.chat.key}" }, contentType = { "channel" }) { row ->
                    val isExcluded = row.chat in excluded
                    val toggle = remember(row.chat) { { value: Boolean -> onToggle(row.chat, value) } }
                    ExcludedChannelItem(row = row, excluded = isExcluded, onToggle = toggle)
                }
            }
        }
    }
}

@Immutable
private data class ExcludedRow(val chat: ChatRef, val title: String, val thumb: ByteArray?)

@Composable
private fun ExcludedChannelItem(row: ExcludedRow, excluded: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = excluded, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TdAvatar(name = row.title, thumb = row.thumb, fileId = null, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = excluded, onCheckedChange = null)
    }
}

private const val SEARCH_THRESHOLD = 8

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
    else pluralStringResource(R.plurals.archive_retention_days, days, days)

@Composable
private fun recordsLabel(n: Int): String =
    if (n == Int.MAX_VALUE) stringResource(R.string.archive_records_unlimited)
    else java.text.NumberFormat.getIntegerInstance().format(n)
