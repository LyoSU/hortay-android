package dev.lyo.hortay.ui.comments

import androidx.activity.BackEventCompat
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.ThreadRow
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalMediaViewer
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.toAlbumItems
import dev.lyo.hortay.ui.timeline.PostBody
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import dev.lyo.hortay.ui.timeline.ReactionChip
import dev.lyo.hortay.ui.timeline.label
import dev.lyo.hortay.ui.timeline.symbolName
import java.text.DateFormat
import java.util.Date

/**
 * Reddit/Twitter-style discussion overlay with live updates. While this screen is on top
 * we tell TDLib that the linked discussion chat is "open" so view counts register and new
 * messages stream in via the shared updates flow.
 *
 * [backProgress] / [backSwipeEdge] drive the Material 3 predictive-back animation: the
 * overlay translates ~10% of the screen width in the swipe direction, scales down to 0.9
 * and fades to 0.7 alpha as the user pulls. The transform pivot is anchored to the swipe
 * edge so the screen feels "pinned" to the user's thumb while the opposite edge recedes.
 * MainScaffold owns the [Animatable] driving these values; we receive a plain Float so
 * this composable stays trivially testable and reusable from other entry points.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: TimelinePost,
    repo: CommentsRepository,
    onDismiss: () -> Unit,
    onChannelClick: (TimelinePost) -> Unit = {},
    backProgress: Float = 0f,
    backSwipeEdge: Int = BackEventCompat.EDGE_LEFT,
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

    // Open/close of the thread chat is owned by CommentsRepository.threadFlow now —
    // TDLib needs the chat opened *before* the first GetMessageThreadHistory so updates
    // stream in and the daemon prioritises loading. Doing it here meant we only opened
    // *after* the bootstrap finished, paying the cold-cache penalty on the first call.

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Material 3 predictive-back transform. Pivot lives at the swipe edge so the screen
    // visibly "hinges" away from the user's thumb.
    //   peek phase (0..1): translate up to 10% width, scale to 0.9, alpha to 0.7 — matches
    //     the M3 spec for cross-pane back motion.
    //   exit phase  (1..2): MainScaffold drives this leg after the user commits. Translates
    //     the rest of the way off-screen, contracts to 0.85 and fades to alpha=0 so the
    //     overlay actually "leaves" before being removed from composition. Without it the
    //     screen would freeze at peek and then snap away.
    val backDirection = if (backSwipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
    val backOriginX = if (backSwipeEdge == BackEventCompat.EDGE_LEFT) 0f else 1f
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (backProgress > 0f) {
                    val peek = backProgress.coerceAtMost(1f)
                    val exit = (backProgress - 1f).coerceAtLeast(0f)
                    translationX = backDirection * size.width * (0.10f * peek + 0.90f * exit)
                    val s = 1f - 0.10f * peek - 0.05f * exit
                    scaleX = s
                    scaleY = s
                    alpha = (1f - 0.30f * peek) * (1f - exit)
                    transformOrigin = TransformOrigin(backOriginX, 0.5f)
                }
            }
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.comments_title), style = MaterialTheme.typography.titleLarge) },
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
                    CommentsRepository.ThreadState.Loading -> stringResource(R.string.comments_loading)
                    is CommentsRepository.ThreadState.Ready -> if (s.rows.isEmpty()) stringResource(R.string.comments_no_comments)
                    else stringResource(R.string.comments_replies, s.rows.size)
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
            text = stringResource(R.string.comments_no_thread),
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

            // Show the quote card ONLY for explicit user-selected quotes. Plain reply
            // relations are structural noise here — every top-level comment is a reply
            // to the host post, and every nested reply already sits visually under its
            // parent comment via the thread indentation. Surfacing a quote card for those
            // would just duplicate context the layout already conveys.
            message.reply?.takeIf { it.isQuote }?.let {
                Spacer(Modifier.height(6.dp))
                ReplyBlock(it)
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
private fun ReplyBlock(reply: ReplyPreview) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = reply.authorName,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val bodyText = reply.excerpt.ifBlank { reply.mediaKind.label() }
            if (bodyText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (reply.excerpt.isBlank()) {
                        reply.mediaKind.symbolName()?.let { name ->
                            Symbol(
                                name = name,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 12.dp,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        reply.mediaThumb?.let { thumb ->
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// Label/symbol mapping is shared with the feed quote card via dev.lyo.hortay.ui.timeline
// .label() / .symbolName() — see ReplyKindResources.kt.

@Composable
internal fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> stringResource(R.string.time_just_now)
        diffMin < 60 -> stringResource(R.string.time_minutes_short, diffMin.toInt())
        diffMin < 60 * 24 -> stringResource(R.string.time_hours_short, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> stringResource(R.string.time_days_short, (diffMin / (60 * 24)).toInt())
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private const val INDENT_DP = 12
