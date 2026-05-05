package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lyo.hortay.data.web.LookupResult
import dev.lyo.hortay.data.web.WebChannelInfo
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.parseUsernameFromInput
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet for subscribing to a new public channel. UX flow:
 *
 *   1. User pastes a t.me link / username / @handle / tg://resolve URL.
 *   2. [parseUsernameFromInput] extracts the bare username (5-32 ASCII chars).
 *   3. We do a one-shot [WebTelegramClient.lookupChannel] to confirm the channel
 *      exists and pre-fill the visual confirmation card (title, avatar, description).
 *   4. User taps "Додати" → [WebFeedSource.subscribeAndRefresh] writes to
 *      DataStore and immediately fetches a first page so the feed updates without
 *      waiting for the next sweep.
 *
 * Validation rule of thumb: do *both* shape validation (regex) and content
 * validation (lookup). The regex catches obvious garbage instantly; the lookup
 * catches valid-shaped-but-deleted handles ("@deleteduser123") and private
 * channels that lack `/s/` previews. Surface both error categories distinctly so
 * the user can tell whether to fix their input or pick a different channel.
 *
 * Curated suggestions appear below the input as a quick-add list. They're
 * locale-aware via [curatedSuggestions] — the Ukrainian-default starter list
 * for `uk`, generic English-language for everything else. Tap a suggestion to
 * pre-fill the input + auto-validate, so the user never types in the empty case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChannelSheet(
    feedSource: WebFeedSource,
    client: WebTelegramClient,
    locale: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var input by remember { mutableStateOf("") }
    var lookupState by remember { mutableStateOf<LookupState>(LookupState.Idle) }

    // Auto-suggest from clipboard if it contains a t.me link. Doesn't insert into
    // the input — just shows a "Paste from clipboard" hint chip the user can tap.
    val clipboardSuggestion = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val pasted = runCatching {
            clipboard.getClipEntry()
                ?.clipData?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (pasted != null && parseUsernameFromInput(pasted) != null) {
            clipboardSuggestion.value = pasted
        }
    }

    fun lookup(username: String) {
        lookupState = LookupState.Loading
        scope.launch {
            lookupState = when (val r = client.lookupChannel(username)) {
                is LookupResult.Found -> LookupState.Found(r.channel)
                is LookupResult.Empty -> LookupState.Found(r.channel)
                LookupResult.NotFound -> LookupState.Error("Канал @$username не знайдено")
                LookupResult.Private -> LookupState.Error("Канал приватний — публічні пости недоступні")
                LookupResult.ParseFailure -> LookupState.Error("Telegram повернув неочікувану розмітку")
                is LookupResult.RateLimited -> LookupState.Error(
                    "Перевищено ліміт запитів — спробуйте за ${r.retryAfterMs / 1000} с",
                )
                is LookupResult.NetworkError -> LookupState.Error(
                    "Помилка мережі: ${r.cause.message}",
                )
            }
        }
    }

    fun trySubmit() {
        val username = parseUsernameFromInput(input)
        if (username == null) {
            lookupState = LookupState.Error("Введіть @username, t.me/username або повне посилання")
            return
        }
        lookup(username)
    }

    fun confirmSubscribe(channel: WebChannelInfo) {
        scope.launch {
            feedSource.subscribeAndRefresh(channel.username, placeholderTitle = channel.title)
            sheetState.hide()
            onDismiss()
        }
    }

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
                text = "Додати канал",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Введіть @username, посилання t.me/username або вставте з буфера обміну.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (lookupState is LookupState.Error || lookupState is LookupState.Found) {
                        lookupState = LookupState.Idle
                    }
                },
                singleLine = true,
                label = { Text("Канал") },
                modifier = Modifier.fillMaxWidth(),
            )

            val clip = clipboardSuggestion.value
            if (clip != null && input.isBlank()) {
                TextButton(onClick = {
                    input = clip
                    clipboardSuggestion.value = null
                }) {
                    Text("Вставити з буфера: ${clip.take(40)}")
                }
            }

            when (val state = lookupState) {
                LookupState.Idle -> Unit
                LookupState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Перевіряємо…", style = MaterialTheme.typography.bodySmall)
                }
                is LookupState.Found -> ChannelPreviewCard(
                    channel = state.channel,
                    onConfirm = { confirmSubscribe(state.channel) },
                )
                is LookupState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::trySubmit,
                    enabled = input.isNotBlank() && lookupState !is LookupState.Loading,
                ) {
                    Text("Знайти")
                }
                TextButton(onClick = onDismiss) { Text("Скасувати") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Популярні канали",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            curatedSuggestions(locale).forEach { suggestion ->
                CuratedRow(
                    suggestion = suggestion,
                    onTap = {
                        input = suggestion.username
                        lookup(suggestion.username)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChannelPreviewCard(channel: WebChannelInfo, onConfirm: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channel.avatarUrl != null) {
                AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("@${channel.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (channel.subscribers != null) {
                    Text("${channel.subscribers} підписників", style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(onClick = onConfirm) { Text("Додати") }
        }
    }
}

@Composable
private fun CuratedRow(suggestion: CuratedChannel, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("@${suggestion.username}", style = MaterialTheme.typography.bodyMedium)
            Text(suggestion.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onTap) { Text("Перевірити") }
    }
}

private sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Found(val channel: WebChannelInfo) : LookupState
    data class Error(val message: String) : LookupState
}

/**
 * One curated channel suggestion. [description] is what we show in the picker —
 * a short Ukrainian/English blurb about why the channel is interesting. Avoid
 * politically polarising defaults to keep first-launch UX neutral.
 */
data class CuratedChannel(
    val username: String,
    val description: String,
)

private fun curatedSuggestions(locale: String): List<CuratedChannel> = when (locale) {
    "uk" -> listOf(
        CuratedChannel("durov", "Засновник Telegram"),
        CuratedChannel("telegram", "Офіційні новини Telegram"),
        CuratedChannel("nexta_live", "Незалежні новини зі Східної Європи"),
        CuratedChannel("varlamov_news", "Новини за Іллею Варламовим"),
        CuratedChannel("bbbreaking", "Критичні новини в реальному часі"),
    )
    else -> listOf(
        CuratedChannel("durov", "Telegram founder Pavel Durov"),
        CuratedChannel("telegram", "Official Telegram product news"),
        CuratedChannel("nexta_live", "Independent Eastern-Europe news"),
        CuratedChannel("breakingmash", "Breaking news, Russian-language"),
    )
}
