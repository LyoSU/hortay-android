package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Single Settings screen used by both TDLib and guest (anonymous) modes.
 *
 * Mode is encoded by which optional services are passed:
 *   - [stats] non-null + [onLogout] non-null → authenticated TDLib mode.
 *     Renders Traffic + Storage cards backed by [StatsRepository], a Logout
 *     row, and the version row.
 *   - [stats] null + [onSignIn] non-null + [onClearWebCache] non-null → guest
 *     mode. Renders a "Sign in to Telegram" CTA, a guest-mode "Clear cache"
 *     row that wipes web.db, a privacy footer, and the version row.
 *
 * Why a single Composable rather than two: section labels, dividers, the
 * SettingsRow chip and the TopAppBar are identical across modes. Branching at
 * the data-source level (which sections render) keeps every visual primitive
 * in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    stats: StatsRepository?,
    contentPadding: PaddingValues,
    onLogout: (() -> Unit)? = null,
    onOpenWebDebug: () -> Unit = {},
    onSignIn: (() -> Unit)? = null,
    onClearWebCache: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmLogout by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf<NetworkUsage?>(null) }
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }

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
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_profile_title), style = MaterialTheme.typography.displaySmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // verticalScroll is essential here: with the Traffic + Storage cards the
                // content overflows phones with shorter screens, and a non-scrollable
                // Column would silently clip the "Вийти" / "Версія" rows below the fold.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- TDLib-mode-only: traffic & storage cards backed by StatsRepository ----
            if (stats != null) {
                SectionLabel(stringResource(R.string.settings_section_traffic))
                TrafficCard(
                    network = network,
                    onReset = {
                        scope.launch {
                            stats.resetTrafficStats()
                            refreshStats()
                        }
                    },
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
                            // Coil's disk cache lives outside TDLib's filesDir; clear both
                            // so the user sees the actual freed space.
                            SingletonImageLoader.get(context).diskCache?.clear()
                            refreshStats()
                            clearing = false
                        }
                    },
                )
            }

            // ---- Guest-mode-only: web.db cache clear + privacy footer -----------------
            if (onClearWebCache != null) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.web_settings_clear_cache))
                SettingsRow(
                    symbol = "delete",
                    title = stringResource(
                        if (clearing) R.string.web_settings_clearing
                        else R.string.web_settings_clear_cache,
                    ),
                    subtitle = stringResource(R.string.web_settings_clear_cache_helper),
                    onClick = {
                        if (!clearing) scope.launch {
                            clearing = true
                            onClearWebCache()
                            clearing = false
                        }
                    },
                )

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.web_settings_privacy_title))
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.web_settings_privacy_title),
                    subtitle = stringResource(R.string.web_settings_privacy_body),
                )
            }

            // ---- Account section: logout (TDLib) OR sign-in CTA (guest) ---------------
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_account))
            if (onLogout != null) {
                SettingsRow(
                    symbol = "logout",
                    title = stringResource(R.string.settings_logout_title),
                    subtitle = stringResource(R.string.settings_logout_subtitle),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { confirmLogout = true },
                )
            }
            if (onSignIn != null) {
                SettingsRow(
                    symbol = "login",
                    title = stringResource(R.string.web_settings_signin),
                    subtitle = stringResource(R.string.web_settings_signin_helper),
                    onClick = onSignIn,
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            SettingsRow(
                symbol = "info",
                title = stringResource(R.string.settings_version),
                subtitle = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            )

            // Debug-only entry point for the anonymous web pipeline smoke test. Hidden
            // in release/beta builds so end users never see it; English literals because
            // it isn't localized as user-facing text.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Debug")
                SettingsRow(
                    symbol = "info",
                    title = "Web mode (anonymous)",
                    subtitle = "Smoke test t.me/s/<channel> pipeline",
                    onClick = onOpenWebDebug,
                )
            }
        }
    }

    if (confirmLogout && onLogout != null) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.settings_logout_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.settings_logout_cancel)) }
            },
            title = { Text(stringResource(R.string.settings_logout_dialog_title)) },
            text = { Text(stringResource(R.string.settings_logout_dialog_text)) },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrafficCard(network: NetworkUsage?, onReset: () -> Unit) {
    val res = LocalContext.current.resources
    StatsCard {
        StatHero(
            primary = TwoColumn(
                left = StatHeroValue(
                    symbol = "arrow_downward",
                    label = stringResource(R.string.settings_traffic_downloaded),
                    value = network?.rxBytes?.let { formatBytes(it, res) } ?: "—",
                ),
                right = StatHeroValue(
                    symbol = "arrow_upward",
                    label = stringResource(R.string.settings_traffic_uploaded),
                    value = network?.txBytes?.let { formatBytes(it, res) } ?: "—",
                ),
            ),
        )
        Text(
            text = stringResource(R.string.settings_traffic_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Symbol(name = "refresh", size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_traffic_reset), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StorageCard(
    storage: StorageUsage?,
    clearing: Boolean,
    onClearCache: () -> Unit,
) {
    val totalBytes = (storage?.totalFilesBytes ?: 0L) + (storage?.databaseSizeBytes ?: 0L)
    val filesBytes = storage?.totalFilesBytes ?: 0L
    val dbBytes = storage?.databaseSizeBytes ?: 0L
    val fillFraction = if (totalBytes <= 0L) 0f else (filesBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    val res = LocalContext.current.resources

    StatsCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (storage == null) "—" else formatBytes(totalBytes, res),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_storage_used_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Symbol(
                name = "storage",
                tint = MaterialTheme.colorScheme.primary,
                size = 28.dp,
            )
        }

        Spacer(Modifier.height(12.dp))
        // Files vs database visual split. Files are the chunky bit; database is small but
        // can't be cleared without a logout, so we show it for honesty.
        StorageBar(filesFraction = fillFraction)

        Spacer(Modifier.height(10.dp))
        StorageLegend(
            filesBytes = filesBytes,
            dbBytes = dbBytes,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClearCache,
            enabled = !clearing && filesBytes > 0L,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (clearing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_storage_clearing), fontWeight = FontWeight.SemiBold)
            } else {
                Symbol(name = "delete_sweep", size = 20.dp, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear), fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            text = stringResource(R.string.settings_storage_clear_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        content = content,
    )
}

private data class StatHeroValue(val symbol: String, val label: String, val value: String)
private data class TwoColumn(val left: StatHeroValue, val right: StatHeroValue)

@Composable
private fun StatHero(primary: TwoColumn) {
    Row(modifier = Modifier.fillMaxWidth()) {
        StatColumn(primary.left, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        StatColumn(primary.right, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatColumn(value: StatHeroValue, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(
                name = value.symbol,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                value.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            value.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StorageBar(filesFraction: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(filesFraction.coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight((1f - filesFraction).coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun StorageLegend(filesBytes: Long, dbBytes: Long) {
    val res = LocalContext.current.resources
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LegendDot(
            color = MaterialTheme.colorScheme.primary,
            label = stringResource(R.string.settings_storage_media),
            value = formatBytes(filesBytes, res),
        )
        LegendDot(
            color = MaterialTheme.colorScheme.tertiary,
            label = stringResource(R.string.settings_storage_db),
            value = formatBytes(dbBytes, res),
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun formatBytes(b: Long, res: android.content.res.Resources): String {
    if (b < 1024) return res.getString(R.string.size_bytes, b.toInt())
    val kb = b / 1024.0
    if (kb < 1024) return res.getString(R.string.size_kb, kb.toFloat())
    val mb = kb / 1024.0
    if (mb < 1024) return res.getString(R.string.size_mb, mb.toFloat())
    val gb = mb / 1024.0
    return res.getString(R.string.size_gb, gb.toFloat())
}

@Composable
private fun SettingsRow(
    symbol: String,
    title: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = symbol, tint = tint, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
