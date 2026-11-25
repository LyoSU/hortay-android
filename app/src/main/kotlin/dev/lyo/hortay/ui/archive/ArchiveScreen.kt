@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.data.archive.ArchiveEntry
import dev.lyo.hortay.data.archive.ArchiveEntryStatus
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.ArchivedMediaResolver
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SourceKind
import dev.lyo.hortay.ui.archive.components.ArchiveDayHeader
import dev.lyo.hortay.ui.archive.components.ArchiveEntryRow
import dev.lyo.hortay.ui.components.HortaySnackbarHost
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.discover.DiscoverSearchField
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.timeline.READING_WIDTH_CAP
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Archive browser: one row per archived message — deleted, or edited at least once —
 * grouped by day, newest first. A search field and one row of filters (All / Deleted /
 * Edited with counts, plus a posts-vs-comments menu when the archive holds comments) sit
 * above the list. Tapping a row opens its [PostRevisionSheet]; swiping it away removes the
 * message's whole history from the archive.
 *
 * The list reads [ArchiveViewModel.state] only, built from the repository's
 * [ArchiveEntry] model — never raw snapshot rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * Opens the entry's message in Telegram — [openArchiveEntryInTelegram] in the app.
     * Null, or an entry [archiveEntryTelegramUri] can't build a link for, hides the
     * sheet's "Open in Telegram" action instead of showing a button that does nothing.
     */
    onOpenInTelegram: ((ArchiveEntry) -> Unit)? = null,
    mediaResolver: ArchivedMediaResolver? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val avatars by viewModel.avatars.collectAsStateWithLifecycle()
    var openEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var queryText by rememberSaveable { mutableStateOf(filter.query) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedMessage = stringResource(R.string.archive_purged)

    LaunchedEffect(queryText) {
        delay(SEARCH_DEBOUNCE_MS)
        viewModel.setQuery(queryText)
    }

    Scaffold(
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.archive_screen_title),
                size = HortayTopBarSize.Compact,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        snackbarHost = { HortaySnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val s = state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                s == null -> {
                    val showLoading = rememberDeferredLoading(pending = true, key = Unit, graceMs = SCREEN_MOUNT_GRACE_MS)
                    if (showLoading) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                }
                s.archiveEmpty -> ArchiveEmptyState(
                    title = stringResource(if (s.enabled) R.string.archive_empty_title else R.string.archive_empty_off_title),
                    body = stringResource(if (s.enabled) R.string.archive_empty_enabled else R.string.archive_empty_off_body),
                    actionLabel = if (s.enabled) null else stringResource(R.string.archive_open_settings),
                    onAction = onOpenSettings,
                    modifier = Modifier.padding(padding),
                )
                else -> ArchiveList(
                    state = s,
                    avatars = avatars,
                    filter = filter,
                    query = queryText,
                    onQueryChange = { queryText = it },
                    onStatus = viewModel::setStatus,
                    onScope = viewModel::setScope,
                    onOpenSettings = onOpenSettings,
                    onOpen = { openEntry = it },
                    onRemove = { entry ->
                        viewModel.remove(entry)
                        scope.launch { snackbarHostState.showSnackbar(removedMessage) }
                    },
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = READING_WIDTH_CAP)
                        .fillMaxSize()
                        .imePadding(),
                )
            }
        }
    }

    val current = openEntry
    if (current != null) {
        val revisionsFlow = remember(current.key) { viewModel.observeRevisions(current) }
        val revisions by revisionsFlow.collectAsStateWithLifecycle(
            initialValue = null as ImmutableList<PostSnapshot>?,
        )
        PostRevisionSheet(
            revisions = revisions,
            onDismiss = { openEntry = null },
            onOpenInTelegram = onOpenInTelegram
                ?.takeIf { archiveEntryTelegramUri(current) != null }
                ?.let { open -> { open(current) } },
            mediaResolver = mediaResolver,
        )
    }
}

