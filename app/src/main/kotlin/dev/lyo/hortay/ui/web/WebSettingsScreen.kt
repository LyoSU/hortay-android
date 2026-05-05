package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.AppGraph
import kotlinx.coroutines.launch

/**
 * Settings screen for the guest reading mode. Mirrors the TDLib mode's
 * [dev.lyo.hortay.ui.settings.SettingsScreen] visual style — full-bleed
 * scrollable column with section dividers — adapted to the much smaller
 * surface area of guest mode.
 *
 * Three concerns:
 *   1. Sign-in CTA (primary action) — flips [GuestModeStore.setGuest] to false;
 *      [MainActivity] recomposes and routes to AuthScreen. Subscriptions
 *      survive the transition.
 *   2. Clear cache — wipes posts + custom emoji; subscriptions intentionally
 *      stay so the user re-fetches the same channels rather than re-discovering.
 *   3. Privacy footer — what we actually send to Telegram (just IP). Visible
 *      at the moment a privacy-conscious user is most likely to wonder.
 */
@Composable
fun WebSettingsScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    onSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Налаштування",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Анонімний режим. Без авторизації в Telegram.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Увійти в Telegram")
        }
        Text(
            text = "Дасть вам коментарі, реакції та приватні канали. Підписки збережуться.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        OutlinedButton(
            onClick = {
                scope.launch {
                    clearing = true
                    graph.webRepository.clearAllCache()
                    clearing = false
                }
            },
            enabled = !clearing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(if (clearing) "Очищаємо…" else "Очистити кеш")
        }
        Text(
            text = "Видалить пости і ресурси кешу. Список підписок збережеться.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text(
            text = "Що бачить Telegram у цьому режимі",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "Ваш IP та звичайний User-Agent десктопного браузера. " +
                "Жодних cookies, жодного облікового запису, жодних повідомлень про вас.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
