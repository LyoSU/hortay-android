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
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    stats: StatsRepository,
    contentPadding: PaddingValues,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmLogout by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf<NetworkUsage?>(null) }
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }

    suspend fun refreshStats() {
        network = stats.networkUsage()
        storage = stats.storageUsage()
    }
    LaunchedEffect(Unit) { refreshStats() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Профіль", style = MaterialTheme.typography.displaySmall) },
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
            SectionLabel("Трафік")
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
            SectionLabel("Сховище")
            StorageCard(
                storage = storage,
                clearing = clearing,
                onClearCache = {
                    scope.launch {
                        clearing = true
                        stats.clearCache()
                        // Coil's disk cache (decoded JPEGs from minithumbs and Telegram
                        // file IDs) is separate from TDLib's tdlib-files/. Clear both so
                        // the user sees the actual freed space, not a leftover.
                        SingletonImageLoader.get(context).diskCache?.clear()
                        refreshStats()
                        clearing = false
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("Акаунт")
            SettingsRow(
                symbol = "logout",
                title = "Вийти з акаунту",
                subtitle = "Скине сесію Telegram. Кеш збережеться.",
                tint = MaterialTheme.colorScheme.error,
                onClick = { confirmLogout = true },
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("Про застосунок")
            SettingsRow(
                symbol = "info",
                title = "Версія",
                subtitle = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Вийти", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Скасувати") }
            },
            title = { Text("Вийти з Telegram?") },
            text = { Text("Доведеться знову ввести номер і код підтвердження.") },
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
    StatsCard {
        StatHero(
            primary = TwoColumn(
                left = StatHeroValue(
                    symbol = "arrow_downward",
                    label = "Скачано",
                    value = network?.rxBytes?.let(::formatBytes) ?: "—",
                ),
                right = StatHeroValue(
                    symbol = "arrow_upward",
                    label = "Відправлено",
                    value = network?.txBytes?.let(::formatBytes) ?: "—",
                ),
            ),
        )
        Text(
            text = "Накопичено за весь час роботи. Не зменшується від очистки кешу — це окремий лічильник.",
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
            Text("Скинути лічильник", fontWeight = FontWeight.SemiBold)
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

    StatsCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (storage == null) "—" else formatBytes(totalBytes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "займає Hortay на цьому пристрої",
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
                Text("Очищення…", fontWeight = FontWeight.SemiBold)
            } else {
                Symbol(name = "delete_sweep", size = 20.dp, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Очистити кеш медіа", fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            text = "Видалить кеш фото, відео й файлів (включно з зображеннями Coil). База повідомлень і сесія лишаються — їх скидає лише вихід з акаунту.",
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LegendDot(
            color = MaterialTheme.colorScheme.primary,
            label = "Медіа",
            value = formatBytes(filesBytes),
        )
        LegendDot(
            color = MaterialTheme.colorScheme.tertiary,
            label = "База даних",
            value = formatBytes(dbBytes),
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

private fun formatBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f МБ", mb)
    val gb = mb / 1024.0
    return String.format(Locale.getDefault(), "%.2f ГБ", gb)
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