/**
 * `https://t.me/...` link to an archived message, or null when none can be built.
 *
 * Hand-rolled because the entry carries only the archive's own identity ([ChatRef] +
 * message key), not a feed post: [dev.lyo.hortay.ui.actions.PostActions.openInTelegram]
 * needs a [dev.lyo.hortay.data.TimelinePost], and TDLib's `GetMessageLink` can't link a
 * deleted message anyway. Same formats as that helper's fallback:
 *   - guest mode ([SourceKind.WEB]): the chat key is the public username and the message
 *     key is the raw `t.me/<username>/<seq>` number.
 *   - TDLib channel posts: `t.me/c/<rawChannelId>/<serverId>`; the message key is TDLib's
 *     packed id (`serverId shl 20`). Resolves for members, which a followed channel's reader is.
 *   - TDLib comments: null — the chat is the discussion group, which the reader usually
 *     isn't a member of, so a `t.me/c/` link there would only open an error.
 */
internal fun archiveEntryTelegramUri(entry: ArchiveEntry): Uri? {
    val messageId = entry.messageKey.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return when (entry.chat.kind) {
        SourceKind.WEB -> entry.chat.key.takeIf { it.isNotBlank() }
            ?.let { username -> "https://t.me/$username/$messageId".toUri() }
        SourceKind.TDLIB -> {
            if (entry.isComment) return null
            val rawChannelId = entry.chat.key.removePrefix("-100").takeIf { it.isNotBlank() } ?: return null
            val serverId = (messageId ushr 20).takeIf { it > 0 } ?: return null
            "https://t.me/c/$rawChannelId/$serverId".toUri()
        }
    }
}

/**
 * Opens [archiveEntryTelegramUri] the way [dev.lyo.hortay.ui.actions.PostActions.openInTelegram]
 * opens a post: an `https://t.me` VIEW intent the Telegram client claims (the OS chooser
 * steps in when none is installed), failures swallowed.
 */
