package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
 * Settings sheet for the guest reading mode. Surfaces the actions a privacy-
 * conscious anonymous user typically wants:
 *
 *   - Sign in to Telegram → flips [GuestModeStore.setGuest] back to false; the
 *     existing TDLib auth flow takes over from there.
 *   - Clear cache → wipes [WebRepository.clearAllCache] (post bodies, custom
 *     emoji, channel-fetch metadata); subscriptions intentionally survive.
 *   - Privacy footer — chat-with-the-user about what we send to Telegram in
 *     this mode (just IP + a desktop User-Agent), so the privacy promise is
 *     visible at the moment a user might wonder.
 *
 * Why a sheet, not a full screen: settings here are short and finite — a sheet
 * anchored at the bottom keeps the in-app focus on the feed and lets the user
 * dismiss with a swipe. A separate full screen would imply richer settings
 * surface than we actually have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Налаштування",
                style = MaterialTheme.typography.titleLarge,
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
                text = "Ваш IP та звичайний User-Agent десктопного браузера. Жодних cookies, жодного облікового запису, жодних повідомлень про вас.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss) { Text("Закрити") }
            }
        }
    }
}
