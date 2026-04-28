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
import dev.lyo.telread.data.CommentRow
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.text.rememberAnnotatedString
import dev.lyo.telread.ui.timeline.PostCard
import dev.lyo.telread.ui.timeline.PostInteractions
import java.text.DateFormat
import java.util.Date

private sealed interface ThreadState {
    data object Loading : ThreadState
    data class Ready(val rows: List<CommentRow>) : ThreadState
    data class Empty(val reason: String) : ThreadState
}

/**
 * Reddit/Twitter-style discussion overlay.
 *   • Original post pinned at the top (read-only).
 *   • Replies rendered as a flattened tree — each row carries `depth`, drawn with a left
 *     indent + a thin vertical connector line so chains read like Reddit.
 *   • Empty / unsupported channels surface a friendly explainer instead of an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: TimelinePost,
    repo: CommentsRepository,
    onDismiss: () -> Unit,
) {
    var state by remember(post.id) { mutableStateOf<ThreadState>(ThreadState.Loading) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(post.id) {
        repo.fetchThread(post.chatId, post.id)
            .onSuccess { rows ->
                state = if (rows.isEmpty()) ThreadState.Empty("Поки немає коментарів.")
                else ThreadState.Ready(rows)
            }
            .onFailure {
                state = ThreadState.Empty("У цьому каналі обговорення не доступне.")
            }
    }

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
                PostCard(post = post, interactions = PostInteractions.Noop, clickable = false)
            }

            item(key = "label") {
                Text(
                    text = when (val s = state) {
                        ThreadState.Loading -> "Завантаження…"
                        is ThreadState.Ready -> "${s.rows.size} відповідей"
                        is ThreadState.Empty -> s.reason
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }

            when (val s = state) {
                ThreadState.Loading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ThreadState.Empty -> Unit
                is ThreadState.Ready -> items(items = s.rows, key = { it.comment.id }) { row ->
                    CommentNode(row)
                }
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
                    .width(indent)
                    .fillMaxHeight(),
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = comment.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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

        if (comment.text.text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = rememberAnnotatedString(comment.text),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }

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
