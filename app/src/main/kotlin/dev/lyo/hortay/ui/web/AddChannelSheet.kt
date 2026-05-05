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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lyo.hortay.R
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
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var input by remember { mutableStateOf("") }
    var lookupState by remember { mutableStateOf<LookupState>(LookupState.Idle) }

    fun lookup(username: String) {
        lookupState = LookupState.Loading
        scope.launch {
            lookupState = when (val r = client.lookupChannel(username)) {
                is LookupResult.Found -> LookupState.Found(r.channel)
                is LookupResult.Empty -> LookupState.Found(r.channel)
                LookupResult.NotFound -> LookupState.Error(
                    ctx.getString(R.string.web_add_not_found, username),
                )
                LookupResult.Private -> LookupState.Error(
                    ctx.getString(R.string.web_add_private),
                )
                LookupResult.ParseFailure -> LookupState.Error(
                    ctx.getString(R.string.web_add_parse_failure),
                )
                is LookupResult.RateLimited -> LookupState.Error(
                    ctx.getString(R.string.web_add_rate_limited, (r.retryAfterMs / 1000).toInt()),
                )
                is LookupResult.NetworkError -> LookupState.Error(
                    ctx.getString(R.string.web_add_network_error, r.cause.message ?: ""),
                )
            }
        }
    }

    fun trySubmit() {
        val username = parseUsernameFromInput(input)
        if (username == null) {
            lookupState = LookupState.Error(ctx.getString(R.string.web_add_invalid))
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

    // Auto-paste-and-validate from clipboard. If the user copied a Telegram
    // link / @handle anywhere before tapping "Add channel", we eat the manual
    // "paste" step entirely: fill the input and trigger lookup so they land
    // straight on the preview card. Privacy-conscious: we only act when the
    // clipboard text parses as a valid Telegram username via the existing
    // [parseUsernameFromInput] regex — random clipboard contents (passwords,
    // URLs to other sites) silently fall through to the empty default. We
    // also gate on `input.isBlank()` so a stale clipboard never overwrites
    // text the user is actively editing across recompositions.
    LaunchedEffect(Unit) {
        val pasted = runCatching {
            clipboard.getClipEntry()
                ?.clipData?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (pasted != null && input.isBlank()) {
            val username = parseUsernameFromInput(pasted)
            if (username != null) {
                input = pasted
                lookup(username)
            }
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
                text = stringResource(R.string.web_add_channel),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.web_add_body),
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
                label = { Text(stringResource(R.string.web_add_input_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            when (val state = lookupState) {
                LookupState.Idle -> Unit
                LookupState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.web_add_validating),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    Text(stringResource(R.string.web_add_lookup))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.web_cancel))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.web_add_curated_title),
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
                    Text(
                        text = stringResource(R.string.web_subscribers, channel.subscribers),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.web_add_confirm))
            }
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
        TextButton(onClick = onTap) {
            Text(stringResource(R.string.web_add_check))
        }
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
