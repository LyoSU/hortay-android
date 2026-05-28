@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import dev.lyo.hortay.R
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Data & storage sub-screen. TDLib mode: Traffic + Storage cards + an Auto-download
 * drill row. Guest mode (stats == null, onClearWebCache != null): web.db clear-cache row
 * + privacy footer. Pushed from the Main settings page; back pops the route stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataStorageScreen(
    stats: StatsRepository?,
    contentPadding: PaddingValues,
    onClearWebCache: (suspend () -> Unit)?,
    autoDownloadAvailable: Boolean,
    onOpenAutoDownload: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var network by remember { mutableStateOf<NetworkUsage?>(null) }
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClearWebCache by remember { mutableStateOf(false) }

    suspend fun refreshStats() {
        val s = stats ?: return
        network = s.networkUsage()
        storage = s.storageUsage()
    }
    LaunchedEffect(stats) { refreshStats() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.settings_category_data_title),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stats != null) {
                SectionLabel(stringResource(R.string.settings_section_traffic))
                TrafficCard(
                    network = network,
                    onReset = { scope.launch { stats.resetTrafficStats(); refreshStats() } },
                )
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.settings_section_storage))
                StorageCard(
                    storage = storage,
                    clearing = clearing,
                    onClearCache = {
                        scope.launch {
                            clearing = true
                            stats.clearCache()
                            SingletonImageLoader.get(context).diskCache?.clear()
                            refreshStats()
                            clearing = false
                        }
                    },
                )
                if (autoDownloadAvailable) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.autodownload_section))
                    SettingsRow(
                        symbol = "download_for_offline",
                        title = stringResource(R.string.autodownload_entry_title),
                        subtitle = stringResource(R.string.autodownload_entry_subtitle),
                        chevron = true,
                        onClick = onOpenAutoDownload,
                    )
                }
            }
            if (onClearWebCache != null) {
                SectionLabel(stringResource(R.string.web_settings_clear_cache))
                SettingsRow(
                    symbol = "delete",
                    title = stringResource(
                        if (clearing) R.string.web_settings_clearing else R.string.web_settings_clear_cache,
                    ),
                    subtitle = stringResource(R.string.web_settings_clear_cache_helper),
                    onClick = { if (!clearing) confirmClearWebCache = true },
                )
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.web_settings_privacy_title))
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.web_settings_privacy_title),
                    subtitle = stringResource(R.string.web_settings_privacy_body),
                )
            }
        }
    }

    if (confirmClearWebCache && onClearWebCache != null) {
        AlertDialog(
            onDismissRequest = { confirmClearWebCache = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearWebCache = false
                    if (!clearing) scope.launch { clearing = true; onClearWebCache(); clearing = false }
                }) {
                    Text(
                        stringResource(R.string.web_settings_clear_cache_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearWebCache = false }) {
                    Text(stringResource(R.string.web_settings_clear_cache_cancel))
                }
            },
            title = { Text(stringResource(R.string.web_settings_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.web_settings_clear_cache_confirm_body)) },
        )
    }
}
