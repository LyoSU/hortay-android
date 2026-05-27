@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.lyo.hortay.R
import dev.lyo.hortay.data.web.LookupResult
import dev.lyo.hortay.data.web.WebChannelInfo
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.parseUsernameFromInput
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet for subscribing to a new public channel. UX flow:
 *
 *   1. User pastes a t.me link / username / @handle / tg://resolve URL.
 *   2. [parseUsernameFromInput] extracts the bare username (2-32 ASCII chars).
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
// LocalContextGetResourceValueCall: error strings are formatted inside an
// async `scope.launch` block (the lookup result lands ~100-1000ms later),
// not during composition itself. The lint check can't distinguish "read in
// composition body" from "read in a coroutine spawned from composition" so
// it flags both. Locale-change correctness is preserved because the sheet
// re-composes on Configuration changes anyway — a lookup in flight when the
// user switches language will format with the old locale, but that's a
// 1-frame edge case worth less than the readability cost of capturing every
// format-string template as a `stringResource()` val up top.
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun AddChannelSheet(
    feedSource: WebFeedSource,
    repository: WebRepository,
    client: WebTelegramClient,
    locale: String,
    onDismiss: () -> Unit,
    /**
     * Tapped when the user wants to escape guest mode — currently surfaced
     * only on the "channel is private" error path, where the sign-in path is
     * the only way to actually read the channel. Caller flips
     * `graph.guestMode.setGuest(false)` to route MainActivity through to
     * `AuthScreen`.
     */
    onSignIn: (() -> Unit)? = null,
    // Optional caller-supplied username pre-fill. Used by deep-link arrivals in
    // guest mode (`tg://resolve?domain=foo` shared from a browser) to land the
    // user directly on the preview card without forcing them to re-paste. When
    // null we fall back to the clipboard auto-paste path; specifying a value
    // suppresses clipboard sniffing so a deep link never gets clobbered by a
    // stale clipboard entry. Compose key on this value so re-opening the sheet
    // with a different handle re-runs the lookup.
    prefilledUsername: String? = null,
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
                    message = ctx.getString(R.string.web_add_private),
                    isPrivate = true,
                )
                LookupResult.ParseFailure -> LookupState.Error(
                    ctx.getString(R.string.web_add_parse_failure),
                )
                // Ceiling division + min-1 guard: a sub-second retryAfterMs
                // (e.g. 400ms) used to format as "0 s" via integer truncation,
                // which read like "no wait at all" in the error and invited a
                // rapid-fire retry.
                is LookupResult.RateLimited -> LookupState.Error(
                    ctx.getString(
                        R.string.web_add_rate_limited,
                        ((r.retryAfterMs + 999) / 1000).toInt().coerceAtLeast(1),
                    ),
                )
                is LookupResult.NetworkError -> LookupState.Error(
                    if (r.cause is dev.lyo.hortay.data.web.LookupTimeoutException) {
                        ctx.getString(R.string.web_lookup_timed_out)
                    } else {
                        ctx.getString(R.string.web_add_network_error, r.cause.message ?: "")
                    },
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

    // Deep-link pre-fill takes precedence over clipboard auto-paste. When the
    // sheet was opened via a `tg://resolve?domain=foo` arrival, the caller
    // supplies the resolved handle here; we fill the input + run lookup
    // straight away. Clipboard sniffing is suppressed in this case so a stale
    // clipboard entry can't clobber the deep-link target. Falling back to
    // clipboard otherwise preserves the auto-paste UX from the manual flow.
    LaunchedEffect(prefilledUsername) {
        if (prefilledUsername != null) {
            if (input.isBlank()) {
                input = prefilledUsername
                lookup(prefilledUsername)
            }
            return@LaunchedEffect
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
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.web_add_validating),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is LookupState.Found -> ChannelPreviewCard(
                    channel = state.channel,
                    onConfirm = { confirmSubscribe(state.channel) },
                )
                is LookupState.Error -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Private-channel-specific recovery affordance: only the
                    // authenticated TDLib path can read private channels (we
                    // need a session cookie to fetch the post stream — t.me/s/
                    // returns a generic placeholder for non-public channels).
                    // Surface "Sign in" as the obvious next step instead of
                    // letting the user bounce back to fix an input that was
                    // structurally fine.
                    if (state.isPrivate && onSignIn != null) {
                        TextButton(onClick = {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                onSignIn()
                            }
                        }) {
                            Text(stringResource(R.string.web_add_signin_for_private))
                        }
                    }
                }
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

            // Already-subscribed lowercase set — used to filter both the
            // curated picks AND the "mentioned in your channels" suggestions.
            // Reading via collectAsStateWithLifecycle so the list re-renders
            // when the user subscribes from this very sheet (the row falls
            // off "suggested" the moment the subscription lands).
            val channels by feedSource.channels.collectAsStateWithLifecycle()
            val subscribedSet by remember(channels) {
                derivedStateOf {
                    channels.asSequence()
                        .filter { it.isSubscribed }
                        .map { it.info.username.lowercase() }
                        .toHashSet()
                }
            }

            // One-shot scan of recent posts in subscribed channels for
            // @mentions + forward sources. Re-runs only when [subscribedSet]
            // changes — i.e. user subscribed/unsubscribed in another surface.
            // Limited to the top 6 unmatched mentions so the picker doesn't
            // bloat into a wall of suggestions.
            val mentionedSuggestions by produceState(
                initialValue = emptyList<MentionedChannel>(),
                subscribedSet,
            ) {
                value = if (subscribedSet.isEmpty()) {
                    emptyList()
                } else {
                    repository
                        .mentionedUsernamesFromSubscribed()
                        .asSequence()
                        .filter { (u, _) -> u !in subscribedSet }
                        .take(6)
                        .map { (u, count) -> MentionedChannel(u, count) }
                        .toList()
                }
            }

            val curatedFiltered = remember(locale, subscribedSet) {
                curatedSuggestions(locale)
                    .filterNot { it.username.lowercase() in subscribedSet }
            }

            if (mentionedSuggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.web_add_mentioned_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                mentionedSuggestions.forEach { suggestion ->
                    MentionedRow(
                        suggestion = suggestion,
                        onTap = {
                            input = suggestion.username
                            lookup(suggestion.username)
                        },
                    )
                }
            }

            if (curatedFiltered.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.web_add_curated_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                curatedFiltered.forEach { suggestion ->
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
}

@Composable
private fun ChannelPreviewCard(channel: WebChannelInfo, onConfirm: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channel.avatarUrl != null) {
                AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = stringResource(
                        R.string.avatar_for_channel,
                        channel.title,
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium),
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

/**
 * Channel surfaced from the user's existing subscription content (forwards
 * and @mentions in posts they already follow). Distinct shape from
 * [CuratedChannel] because the row also shows the mention count, which is
 * the primary cue for "why is this suggested" — "згадується у 3 каналах"
 * carries social proof that a flat description can't.
 */
private data class MentionedChannel(
    val username: String,
    val mentionCount: Int,
)

@Composable
private fun MentionedRow(suggestion: MentionedChannel, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("@${suggestion.username}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.web_add_mentioned_count, suggestion.mentionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onTap) {
            Text(stringResource(R.string.web_add_check))
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
    data class Error(val message: String, val isPrivate: Boolean = false) : LookupState
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

// Curated picks: each handle was verified live via
// `curl https://t.me/s/<u>` AND its `class="counter_value">` was checked for
// a meaningful subscriber count (≥5K) — most short / generic-sounding handles
// turn out to be squatters with single-digit subs, not the brand they
// resemble. We deliberately skip "Apple", "OpenAI", "Hacker News" and
// similar Western brands that have no real Telegram presence to avoid
// suggesting the user a 1-subscriber impostor channel.
//
// Mix favours culture / tech / science over hard news so first-launch
// doesn't feel like a news firehose. No Russian-language channels.
internal fun curatedSuggestions(locale: String): List<CuratedChannel> = when (locale) {
    "uk" -> listOf(
        CuratedChannel("durov", "Засновник Telegram"),
        CuratedChannel("telegram", "Офіційні новини Telegram"),
        CuratedChannel("liroom", "Лірум: культура, кіно, література"),
        CuratedChannel("science", "Science: AI, космос, фізика (англ.)"),
        CuratedChannel("hromadske_ua", "Громадське"),
        CuratedChannel("ukrpravda_news", "Українська правда"),
        CuratedChannel("suspilnenews", "Суспільне Новини"),
        CuratedChannel("bbcukrainian", "BBC News Україна"),
    )
    else -> listOf(
        CuratedChannel("durov", "Pavel Durov — Telegram founder"),
        CuratedChannel("telegram", "Official Telegram product news"),
        CuratedChannel("TelegramTips", "Telegram tips & tricks"),
        CuratedChannel("science", "Science: AI, space, biotech, physics"),
        CuratedChannel("deeplearning_ai", "AI & Deep Learning"),
        CuratedChannel("guardian", "The Guardian"),
        CuratedChannel("figma", "Figma design"),
        CuratedChannel("kyivindependent_official", "The Kyiv Independent — Ukraine"),
    )
}
