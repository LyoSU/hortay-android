@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.ui.theme.HortaySpacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Guest mode: new-post notifications. How many channels have their bell on (the bell
 * itself lives in each channel's header, so this row only reports), and "Check over Wi-Fi
 * only" for the background check ([SettingsStore.webNotifyWifiOnly]).
 */
@Composable
internal fun WebNotifySection(
    settings: SettingsStore,
    notifyChannelCount: Flow<Int>,
) {
    val scope = rememberCoroutineScope()
    val count by notifyChannelCount.collectAsStateWithLifecycle(initialValue = 0)
    val wifiOnly by settings.webNotifyWifiOnly.collectAsStateWithLifecycle(initialValue = false)
    Spacer(Modifier.height(HortaySpacing.xs))
    SectionLabel(stringResource(R.string.settings_section_notifications))
    SettingsGroup {
        SettingsRow(
            symbol = "notifications_active",
            title = stringResource(R.string.settings_web_notify_title),
            subtitle = if (count == 0) {
                stringResource(R.string.settings_web_notify_subtitle_empty)
            } else {
                pluralStringResource(R.plurals.settings_web_notify_count, count, count)
            },
            grouped = true,
        )
        WifiOnlyRow(
            enabled = wifiOnly,
            onToggle = { next -> if (next != wifiOnly) scope.launch { settings.setWebNotifyWifiOnly(next) } },
        )
    }
}

@Composable
private fun WifiOnlyRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = settingsGroupedShapes(),
        colors = settingsRowColors(),
        leadingContent = {
            SettingsIconTile(symbol = "wifi", active = enabled)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_web_notify_wifi_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_web_notify_wifi_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
