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
import dev.lyo.telread.data.Comment
import dev.lyo.telread.data.CommentRow
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.TdMedia
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.media.TdMediaImage
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
    val state by remember(post.chatId, post.id) {
        repo.observeThread(post.chatId, post.id)
    }.collectAsStateWithLifecycle(initialValue = CommentsRepository.ThreadState.Loading)

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
                PostCard(post = post, interactions = PostInteractions.Noop, clickable = false, expanded = true)
            }

            item(key = "label") {
                val label = when (val s = state) {
                    CommentsRepository.ThreadState.Loading -> "Завантаження…"
                    is CommentsRepository.ThreadState.Ready -> if (s.rows.isEmpty()) "Поки немає коментарів."
                    else "${s.rows.size} відповідей"
                    is CommentsRepository.ThreadState.Error -> s.message
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }

            when (val s = state) {
                CommentsRepository.ThreadState.Loading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is CommentsRepository.ThreadState.Ready -> items(items = s.rows, key = { it.comment.id }) { row ->
                    CommentNode(row)
                }
                is CommentsRepository.ThreadState.Error -> Unit
            }
        }
    }
}

@Composable
private fun CommentNode(row: CommentRow) {
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
        CommentBubble(row, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CommentBubble(row: CommentRow, modifier: Modifier = Modifier) {
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
        PostBody(content = comment.content)

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
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (comment.avatarFileId != null) {
            TdMediaImage(
                media = TdMedia(comment.avatarFileId, 0, 0, null),
                contentDescription = comment.authorName,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = comment.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
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

private const val INDENT_DP = 16
