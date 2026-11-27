@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.web

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.lyo.hortay.R
import dev.lyo.hortay.data.discover.ChannelCardData
import dev.lyo.hortay.data.discover.ChannelSuggestionsRepository
import dev.lyo.hortay.data.discover.SuggestedGroup
import dev.lyo.hortay.data.web.GuestChannelCandidate
import dev.lyo.hortay.data.web.GuestChannelHit
import dev.lyo.hortay.data.web.GuestChannelIndex
import dev.lyo.hortay.data.web.GuestChannelSource
import dev.lyo.hortay.data.web.GuestMatchTier
import dev.lyo.hortay.data.web.LookupResult
import dev.lyo.hortay.data.web.WebChannelInfo
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.parseUsernameFromInput
import dev.lyo.hortay.data.web.probeableUsername
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.discover.ChannelDiscoverRow
import dev.lyo.hortay.ui.discover.DiscoverSearchField
import dev.lyo.hortay.ui.discover.SectionHeader
import dev.lyo.hortay.ui.discover.discoverSuggestions
import dev.lyo.hortay.ui.discover.rememberHideKeyboardOnDrag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
 *
 * Search by name: guest mode has no Telegram-wide name search (TDLib is unauthorised
 * and t.me only resolves an exact username), so typed text that isn't a link filters
 * a local [GuestChannelIndex] instantly — followed and formerly followed channels, the
 * catalog, forward sources and @mentions — and, when it has a username's shape, is
 * probed live as `@username` after [PROBE_DEBOUNCE_MS] (cached t.me/s page, newest
 * probe wins, a miss shows nothing). The IME Search key and Find still run the explicit
 * FORCE_NETWORK lookup with its error messages; a pasted t.me / tg:// link looks up at
 * once, as before. Only the top results without an avatar are hydrated, after
 * [HYDRATE_DEBOUNCE_MS], through the same 4-permit gate as the catalog.
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
    val sheetState = rememberExpandedSheetState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The sheet's own keyboard controller. ModalBottomSheet hosts its content in a
    // separate window, so a controller read out here belongs to the Activity window and
    // its hide() never reaches the IME the sheet's field opened; the content stores the
    // right one. A plain holder, like lookupJob: nothing renders from it.
    val sheetKeyboard = remember { arrayOfNulls<SoftwareKeyboardController>(1) }

    var input by remember { mutableStateOf("") }
    // A link is resolved, never searched; anything else is a name query.
    val linkMode = isLinkLike(input)
    val query = if (linkMode) "" else input.trim()
    var lookupState by remember { mutableStateOf<LookupState>(LookupState.Idle) }

    // One lookup at a time: a new lookup cancels the previous one, so a slow earlier
    // probe (rate-limit gate, bad network) can't land after a newer one and overwrite
    // its result. A plain holder, not snapshot state — nothing renders from the Job.
    val lookupJob = remember { arrayOfNulls<Job>(1) }

    fun lookup(username: String) {
        lookupJob[0]?.cancel()
        lookupState = LookupState.Loading
        lookupJob[0] = scope.launch {
            val r = client.lookupChannel(username)
            // Cancellation is cooperative: a superseded lookup whose probe already
            // returned must still not publish its result.
            ensureActive()
            lookupState = when (r) {
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

    // An invalid-input error supersedes any lookup still in flight, like a new lookup.
    fun showInvalidInput() {
        lookupJob[0]?.cancel()
        lookupState = LookupState.Error(ctx.getString(R.string.web_add_invalid))
    }

    fun trySubmit() {
        val username = parseUsernameFromInput(input)
        if (username == null) {
            // A name query has its own empty state; only a link that doesn't parse
            // (an invite, a private c/ link) earns the "enter @username" error.
            if (isLinkLike(input)) showInvalidInput() else sheetKeyboard[0]?.hide()
            return
        }
        lookup(username)
    }

    fun clearInput() {
        input = ""
        lookupJob[0]?.cancel()
        lookupState = LookupState.Idle
    }

    fun signIn(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
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
                // Same gate as the typed and clipboard paths: a deep link carries
                // whatever the sender put in it, and only a parsed username may reach
                // the t.me/s/ lookup.
                val username = parseUsernameFromInput(prefilledUsername)
                if (username != null) {
                    lookup(username)
                } else {
                    showInvalidInput()
                }
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

    // Curated catalog for this locale, with subscribed channels filtered out. Null until
    // the catalog resolves — a first open with no disk cache awaits the network, and the
    // sheet shows a loading row for it instead of an empty space under the search field.
    val groups by produceState<List<SuggestedGroup>?>(initialValue = null, locale) {
        value = suggestionsRepo.groups(locale)
    }
    val visibleGroups = remember(groups, subscribedSet, holdVisible.keys.toList()) {
        groups.orEmpty().mapNotNull { g ->
            val remaining = g.channels.filter {
                val key = it.username.lowercase()
                key !in subscribedSet || key in holdVisible
            }
            if (remaining.isEmpty()) null else g.copy(channels = remaining.toImmutableList())
        }
    }

    // Live hydration: avatar / real title / subscriber count via t.me/s, eager but
    // throttled to a handful of concurrent fetches. Keyed by lowercase username.
    // useCache = true lets OkHttp's disk cache serve / revalidate the page, so a
    // re-open costs conditional GETs instead of 10-22 full page downloads; the
    // user-typed lookup below stays FORCE_NETWORK.
    val hydrated = remember { mutableStateMapOf<String, ChannelCardData>() }
    // One gate for every hydration source (catalog rows and search results), so the
    // two never add up to more than four concurrent t.me/s fetches.
    val gate = remember { Semaphore(HYDRATE_CONCURRENCY) }
    LaunchedEffect(visibleGroups) {
        val names = visibleGroups.flatMap { it.channels }.map { it.username }.distinct()
        coroutineScope {
            names.forEach { u ->
                if (hydrated.containsKey(u.lowercase())) return@forEach
                launch {
                    gate.withPermit {
                        val info = when (val r = client.lookupChannel(u, useCache = true)) {
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

    // "Mentioned in your channels" — forwards / @mentions from existing subs, most
    // mentioned first; the repository already leaves bot handles out. Every shown row
    // hydrates itself through the shared gate; a handle t.me can't show as a channel (a
    // person, a group, a private or deleted channel) drops out, and the next one moves
    // up, so the section only ever offers rows a tap can actually subscribe to.
    val mentionedAll by produceState(
        initialValue = emptyList<MentionedChannel>(),
        subscribedSet,
    ) {
        value = if (subscribedSet.isEmpty()) {
            emptyList()
        } else {
            repository
                .mentionedUsernamesFromSubscribed()
                .take(MENTIONED_CANDIDATES)
                .map { (u, count) -> MentionedChannel(u, count) }
        }
    }
    val mentionDropped = remember { mutableStateMapOf<String, Unit>() }
    // Lookups that ended without a verdict (rate limit, network) — not retried while
    // the sheet is open, but the row stays: the handle may well be a channel.
    val mentionAttempted = remember { HashSet<String>() }
    val mentionedSuggestions = remember(mentionedAll, subscribedSet, holdVisible.keys.toList(), mentionDropped.keys.toList()) {
        mentionedAll.asSequence()
            .filter { (it.username !in subscribedSet || it.username in holdVisible) && it.username !in mentionDropped }
            .take(MENTIONED_MAX_ROWS)
            .toList()
            .toImmutableList()
    }

    suspend fun hydrateMention(username: String) {
        if (hydrated.containsKey(username) || username in mentionDropped || username in mentionAttempted) return
        gate.withPermit {
            when (val r = client.lookupChannel(username, useCache = true)) {
                is LookupResult.Found -> hydrated[username] = r.channel.toCardData()
                is LookupResult.Empty -> hydrated[username] = r.channel.toCardData()
                LookupResult.NotFound, LookupResult.Private, LookupResult.ParseFailure ->
                    mentionDropped[username] = Unit
                is LookupResult.RateLimited, is LookupResult.NetworkError -> Unit
            }
            mentionAttempted += username
        }
    }

    // ---- Search by name -------------------------------------------------------

    // Raw candidates from web.db, re-read when the subscriptions change — but only while
    // the field is empty: a reload re-ranks the results (a followed channel outweighs
    // others), and the row just subscribed to must not jump away under the finger.
    var candidateKey by remember { mutableStateOf<Set<String>>(subscribedSet) }
    LaunchedEffect(subscribedSet, query.isEmpty()) {
        if (query.isEmpty()) candidateKey = subscribedSet
    }
    val dbCandidates by produceState<List<GuestChannelCandidate>?>(initialValue = null, candidateKey) {
        value = repository.guestSearchCandidates()
    }
    // The index merges web.db, the catalog and this session's live card data, built off
    // the main thread. Hydration lands one channel at a time; after the first build a
    // short pause coalesces a burst of them into one rebuild.
    val hydratedCount = hydrated.size
    val index by produceState<GuestChannelIndex?>(initialValue = null, dbCandidates, groups, hydratedCount) {
        val base = dbCandidates ?: return@produceState
        if (value != null) delay(INDEX_REBUILD_DEBOUNCE_MS)
        val catalog = groups.orEmpty().asSequence().flatMap { it.channels.asSequence() }.map {
            GuestChannelCandidate(
                username = it.username,
                title = it.titleOverride,
                description = it.description,
                source = GuestChannelSource.Catalog,
            )
        }.toList()
        val live = hydrated.toMap().map { (u, card) ->
            GuestChannelCandidate(
                username = u,
                title = card.title,
                avatarUrl = card.avatarUrl,
                subscribers = card.subscribersText,
                source = GuestChannelSource.Live,
            )
        }
        value = withContext(Dispatchers.Default) { GuestChannelIndex.build(base + catalog + live) }
    }
    val explicitFound = (lookupState as? LookupState.Found)?.channel?.username?.lowercase()
    // Plain string ops over pre-folded fields: cheap enough for every keystroke.
    val hits: ImmutableList<GuestChannelHit> = remember(query, index, explicitFound) {
        val idx = index
        if (query.isEmpty() || idx == null) {
            persistentListOf()
        } else {
            idx.search(query).filter { it.channel.username != explicitFound }.toImmutableList()
        }
    }

    // Live probe of the typed text as an exact username. Skipped while an explicit
    // lookup owns the field, and when the index already has that channel with its card
    // data. Keyed on the name, so a newer keystroke cancels the pending or in-flight
    // probe; a miss simply leaves no row.
    val probeName = if (linkMode) null else probeableUsername(input)
    val localExact = probeName?.let { index?.get(it) }
    val probeNeeded = probeName != null &&
        index != null &&
        lookupState is LookupState.Idle &&
        !(localExact?.title != null && (localExact.avatarUrl != null || hydrated.containsKey(probeName)))
    var probe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }
    LaunchedEffect(probeName, probeNeeded) {
        probe = ProbeState.Idle
        if (!probeNeeded) return@LaunchedEffect
        probe = ProbeState.Pending
        delay(PROBE_DEBOUNCE_MS)
        probe = ProbeState.Loading
        val info = when (val r = client.lookupChannel(probeName, useCache = true)) {
            is LookupResult.Found -> r.channel
            is LookupResult.Empty -> r.channel
            else -> null
        }
        ensureActive()
        // A known channel takes the live card data into its own row; an unknown one
        // gets the probe row. Kept out of `hydrated` then, or the next index rebuild
        // would turn the probe row into a local row with a different key.
        if (info != null && localExact != null) {
            hydrated[probeName] = ChannelCardData(
                title = info.title,
                subscribersText = info.subscribers,
                avatarUrl = info.avatarUrl,
            )
        }
        probe = if (info != null) ProbeState.Found(info) else ProbeState.Missing
    }
    val probeSlot = localExact == null && (probe is ProbeState.Loading || probe is ProbeState.Found)
    val resultCount = hits.size + if (localExact == null && probe is ProbeState.Found) 1 else 0
    val searchActive = query.isNotEmpty()
    val showSearchEmpty = searchActive &&
        index != null &&
        resultCount == 0 &&
        (probe is ProbeState.Idle || probe is ProbeState.Missing)

    // Card data for the top results that lack an avatar, once typing pauses. Keyed on
    // the usernames, so a re-rank that keeps the same top rows doesn't restart it; a
    // channel is tried once per sheet, found or not.
    val topUsernames = remember(hits) { hits.take(HYDRATE_TOP_RESULTS).map { it.channel.username } }
    val hydrateAttempted = remember { HashSet<String>() }
    LaunchedEffect(topUsernames) {
        if (topUsernames.isEmpty()) return@LaunchedEffect
        delay(HYDRATE_DEBOUNCE_MS)
        coroutineScope {
            topUsernames.forEach { u ->
                val known = index?.get(u)
                if (known?.avatarUrl != null || hydrated.containsKey(u) || u in hydrateAttempted) return@forEach
                launch {
                    gate.withPermit {
                        val info = when (val r = client.lookupChannel(u, useCache = true)) {
                            is LookupResult.Found -> r.channel
                            is LookupResult.Empty -> r.channel
                            else -> null
                        }
                        hydrateAttempted += u
                        if (info != null) {
                            hydrated[u] = ChannelCardData(
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

    // Optimistic subscribe (DESIGN_POLISH M2): the row owns its in-flight / "Subscribed"
    // morph; here we run the subscribe, then keep the handle in `holdVisible` for
    // SUBSCRIBE_CONFIRM_HOLD_MS so the confirmation reads before the live subscription
    // flow drops the row from the curated list (search results keep followed channels,
    // shown as subscribed). `subscribeAndRefresh` surfaces its own errors; returning
    // true reflects the dispatch.
    //
    // The very first channel closes the sheet: with nothing followed yet the user came
    // here to get something to read, and the feed behind the sheet now shows that
    // channel loading. Later adds keep the sheet open so several channels can be
    // picked in a row. Read through an updated state: result rows remember this
    // callback across recompositions.
    val currentSubscribed by rememberUpdatedState(subscribedSet)
    suspend fun addChannel(username: String, title: String?): Boolean {
        val key = username.lowercase()
        val firstChannel = currentSubscribed.isEmpty()
        holdVisible[key] = Unit
        val placeholder = title?.takeIf { it.isNotBlank() } ?: hydrated[key]?.title ?: username
        feedSource.subscribeAndRefresh(username, placeholderTitle = placeholder)
        if (firstChannel) {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        } else {
            scope.launch {
                delay(SUBSCRIBE_CONFIRM_HOLD_MS)
                holdVisible.remove(key)
            }
        }
        return true
    }

    // Hoisted out of the LazyColumn body: stringResource() is @Composable and the
    // LazyListScope content lambda is not, so it can't be read inside discoverSuggestions().
    val addLabel = stringResource(R.string.web_add_confirm)
    val subscribedLabel = stringResource(R.string.discover_subscribed)

    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    val sheetContentHeight = remember(windowHeightPx, density) {
        with(density) { (windowHeightPx * SHEET_HEIGHT_FRACTION).toDp() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        sheetKeyboard[0] = LocalSoftwareKeyboardController.current
        // Read inside the sheet's content for the same reason (its own window).
        val hideKeyboardOnDrag = rememberHideKeyboardOnDrag()
        LazyColumn(
            modifier = Modifier
                .nestedScroll(hideKeyboardOnDrag)
                .fillMaxWidth()
                // A fixed height, not a cap: the sheet wraps its content, so a cap let it
                // shrink with every keystroke that left fewer results and the search
                // field slid under the finger. Fixed in every state, it stays put; the
                // results or the empty state fill it or leave space. Also gives the
                // LazyColumn the finite max constraint the wrap-content Column lacks.
                .height(sheetContentHeight)
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
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Stadium search-bar idiom (DESIGN_POLISH H2). Typing filters the
                    // local index as you go; the IME "search" key and the trailing
                    // Find button run the explicit lookup of an @username or link.
                    DiscoverSearchField(
                        value = input,
                        onValueChange = { new ->
                            val previous = input
                            input = new
                            if (lookupState is LookupState.Error || lookupState is LookupState.Found) {
                                lookupState = LookupState.Idle
                            }
                            // A pasted link resolves at once, as the clipboard auto-paste
                            // does. Typed one character at a time it waits for Find, so a
                            // half-typed t.me/du never flashes "not found".
                            if (new.length - previous.length > 1 && isLinkLike(new)) {
                                parseUsernameFromInput(new)?.let(::lookup)
                            }
                        },
                        hint = stringResource(R.string.web_add_search_hint),
                        onSearch = { trySubmit() },
                        onClear = ::clearInput,
                        // Only with text to act on: an empty field would show a disabled
                        // Find that steals the width the hint needs.
                        trailing = if (input.isBlank()) null else {
                            {
                                FindButton(
                                    onClick = ::trySubmit,
                                    enabled = lookupState !is LookupState.Loading &&
                                        (linkMode || parseUsernameFromInput(input) != null),
                                )
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
                                InlineTextButton(
                                    text = stringResource(R.string.web_add_signin_for_private),
                                    onClick = { signIn(onSignIn) },
                                )
                            }
                        }
                    }
                }
            }

            if (searchActive) {
                if (resultCount > 0) {
                    item(key = "search_header", contentType = "discover_header") {
                        SearchResultsHeader(count = resultCount)
                    }
                }
                if (probeSlot) {
                    // One key for the loading and the found state, so the slot keeps
                    // its place while the probe resolves.
                    item(key = "search_probe", contentType = "search_probe") {
                        when (val p = probe) {
                            is ProbeState.Found -> {
                                val info = p.channel
                                ChannelDiscoverRow(
                                    name = info.title.ifBlank { "@${info.username}" },
                                    handle = "@${info.username}",
                                    // Raw t.me count, as on the catalog rows.
                                    subscribers = info.subscribers,
                                    description = null,
                                    avatarThumb = null,
                                    avatarFileId = null,
                                    avatarUrl = info.avatarUrl,
                                    actionLabel = addLabel,
                                    subscribedLabel = subscribedLabel,
                                    actionEnabled = info.username.lowercase() !in subscribedSet,
                                    onAction = remember(info) { { addChannel(info.username, info.title) } },
                                )
                            }
                            else -> ProbeLoadingRow(username = probeName.orEmpty())
                        }
                    }
                }
                items(
                    items = hits,
                    key = { "hit_${it.channel.username}" },
                    contentType = { "discover_row" },
                ) { hit ->
                    val username = hit.channel.username
                    SearchHitRow(
                        hit = hit,
                        card = hydrated[username],
                        subscribed = username in subscribedSet,
                        addLabel = addLabel,
                        subscribedLabel = subscribedLabel,
                        onAdd = remember(username) { { addChannel(username, hit.channel.title) } },
                    )
                }
                if (showSearchEmpty) {
                    item(key = "search_empty", contentType = "search_empty") {
                        SearchEmptyState(
                            onSignIn = onSignIn?.let { action -> { signIn(action) } },
                        )
                    }
                }
            }

            if (!searchActive && mentionedSuggestions.isNotEmpty()) {
                item(key = "mentioned_header", contentType = "discover_header") {
                    SectionHeader(stringResource(R.string.web_add_mentioned_title))
                }
                items(
                    items = mentionedSuggestions,
                    key = { "m_${it.username}" },
                    contentType = { "discover_row" },
                ) { suggestion ->
                    val username = suggestion.username
                    LaunchedEffect(username) { hydrateMention(username) }
                    val card = hydrated[username]
                    ChannelDiscoverRow(
                        name = card?.title?.takeIf { it.isNotBlank() } ?: "@$username",
                        handle = "@$username",
                        subscribers = card?.subscribersText,
                        description = pluralStringResource(
                            R.plurals.web_add_mentioned_count,
                            suggestion.mentionCount,
                            suggestion.mentionCount,
                        ),
                        avatarThumb = null,
                        avatarFileId = null,
                        avatarUrl = card?.avatarUrl,
                        actionLabel = addLabel,
                        subscribedLabel = subscribedLabel,
                        actionEnabled = username !in subscribedSet,
                        // No title captured: addChannel reads the live card's title itself.
                        onAction = remember(username) { { addChannel(username, null) } },
                    )
                }
            }

            if (!searchActive && groups == null) {
                item(key = "suggestions_loading", contentType = "suggestions_loading") {
                    val loadingLabel = stringResource(R.string.web_loading)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                            .semantics { contentDescription = loadingLabel },
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }

            if (!searchActive) {
                discoverSuggestions(
                    groups = visibleGroups,
                    hydrated = hydrated,
                    addLabel = addLabel,
                    subscribedLabel = subscribedLabel,
                    onAdd = { username -> addChannel(username, null) },
                )
            }
        }
    }
}

@Composable
private fun FindButton(onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(R.string.web_add_lookup))
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
@androidx.compose.runtime.Immutable
private data class MentionedChannel(
    val username: String,
    val mentionCount: Int,
)

/**
 * "N channels found" in the shared section-header style, announced politely as the
 * count changes while typing.
 */
@Composable
private fun SearchResultsHeader(count: Int) {
    SectionHeader(
        title = pluralStringResource(R.plurals.web_add_search_results, count, count),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * One local search result on the shared discover row. The matched parts of the title
 * and of the `@handle` line are drawn in `primary`, so a hit inside the username (or a
 * channel known only by its username) shows why it matched.
 * Live card data fills what the index lacks; the emphasis applies only while the shown
 * title is the one the index matched against.
 */
@Composable
private fun SearchHitRow(
    hit: GuestChannelHit,
    card: ChannelCardData?,
    subscribed: Boolean,
    addLabel: String,
    subscribedLabel: String,
    onAdd: suspend () -> Boolean,
) {
    val channel = hit.channel
    val handle = "@${channel.username}"
    val title = channel.title ?: card?.title?.takeIf { it.isNotBlank() }
    val accent = MaterialTheme.colorScheme.primary
    // Ranges index the bare username; the shown handle has a leading "@".
    val handleText = remember(hit, accent) {
        emphasised(handle, hit.usernameRanges.map { (it.first + 1)..(it.last + 1) }, accent)
    }
    val nameText = remember(hit, title, accent) {
        when {
            title == null -> handleText
            title == channel.title -> emphasised(title, hit.titleRanges, accent)
            else -> AnnotatedString(title)
        }
    }
    val seenCount = channel.forwardCount + channel.mentionCount
    val onlySeenInPosts = !channel.hasSource(GuestChannelSource.Subscribed) &&
        !channel.hasSource(GuestChannelSource.Known) &&
        !channel.hasSource(GuestChannelSource.Catalog) &&
        seenCount > 0
    val description = when {
        hit.tier == GuestMatchTier.DescriptionSubstring -> channel.description
        onlySeenInPosts -> pluralStringResource(R.plurals.web_add_mentioned_count, seenCount, seenCount)
        else -> null
    }
    ChannelDiscoverRow(
        name = title ?: handle,
        handle = handle,
        subscribers = channel.subscribers ?: card?.subscribersText,
        description = description,
        avatarThumb = null,
        avatarFileId = null,
        avatarUrl = channel.avatarUrl ?: card?.avatarUrl,
        actionLabel = addLabel,
        subscribedLabel = subscribedLabel,
        actionEnabled = !subscribed,
        onAction = onAdd,
        nameText = nameText,
        handleText = handleText,
    )
}

private fun emphasised(text: String, ranges: List<IntRange>, accent: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(SpanStyle(color = accent), start, end)
        }
    }

/** The probe slot while t.me answers: same footprint as a channel row, so nothing shifts. */
@Composable
private fun ProbeLoadingRow(username: String) {
    val checking = stringResource(R.string.web_add_validating)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The same 6 dp + 48 dp (the subscribe button's touch target) as a found
            // row, so the slot keeps its height when the probe resolves.
            .padding(vertical = 6.dp)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator(modifier = Modifier.size(28.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "@$username",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = checking,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Nothing local matches and the probe found nothing: say what guest search can reach,
 * and offer the one way to search all of Telegram.
 */
@Composable
private fun SearchEmptyState(onSignIn: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.web_add_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onSignIn != null) {
            InlineTextButton(
                text = stringResource(R.string.web_add_search_sign_in),
                onClick = onSignIn,
            )
        }
    }
}

/**
 * A text button whose label starts where the text above it starts: pulled back by the
 * button's own start padding, which would otherwise indent the label. The touch target
 * and ripple keep their full size.
 */
@Composable
private fun InlineTextButton(text: String, onClick: () -> Unit) {
    val inset = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(LocalLayoutDirection.current)
    TextButton(onClick = onClick, modifier = Modifier.offset(x = -inset)) {
        Text(text)
    }
}

private fun WebChannelInfo.toCardData() = ChannelCardData(
    title = title,
    subscribersText = subscribers,
    avatarUrl = avatarUrl,
)

/** A t.me / tg:// link: resolved by lookup, never searched by name. */
private fun isLinkLike(input: String): Boolean {
    val t = input.trim().lowercase()
    return t.startsWith("tg://") || t.contains("t.me/")
}

/** The live username probe (see [PROBE_DEBOUNCE_MS]). */
private sealed interface ProbeState {
    data object Idle : ProbeState

    /** Waiting out the debounce; draws nothing, but holds back the empty state. */
    data object Pending : ProbeState
    data object Loading : ProbeState
    data class Found(val channel: WebChannelInfo) : ProbeState
    data object Missing : ProbeState
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

// Typing pause before the typed username is probed on t.me. Long enough that a word
// typed at speed costs one request, short enough to feel live.
private const val PROBE_DEBOUNCE_MS = 450L

// Typing pause before the top search results without an avatar are hydrated.
private const val HYDRATE_DEBOUNCE_MS = 400L

// How many of the top search results may be hydrated.
private const val HYDRATE_TOP_RESULTS = 8

// Most-mentioned handles read for the "Mentioned in your channels" section, and how
// many of them it shows; the spares replace rows that turn out not to be channels.
private const val MENTIONED_CANDIDATES = 12
private const val MENTIONED_MAX_ROWS = 5

// Concurrent t.me/s card fetches across catalog and search hydration.
private const val HYDRATE_CONCURRENCY = 4

// Share of the window height the sheet's content takes, in every state.
private const val SHEET_HEIGHT_FRACTION = 0.82f

// Coalesces a burst of hydration results into one index rebuild.
private const val INDEX_REBUILD_DEBOUNCE_MS = 300L
