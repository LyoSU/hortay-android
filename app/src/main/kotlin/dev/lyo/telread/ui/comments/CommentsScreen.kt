package dev.lyo.telread.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.telread.data.AlbumItem
import dev.lyo.telread.data.Comment
import dev.lyo.telread.data.CommentRow
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.media.LocalMediaViewer
import dev.lyo.telread.ui.media.TdAvatar
import dev.lyo.telread.ui.media.toAlbumItems
import dev.lyo.telread.ui.timeline.PostBody
import dev.lyo.telread.ui.timeline.PostCard
import dev.lyo.telread.ui.timeline.PostInteractions
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
    val pinnedPostInteractions = remember(viewer) {
        PostInteractions(
            onMediaClick = { p, idx -> viewer.openFor(p.content, idx) },
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "back")
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
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }

            when (val s = state) {
                CommentsRepository.ThreadState.Loading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is CommentsRepository.ThreadState.Ready -> items(items = s.rows, key = { it.comment.id }) { row ->
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
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
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
private fun CommentNode(row: CommentRow, onMediaClick: (List<AlbumItem>, Int) -> Unit) {
    val indent = (row.depth * INDENT_DP).dp
    Row(modifier = Modifier.fillMaxWidth()) {
        if (indent > 0.dp) {
            Box(
                modifier = Modifier
                    .padding(start = 24.dp)
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

@Composable
private fun CommentBubble(
    row: CommentRow,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val comment = row.comment
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommentAvatar(comment)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatRelative(comment.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // Same content renderer as posts — text + entities, media, polls, stickers, etc.
        // onMediaClick lets photos/videos/animations open in the same full-screen viewer
        // we use on the timeline.
        PostBody(
            content = comment.content,
            onMediaClick = { _, idx ->
                comment.content.toAlbumItems()?.let { items -> onMediaClick(items, idx) }
            },
        )

        if (comment.reactions.totalCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = comment.reactions.totalCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommentAvatar(comment: Comment) {
    TdAvatar(
        name = comment.authorName,
        thumb = comment.avatarThumb,
        fileId = comment.avatarFileId,
        size = 36.dp,
        textStyle = MaterialTheme.typography.titleSmall,
    )
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

private const val INDENT_DP = 16
