package dev.lyo.hortay.ui.comments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.Reactor
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.data.SenderRef
import dev.lyo.hortay.data.stableKey
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.timeline.ReactionEmojiGlyph
import dev.lyo.hortay.ui.timeline.formatViews
import dev.lyo.hortay.ui.users.LocalUserProfileOpener
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Which comment's reactors to list, and the reaction to start filtered to (null = all). */
internal data class ReactorsRequest(
    val threadChatId: Long,
    val messageId: Long,
    val reactions: Reactions,
    val kind: ReactionKind?,
)

/**
 * Paged "who reacted" list of one comment, scoped to the sheet that shows it (it dies with the
 * sheet, so it holds no account state and needs no logout hook). Each [loadMore] is one
 * `getMessageAddedReactions` page through [CommentsRepository.reactors]; nothing is prefetched
 * or polled. Changing the filter drops the pages of the previous one and cancels its request.
 */
@Stable
internal class ReactorsListState(
    private val repo: CommentsRepository,
    private val threadChatId: Long,
    private val messageId: Long,
    initialKind: ReactionKind?,
    private val scope: CoroutineScope,
) {
    var kind by mutableStateOf(initialKind)
        private set
    var rows by mutableStateOf<ImmutableList<Reactor>>(persistentListOf())
        private set
    /** TDLib's total for the current filter; null until the first page lands. */
    var totalCount by mutableStateOf<Int?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var exhausted by mutableStateOf(false)
        private set
    private var nextOffset = ""
    private var job: Job? = null

    fun select(newKind: ReactionKind?) {
        if (newKind == kind) return
        job?.cancel()
        kind = newKind
        rows = persistentListOf()
        totalCount = null
        nextOffset = ""
        exhausted = false
        failed = false
        loading = false
        loadMore()
    }

    fun retry() {
        failed = false
        loadMore()
    }

    fun loadMore() {
        if (loading || exhausted || failed) return
        val requestKind = kind
        val offset = nextOffset
        loading = true
        job = scope.launch {
            val result = repo.reactors(threadChatId, messageId, requestKind, offset)
            if (requestKind != kind) return@launch
            result
                .onSuccess { page ->
                    val seen = rows.mapTo(HashSet()) { it.rowKey() }
                    rows = (rows + page.reactors.filter { seen.add(it.rowKey()) }).toImmutableList()
                    totalCount = page.totalCount
                    nextOffset = page.nextOffset
                    exhausted = page.nextOffset.isEmpty()
                }
                .onFailure { if (it is CancellationException) throw it else failed = true }
            loading = false
        }
    }
}

/** A sender can leave more than one reaction (Premium), so the row key carries both. */
private fun Reactor.rowKey(): String = when (val s = sender) {
    is SenderRef.User -> "u${s.userId}"
    is SenderRef.Chat -> "c${s.chatId}"
} + ":" + kind.stableKey

