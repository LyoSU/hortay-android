@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.lyo.hortay.data.discover.ChannelCardData
import dev.lyo.hortay.data.discover.ChannelSuggestionsRepository
import dev.lyo.hortay.data.discover.SuggestedGroup
import dev.lyo.hortay.data.web.LookupResult
import dev.lyo.hortay.data.web.WebChannelInfo
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.parseUsernameFromInput
import dev.lyo.hortay.ui.discover.DiscoverSearchField
import dev.lyo.hortay.ui.discover.discoverSuggestions
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Modal bottom sheet for subscribing to a new public channel in guest mode. UX:
 *
 *   1. User pastes a t.me link / username / @handle / tg://resolve URL, OR taps a
 *      curated suggestion.
 *   2. [parseUsernameFromInput] extracts the bare username; a one-shot
 *      [WebTelegramClient.lookupChannel] confirms the channel and pre-fills the
 *      preview card. Curated suggestions skip the preview — they're trusted, so a
 *      tap subscribes directly.
 *
 * Curated suggestions come from [ChannelSuggestionsRepository] (a remote, locale-
 * aware catalog) and are hydrated live — each row's avatar, real title and
 * subscriber count are fetched via the same `t.me/s` pipeline the rest of guest
 * mode uses. Already-subscribed channels drop out of the list the moment the
 * subscription lands. Authenticated mode shares the same catalog but hydrates via
 * TDLib instead — see `ui/channels/AddChannelTdSheet.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
// LocalContextGetResourceValueCall: error strings are formatted inside an async
// `scope.launch` block (the lookup lands ~100-1000ms later), not during
// composition. The lint check can't tell "read in composition body" from "read in
// a coroutine spawned from composition" so it flags both.
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun AddChannelSheet(
    feedSource: WebFeedSource,
    repository: WebRepository,
    client: WebTelegramClient,
    suggestionsRepo: ChannelSuggestionsRepository,
    locale: String,
    onDismiss: () -> Unit,
    /**
     * Tapped when the user wants to escape guest mode — surfaced on the "channel is
     * private" error path, where signing in is the only way to read the channel.
     */
    onSignIn: (() -> Unit)? = null,
    // Optional caller-supplied username pre-fill (deep-link arrivals). When null we
    // fall back to clipboard auto-paste; a value suppresses clipboard sniffing.
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

    // Deep-link pre-fill takes precedence over clipboard auto-paste.
    LaunchedEffect(prefilledUsername) {
        if (prefilledUsername != null) {
            if (input.isBlank()) {
                input = prefilledUsername
                lookup(prefilledUsername)
            }
            return@LaunchedEffect
        }
        // Auto-paste-and-validate from clipboard: only act when the text parses as a
        // valid Telegram username, and only when the input is still blank.
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

    // Subscriptions, used to drop already-followed channels out of the suggestions.
    val channels by feedSource.channels.collectAsStateWithLifecycle()
    val subscribedSet by remember(channels) {
        derivedStateOf {
            channels.asSequence()
                .filter { it.isSubscribed }
                .map { it.info.username.lowercase() }
                .toHashSet()
        }
    }

    // Just-subscribed handles held visible for SUBSCRIBE_CONFIRM_HOLD_MS so the row's
    // "Subscribed" check-circle confirmation reads before the row leaves the list
    // (DESIGN_POLISH H1). Without this the live subscription flow drops the row the
    // instant the subscribe lands and the confirmation never paints.
    val holdVisible = remember { mutableStateMapOf<String, Unit>() }

    // Curated catalog for this locale, with subscribed channels filtered out.
    val groups by produceState(initialValue = emptyList<SuggestedGroup>(), locale) {
        value = suggestionsRepo.groups(locale)
    }
    val visibleGroups = remember(groups, subscribedSet, holdVisible.keys.toList()) {
        groups.mapNotNull { g ->
            val remaining = g.channels.filter {
                val key = it.username.lowercase()
                key !in subscribedSet || key in holdVisible
            }
            if (remaining.isEmpty()) null else g.copy(channels = remaining.toImmutableList())
        }
    }

    // Live hydration: avatar / real title / subscriber count via t.me/s, eager but
    // throttled to a handful of concurrent fetches. OkHttp's disk cache makes a
    // re-open cheap. Keyed by lowercase username.
    val hydrated = remember { mutableStateMapOf<String, ChannelCardData>() }
    LaunchedEffect(visibleGroups) {
        val names = visibleGroups.flatMap { it.channels }.map { it.username }.distinct()
        val gate = Semaphore(4)
        coroutineScope {
            names.forEach { u ->
                if (hydrated.containsKey(u.lowercase())) return@forEach
                launch {
                    gate.withPermit {
                        val info = when (val r = client.lookupChannel(u)) {
                            is LookupResult.Found -> r.channel
                            is LookupResult.Empty -> r.channel
                            else -> null
                        }
                        if (info != null) {
                            hydrated[u.lowercase()] = ChannelCardData(
                                title = info.title,
                                subscribersText = info.subscribers,
                                avatarUrl = info.avatarUrl,
                            )
                        }
                    }
                }
            }
        }
    }

    // "Mentioned in your channels" — forwards / @mentions from existing subs.
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

    // Hoisted out of the LazyColumn body: stringResource() is @Composable and the
    // LazyListScope content lambda is not, so it can't be read inside discoverSuggestions().
    val addLabel = stringResource(R.string.web_add_confirm)
    val subscribedLabel = stringResource(R.string.discover_subscribed)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // Bound the height so the LazyColumn never gets an infinite max
                // constraint from the sheet's wrap-content Column (would crash).
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.82f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title", contentType = "title") {
                Text(
                    text = stringResource(R.string.web_add_channel),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "lookup", contentType = "lookup") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.web_add_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Stadium search-bar idiom (DESIGN_POLISH H2). The submit
                    // affordance: the IME "search" key and the trailing button both
                    // fire the lookup, since the curated-suggestions redesign dropped
                    // the standalone button row.
                    DiscoverSearchField(
                        value = input,
                        onValueChange = {
                            input = it
                            if (lookupState is LookupState.Error || lookupState is LookupState.Found) {
                                lookupState = LookupState.Idle
                            }
                        },
                        hint = stringResource(R.string.web_add_input_label),
                        onSearch = { trySubmit() },
                        trailing = {
                            TextButton(
                                onClick = ::trySubmit,
                                enabled = input.isNotBlank() && lookupState !is LookupState.Loading,
                            ) {
                                Text(stringResource(R.string.web_add_lookup))
                            }
                        },
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
                }
            }

            if (mentionedSuggestions.isNotEmpty()) {
                item(key = "mentioned_header", contentType = "discover_header") {
                    Text(
                        text = stringResource(R.string.web_add_mentioned_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(mentionedSuggestions, key = { "m_${it.username}" }) { suggestion ->
                    MentionedRow(
                        suggestion = suggestion,
                        onTap = {
                            input = suggestion.username
                            lookup(suggestion.username)
                        },
                    )
                }
            }

            discoverSuggestions(
                groups = visibleGroups,
                hydrated = hydrated,
                addLabel = addLabel,
                subscribedLabel = subscribedLabel,
                // Optimistic subscribe (DESIGN_POLISH M2): the row owns its in-flight /
                // "Subscribed" morph; here we run the subscribe, then keep the handle in
                // `holdVisible` for SUBSCRIBE_CONFIRM_HOLD_MS so the confirmation reads
                // before the live subscription flow drops the row. `subscribeAndRefresh`
                // surfaces its own errors; returning true reflects the dispatch.
                onAdd = { username ->
                    val key = username.lowercase()
                    holdVisible[key] = Unit
                    val title = hydrated[key]?.title ?: username
                    feedSource.subscribeAndRefresh(username, placeholderTitle = title)
                    scope.launch {
                        kotlinx.coroutines.delay(SUBSCRIBE_CONFIRM_HOLD_MS)
                        holdVisible.remove(key)
                    }
                    true
                },
            )
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
 * Channel surfaced from the user's existing subscription content (forwards and
 * @mentions in posts they already follow). The row shows the mention count, which
 * is the social-proof cue a flat description can't carry.
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

private sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Found(val channel: WebChannelInfo) : LookupState
    data class Error(val message: String, val isPrivate: Boolean = false) : LookupState
}

// How long a just-subscribed row stays visible showing its "Subscribed" check-circle
// confirmation before the live subscription flow drops it (DESIGN_POLISH H1).
private const val SUBSCRIBE_CONFIRM_HOLD_MS = 1500L
