package dev.lyo.telread.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.telread.data.AlbumItem
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.ThreadRow
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.icons.Symbol
import dev.lyo.telread.ui.media.LocalMediaViewer
import dev.lyo.telread.ui.media.TdAvatar
import dev.lyo.telread.ui.media.toAlbumItems
import dev.lyo.telread.ui.timeline.PostBody
import dev.lyo.telread.ui.timeline.PostCard
import dev.lyo.telread.ui.timeline.PostInteractions
import dev.lyo.telread.ui.timeline.ReactionChip
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Reddit/Twitter-style discussion overlay with live updates. While this screen is on top
 * we tell TDLib that the linked discussion chat is "open" so view counts register and new
 * messages stream in via the shared updates flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: TimelinePost,
    repo: CommentsRepository,
    onDismiss: () -> Unit,
    onChannelClick: (TimelinePost) -> Unit = {},
) {
    // For an album, all sibling ids are candidates — the thread carrier may be any of
    // them. For a standalone post the only candidate is post.id.
    val candidateIds = remember(post.id, post.albumMessageIds) {
        post.albumMessageIds.ifEmpty { listOf(post.id) }
    }
    val state by remember(post.chatId, candidateIds) {
        repo.observeThread(post.chatId, candidateIds)
    }.collectAsStateWithLifecycle(initialValue = CommentsRepository.ThreadState.Loading)

    val viewer = LocalMediaViewer.current
    val pinnedPostInteractions = remember(viewer, onChannelClick) {
        PostInteractions(
            onMediaClick = { p, idx -> viewer.openFor(p.content, idx) },
            onChannelClick = onChannelClick,
        )
    }

    // Tell TDLib the linked discussion chat is "open" while this screen is alive; close it
    // when the screen leaves composition. awaitCancellation + NonCancellable guarantees the
    // close call survives even on rapid back-press.
    val activeThreadChatId = (state as? CommentsRepository.ThreadState.Ready)?.threadChatId
    LaunchedEffect(activeThreadChatId) {
        val tid = activeThreadChatId ?: return@LaunchedEffect
        repo.openThread(tid)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            withContext(NonCancellable) { repo.closeThread(tid) }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Обговорення", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Symbol(name = "arrow_back", contentDescription = "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "post") {
                PostCard(post = post, interactions = pinnedPostInteractions, clickable = false, expanded = true)
            }

            // Loading / counter / error label live above the list. For Error we still
            // render a friendlier hero block below — the small primary-coloured label was
            // easy to miss on channels without a linked discussion group.
            item(key = "label") {
                val label = when (val s = state) {
                    CommentsRepository.ThreadState.Loading -> "Завантаження…"
                    is CommentsRepository.ThreadState.Ready -> if (s.rows.isEmpty()) "Поки немає коментарів."
                    else "${s.rows.size} відповідей"
                    is CommentsRepository.ThreadState.Error -> null
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            when (val s = state) {
                CommentsRepository.ThreadState.Loading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is CommentsRepository.ThreadState.Ready -> items(items = s.rows, key = { it.message.id }) { row ->
                    CommentNode(row, onMediaClick = { items, idx -> viewer.open(items, idx) })
                }
                is CommentsRepository.ThreadState.Error -> item(key = "error") {
                    NoDiscussionState(message = s.message)
                }
            }
        }
    }
}

@Composable
private fun NoDiscussionState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(
            name = "chat_bubble",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 48.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Без обговорення",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun CommentNode(row: ThreadRow, onMediaClick: (List<AlbumItem>, Int) -> Unit) {
    val indent = (row.depth * INDENT_DP).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (indent > 0.dp) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(indent),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
                )
            }
        }
        CommentBubble(row, onMediaClick = onMediaClick, modifier = Modifier.weight(1f))
    }
}

/**
 * Threads-style flat comment: avatar in left rail, header (name + time) and body in right
 * column. No surface fill or rounded corners — just whitespace separates entries. The body
 * and reactions are rendered through the same [PostBody] / [ReactionChip] used by the feed
 * so any media type the timeline shows works here too — including new content types the
 * old comment renderer missed (animated emoji, checklists, expired-media placeholders).
 */
@Composable
private fun CommentBubble(
    row: ThreadRow,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = row.message
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        TdAvatar(
            name = message.senderName,
            thumb = message.avatarThumb,
            fileId = message.avatarFileId,
            size = 36.dp,
            textStyle = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                if (message.editDate > 0L) {
                    Symbol(
                        name = "edit",
                        contentDescription = "edited",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 13.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = formatRelative(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Quoted reply now flows from the unified MessageMapper — same renderer as
            // feed-post replies, so a comment that quotes another thread reply gets a
            // proper author + excerpt block instead of just an indented bubble.
            message.reply?.let {
                Spacer(Modifier.height(6.dp))
                ReplyBlock(it.authorName, it.excerpt)
            }

            Spacer(Modifier.height(6.dp))
            PostBody(
                content = message.content,
                onMediaClick = { _, idx ->
                    message.content.toAlbumItems()?.let { items -> onMediaClick(items, idx) }
                },
                expanded = true,
            )

            if (message.reactions.items.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    message.reactions.items.forEachIndexed { idx, item ->
                        if (idx > 0) Spacer(Modifier.width(6.dp))
                        ReactionChip(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBlock(authorName: String, excerpt: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(1.dp),
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> "щойно"
        diffMin < 60 -> "${diffMin}хв"
        diffMin < 60 * 24 -> "${diffMin / 60}год"
        diffMin < 60 * 24 * 7 -> "${diffMin / (60 * 24)}д"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private const val INDENT_DP = 12