internal fun openArchiveEntryInTelegram(context: Context, entry: ArchiveEntry) {
    val uri = archiveEntryTelegramUri(entry) ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ArchiveList(
    state: ArchiveListState,
    avatars: ImmutableMap<ChatRef, ArchiveAvatar>,
    filter: ArchiveListFilter,
    query: String,
    onQueryChange: (String) -> Unit,
    onStatus: (ArchiveEntryStatus?) -> Unit,
    onScope: (ArchiveScope) -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (ArchiveEntry) -> Unit,
    onRemove: (ArchiveEntry) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val labels = rememberArchiveTimeLabels()
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item(key = "controls", contentType = "controls") {
            Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                DiscoverSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    hint = stringResource(R.string.archive_search_placeholder),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // Full width, with the 16 dp margin inside the scroll: chips run under the
                // screen edge (so a cut chip reads as "scrolls") and the last one can still
                // scroll fully into view.
                ArchiveFilterChips(
                    filter = filter,
                    counts = state.counts,
                    showScope = state.hasComments,
                    onStatus = onStatus,
                    onScope = onScope,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (!state.enabled) {
                    ArchiveOffBanner(onOpenSettings, Modifier.padding(horizontal = 16.dp).padding(top = 12.dp))
                }
            }
        }
        if (state.items.isEmpty()) {
            item(key = "no-matches", contentType = "empty") {
                ArchiveEmptyState(
                    title = stringResource(R.string.archive_no_matches_title),
                    body = stringResource(R.string.archive_no_matches_body),
                    actionLabel = null,
                    onAction = {},
                    modifier = Modifier.padding(top = 24.dp),
                    fillHeight = false,
                )
            }
        }
        items(
            items = state.items,
            key = { it.key },
            contentType = { item ->
                when (item) {
                    is ArchiveListItem.DayHeader -> "day"
                    is ArchiveListItem.Entry -> "entry"
                }
            },
        ) { item ->
            when (item) {
                is ArchiveListItem.DayHeader -> ArchiveDayHeader(
                    label = labels.day(item.dayStartMs),
                    modifier = Modifier.animateItem(),
                )
                is ArchiveListItem.Entry -> {
                    val entry = item.entry
                    val open = remember(entry) { { onOpen(entry) } }
                    val remove = remember(entry) { { onRemove(entry) } }
                    SwipeToRemove(onRemove = remove, modifier = Modifier.animateItem()) {
                        ArchiveEntryRow(
                            entry = entry,
                            avatar = avatars[entry.chat],
                            timeLabel = labels.time(entry.eventAtMs),
                            onClick = open,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status chips with live counts in the Channels-tab style, plus a trailing menu chip for
 * posts / comments. An empty status other than All (and not selected) is left out instead
 * of offered as a dead end.
 */
@Composable
private fun ArchiveFilterChips(
    filter: ArchiveListFilter,
    counts: ArchiveCounts,
    showScope: Boolean,
    onStatus: (ArchiveEntryStatus?) -> Unit,
    onScope: (ArchiveScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chips = listOf(
            Triple(null, R.string.archive_filter_all, counts.all),
            Triple(ArchiveEntryStatus.Deleted, R.string.archive_filter_deleted, counts.deleted),
            Triple(ArchiveEntryStatus.Edited, R.string.archive_filter_edited, counts.edited),
        )
        chips.forEach { (status, label, count) ->
            val selected = filter.status == status
            if (status != null && count == 0 && !selected) return@forEach
            FilterChip(
                selected = selected,
                onClick = { onStatus(status) },
                label = {
                    Text(
                        text = stringResource(R.string.archive_filter_count, stringResource(label), count),
                        style = MaterialTheme.typography.labelLarge.tabularFigures(),
                    )
                },
            )
        }
        if (showScope || filter.scope != ArchiveScope.ALL) {
            ScopeChip(selected = filter.scope, onScope = onScope)
        }
    }
}

@Composable
private fun ScopeChip(selected: ArchiveScope, onScope: (ArchiveScope) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != ArchiveScope.ALL,
            onClick = { expanded = true },
            label = { Text(stringResource(selected.labelRes())) },
            trailingIcon = { Symbol(name = "keyboard_arrow_down", size = 18.dp) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(ArchiveScope.ALL, ArchiveScope.POSTS, ArchiveScope.COMMENTS).forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = if (option == selected) {
                        { Symbol(name = "check_circle", size = 18.dp, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = {
                        onScope(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun ArchiveScope.labelRes(): Int = when (this) {
    ArchiveScope.ALL -> R.string.archive_scope_all
    ArchiveScope.POSTS -> R.string.archive_scope_posts
    ArchiveScope.COMMENTS -> R.string.archive_scope_comments
}

/** Swipe either way to remove; TalkBack gets the same action through a custom action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentRemove by rememberUpdatedState(onRemove)
    val label = stringResource(R.string.archive_remove_action)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                currentRemove()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(label) { currentRemove(); true })
        },
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Arrangement.Start
                } else {
                    Arrangement.End
                },
            ) {
                Symbol(
                    name = "delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    size = 22.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun ArchiveOffBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.archive_off_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.archive_open_settings))
        }
    }
}

@Composable
private fun ArchiveEmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = true,
) {
    Column(
        modifier = modifier
            .then(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "history",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 40.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) {
                Text(actionLabel)
            }
        }
    }
}

/** Day-header and row-time formatters in the app locale and the device time zone. */
private class ArchiveTimeLabels(
    private val today: String,
    private val yesterday: String,
    private val locale: Locale,
    is24h: Boolean,
) {
    private val zone = TimeZone.getDefault()
    private val cal = Calendar.getInstance(zone)
    private val todayStart = startOfDay(cal, System.currentTimeMillis())
    private val yesterdayStart = run {
        cal.timeInMillis = todayStart
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.timeInMillis
    }
    private val currentYear = run {
        cal.timeInMillis = todayStart
        cal.get(Calendar.YEAR)
    }
    private val timeFormat = pattern(if (is24h) "Hmm" else "hmm")
    private val dayFormat = pattern("MMMMd")
    private val dayYearFormat = pattern("yMMMMd")

    private fun pattern(skeleton: String) =
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .apply { timeZone = zone }

    fun time(epochMs: Long): String = timeFormat.format(Date(epochMs))

    fun day(dayStartMs: Long): String = when (dayStartMs) {
        todayStart -> today
        yesterdayStart -> yesterday
        else -> {
            cal.timeInMillis = dayStartMs
            val fmt = if (cal.get(Calendar.YEAR) == currentYear) dayFormat else dayYearFormat
            fmt.format(Date(dayStartMs))
        }
    }
}

@Composable
private fun rememberArchiveTimeLabels(): ArchiveTimeLabels {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val today = stringResource(R.string.archive_day_today)
    val yesterday = stringResource(R.string.archive_day_yesterday)
    return remember(locale, is24h, today, yesterday) { ArchiveTimeLabels(today, yesterday, locale, is24h) }
}

private const val SEARCH_DEBOUNCE_MS = 250L
