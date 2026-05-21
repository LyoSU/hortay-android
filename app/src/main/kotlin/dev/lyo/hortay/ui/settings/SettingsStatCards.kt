@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.ui.icons.Symbol

@Composable
internal fun TrafficCard(network: NetworkUsage?, onReset: () -> Unit) {
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
            shape = MaterialTheme.shapes.large,
        ) {
            Symbol(name = "refresh", size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_traffic_reset), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun StorageCard(
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
            shapes = ButtonDefaults.shapes(
                shape = MaterialTheme.shapes.large,
                pressedShape = MaterialTheme.shapes.small,
            ),
            enabled = !clearing && filesBytes > 0L,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (clearing) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
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
            .clip(MaterialTheme.shapes.medium)
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
private fun LegendDot(color: Color, label: String, value: String) {
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

private fun formatBytes(b: Long, res: Resources): String {
    if (b < 1024) return res.getString(R.string.size_bytes, b.toInt())
    val kb = b / 1024.0
    if (kb < 1024) return res.getString(R.string.size_kb, kb.toFloat())
    val mb = kb / 1024.0
    if (mb < 1024) return res.getString(R.string.size_mb, mb.toFloat())
    val gb = mb / 1024.0
    return res.getString(R.string.size_gb, gb.toFloat())
}