/**
 * "Who reacted" sheet for a comment — Telegram groups' reactor list. A title with the total, a
 * filter row (all / each reaction) when it was opened for every reaction, then one row per
 * reaction: face, name and the reaction at the row end. Tapping a person opens their profile the
 * way a comment author does; a chat sender (a channel reacting as itself) opens that chat the
 * way a channel author does. Pages load as the reader nears the end of the list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReactorsSheet(
    repo: CommentsRepository,
    request: ReactorsRequest,
    onOpenChat: (chatId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(request.threadChatId, request.messageId) {
        ReactorsListState(repo, request.threadChatId, request.messageId, request.kind, scope)
    }
    LaunchedEffect(state) { state.loadMore() }
    val opener = LocalUserProfileOpener.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state) {
        // Emits the row count (plus the in-flight flag) while the reader is near the end,
        // not a plain "near the end" boolean: a boolean latched `true` on the empty first
        // frame and never changed again when a short page left the list still near its
        // end, so paging stalled after page one. Keying on the count re-fires after every
        // page that lands; the loading flag re-fires after a page that added no new rows
        // (all duplicates) so paging still moves on. The empty list (before the first
        // page, or right after a filter switch) is skipped — [ReactorsListState.select]
        // and the initial effect load that page themselves.
        snapshotFlow {
            val size = state.rows.size
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (size > 0 && last >= size - PAGE_PREFETCH_DISTANCE) size to state.loading else null
        }
            .distinctUntilChanged()
            .filter { it != null && !it.second }
            .collect { state.loadMore() }
    }
    val filterKinds = remember(request.reactions) {
        request.reactions.items.filter { it.kind !is ReactionKind.Paid }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandedSheetState(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = HortaySpacing.lg)) {
            val total = state.totalCount
            Text(
                text = if (total != null) {
                    pluralStringResource(R.plurals.reactions_sheet_title, total, total)
                } else {
                    stringResource(R.string.reactions_title)
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = SHEET_H_PADDING, vertical = HortaySpacing.sm),
            )
            if (request.kind == null && filterKinds.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = SHEET_H_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(HortaySpacing.sm),
                ) {
                    FilterChip(
                        selected = state.kind == null,
                        onClick = { state.select(null) },
                        label = { Text(stringResource(R.string.folder_all)) },
                    )
                    filterKinds.forEach { item ->
                        FilterChip(
                            selected = state.kind == item.kind,
                            onClick = { state.select(item.kind) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ReactionGlyph(item.kind)
                                    Spacer(Modifier.width(HortaySpacing.xs))
                                    Text(formatViews(item.count))
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.padding(top = HortaySpacing.sm))
            }

            val showLoading = rememberDeferredLoading(
                pending = state.loading && state.rows.isEmpty(),
                key = state.kind,
                graceMs = SCREEN_MOUNT_GRACE_MS,
            )
            when {
                state.rows.isEmpty() && state.failed -> ReactorsMessage(
                    title = stringResource(R.string.reactions_load_failed),
                    body = stringResource(R.string.comments_load_failed_body),
                    actionLabel = stringResource(R.string.comments_retry),
                    onAction = state::retry,
                )
                state.rows.isEmpty() && state.exhausted -> ReactorsMessage(
                    title = stringResource(R.string.reactions_empty),
                )
                state.rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = LOADING_MIN_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showLoading) LoadingIndicator()
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    items(state.rows, key = { it.rowKey() }) { reactor ->
                        ReactorRow(
                            reactor = reactor,
                            onClick = {
                                onDismiss()
                                when (val s = reactor.sender) {
                                    is SenderRef.User -> opener.open(s.userId)
                                    is SenderRef.Chat -> onOpenChat(s.chatId)
                                }
                            },
                        )
                    }
                    if (state.failed) {
                        item(key = "retry") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = state::retry) {
                                    Text(stringResource(R.string.comments_retry))
                                }
                            }
                        }
                    } else if (state.loading) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(HortaySpacing.md),
                                contentAlignment = Alignment.Center,
                            ) { LoadingIndicator() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactorRow(reactor: Reactor, onClick: () -> Unit) {
    val face = reactor.face
    val name = face?.name.orEmpty()
    val emoji = (reactor.kind as? ReactionKind.Emoji)?.text
    val description = if (emoji != null) stringResource(R.string.reactions_row_description, name, emoji) else name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = SHEET_H_PADDING, vertical = HortaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TdAvatar(
                name = name,
                thumb = face?.thumb,
                fileId = face?.fileId,
                size = AVATAR_SIZE,
                textStyle = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(HortaySpacing.md))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HortaySpacing.md))
            ReactionGlyph(reactor.kind)
        }
    }
}

/** The reaction's own artwork: Telegram's emoji glyph, a custom-emoji sticker, or the star. */
@Composable
private fun ReactionGlyph(kind: ReactionKind) {
    when (kind) {
        is ReactionKind.Emoji -> ReactionEmojiGlyph(emoji = kind.text, style = MaterialTheme.typography.titleMedium)
        is ReactionKind.CustomEmoji -> CustomEmojiInlineView(
            customEmojiId = kind.customEmojiId,
            modifier = Modifier.size(GLYPH_SIZE),
            tintColor = MaterialTheme.colorScheme.onSurface,
        )
        ReactionKind.Paid -> Text("⭐", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReactorsMessage(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LOADING_MIN_HEIGHT)
            .padding(horizontal = SHEET_H_PADDING, vertical = HortaySpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Spacer(Modifier.padding(top = HortaySpacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.padding(top = HortaySpacing.sm))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private val SHEET_H_PADDING = 24.dp
private val ROW_MIN_HEIGHT = 56.dp
private val AVATAR_SIZE = 40.dp
private val GLYPH_SIZE = 22.dp
private val LOADING_MIN_HEIGHT = 160.dp

/** Load the next page once the last visible row is this close to the end of what is loaded. */
private const val PAGE_PREFETCH_DISTANCE = 10
