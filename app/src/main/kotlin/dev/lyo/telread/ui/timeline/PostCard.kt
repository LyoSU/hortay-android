package dev.lyo.telread.ui.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lyo.telread.data.ForwardOrigin
import dev.lyo.telread.data.ReactionItem
import dev.lyo.telread.data.ReplyPreview
import dev.lyo.telread.data.SenderVerification
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.ui.icons.Symbol
import dev.lyo.telread.ui.media.TdAvatar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(
    post: TimelinePost,
    interactions: PostInteractions = PostInteractions.Noop,
    clickable: Boolean = true,
    expanded: Boolean = false,
) {
    var sheetOpen by remember { mutableStateOf(false) }

    // Hand each section ONLY the primitive fields it actually paints. When TDLib emits
    // an UpdateMessageInteractionInfo and the post's `views` change, Compose passes a
    // new TimelinePost instance into PostCard — but the per-section args (avatar fields,
    // header fields, body content reference) remain identical, so Compose's skipping
    // logic prunes their recompositions. Without this split every interaction-info hit
    // re-runs the whole card.

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = clickable,
                    onClick = { interactions.onPostClick(post) },
                    onLongClick = { sheetOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Avatar(
                name = post.senderName,
                thumb = post.avatarThumb,
                fileId = post.avatarFileId,
                onClick = { interactions.onChannelClick(post) },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                HeaderRow(
                    senderName = post.senderName,
                    authorSignature = post.authorSignature,
                    editDate = post.editDate,
                    date = post.date,
                    pinned = post.isPinned,
                    verification = post.verification,
                    onChannelClick = { interactions.onChannelClick(post) },
                )

                post.forwardOrigin?.let {
                    Spacer(Modifier.height(6.dp))
                    ForwardChip(
                        origin = it,
                        onClick = if (it is ForwardOrigin.Channel || it is ForwardOrigin.Chat) {
                            { interactions.onForwardSourceClick(post) }
                        } else null,
                    )
                }

                post.reply?.let {
                    Spacer(Modifier.height(8.dp))
                    ReplyBlock(it)
                }

                Spacer(Modifier.height(8.dp))
                val translation = interactions.translationFor(post)
                if (translation != null) {
                    TranslationChip(onDismiss = { interactions.onClearTranslationClick(post) })
                    Spacer(Modifier.height(8.dp))
                }
                PostBody(
                    content = post.content,
                    onMediaClick = { _, idx -> interactions.onMediaClick(post, idx) },
                    expanded = expanded,
                    translation = translation,
                )

                if (post.views > 0 || post.commentCount != null || post.reactions.items.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ActionRow(
                        views = post.views,
                        commentCount = post.commentCount,
                        reactions = post.reactions,
                        onCommentsClick = { interactions.onPostClick(post) },
                        onReactionTap = { emoji -> interactions.onReactionToggle(post, emoji) },
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }

    if (sheetOpen) {
        PostActionSheet(
            post = post,
            interactions = interactions,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun Avatar(name: String, thumb: ByteArray?, fileId: Int?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        TdAvatar(
            name = name,
            thumb = thumb,
            fileId = fileId,
            size = 40.dp,
            background = avatarBg(name),
        )
    }
}

@Composable
private fun avatarBg(name: String) = run {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    palette[(name.hashCode().rem(palette.size) + palette.size) % palette.size]
}

/**
 * Single-line Threads-style header: title + optional signature merged into one AnnotatedString
 * so they ellipsis as a unit, then time (and optional edit marker) pinned right. Title weight=1
 * yields space to the trailing time block.
 */
@Composable
private fun HeaderRow(
    senderName: String,
    authorSignature: String?,
    editDate: Long,
    date: Long,
    pinned: Boolean,
    verification: SenderVerification?,
    onChannelClick: () -> Unit,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(senderName, authorSignature, titleColor, subColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = titleColor, fontWeight = FontWeight.SemiBold)) {
                append(senderName)
            }
            authorSignature?.let {
                withStyle(SpanStyle(color = subColor, fontWeight = FontWeight.Normal)) {
                    append("  ·  $it")
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onChannelClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = annotated,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            verification?.let {
                Spacer(Modifier.width(4.dp))
                VerificationBadge(it)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (pinned) {
            Symbol(
                name = "push_pin",
                contentDescription = "pinned",
                tint = MaterialTheme.colorScheme.primary,
                size = 14.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (editDate > 0L) {
            Symbol(
                name = "edit",
                contentDescription = "edited",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 14.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = formatRelative(date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Compact "Перекладено · Показати оригінал" chip rendered above the body when an active
 * translation overrides the post text. Telegram-X uses a single line in the same accent
 * colour as forward attribution; we mirror that so the affordance reads as related,
 * not as an alert.
 */
@Composable
private fun TranslationChip(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onDismiss),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = "translate",
            tint = MaterialTheme.colorScheme.tertiary,
            size = 16.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Перекладено · Показати оригінал",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * 12dp verification mark next to the channel name. Verified is a Material 3 primary check;
 * scam / fake mirror Telegram's red-pill / yellow-pill chips at miniature size so the eye
 * recognises them at a glance even without colour cues (the icon shape carries the meaning).
 */
@Composable
private fun VerificationBadge(verification: SenderVerification) {
    when (verification) {
        SenderVerification.Verified -> Symbol(
            name = "verified",
            contentDescription = "Verified",
            tint = MaterialTheme.colorScheme.primary,
            size = 16.dp,
        )
        SenderVerification.Scam -> WarningPill(
            label = "SCAM",
            color = MaterialTheme.colorScheme.error,
        )
        SenderVerification.Fake -> WarningPill(
            label = "FAKE",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun WarningPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
    }
}

@Composable
private fun ForwardChip(origin: ForwardOrigin, onClick: (() -> Unit)?) {
    Row(
        modifier = if (onClick != null) {
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = "repeat",
            tint = MaterialTheme.colorScheme.tertiary,
            size = 16.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Переслано від ${forwardLabel(origin)}",
            style = MaterialTheme.typography.labelMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun forwardLabel(origin: ForwardOrigin): String = when (origin) {
    is ForwardOrigin.User -> origin.userName
    is ForwardOrigin.Channel -> origin.channelName + (origin.authorSignature?.let { " · $it" } ?: "")
    is ForwardOrigin.HiddenUser -> origin.senderName
    is ForwardOrigin.Chat -> origin.chatName
}

/**
 * Threads-style blockquote: only a thin left bar + indented text, no surface fill.
 * IntrinsicSize.Min lets the bar's fillMaxHeight match the actual two-line text height.
 */
@Composable
private fun ReplyBlock(reply: ReplyPreview) {
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
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reply.authorName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = reply.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Stats + per-emoji reaction chips on a single horizontal-scrollable line. Twitter/Reddit-style:
 * tap is reserved for the post itself, secondary actions live in the long-press sheet so this row
 * stays read-only and uncluttered.
 */
@Composable
private fun ActionRow(
    views: Int,
    commentCount: Int?,
    reactions: dev.lyo.telread.data.Reactions,
    onCommentsClick: () -> Unit,
    onReactionTap: (emoji: String) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (views > 0) {
            StatPill("visibility", formatViews(views))
        }
        commentCount?.let { count ->
            if (views > 0) Spacer(Modifier.width(14.dp))
            StatPill(
                symbol = "chat_bubble",
                text = if (count > 0) formatViews(count) else "0",
                onClick = onCommentsClick,
            )
        }
        if (reactions.items.isNotEmpty()) {
            if (views > 0 || commentCount != null) {
                Spacer(Modifier.width(14.dp))
                VerticalSeparator()
                Spacer(Modifier.width(14.dp))
            }
            reactions.items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.width(6.dp))
                ReactionChip(item, onClick = { onReactionTap(item.emoji) })
            }
        }
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .height(18.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/**
 * Public so the comments screen can reuse the same chip styling for thread replies. Sized
 * to clear Material 3's 40dp minimum touch target without looking visually heavy: ~36dp
 * tall (12dp emoji + 18dp container padding × 2 ≈ 40dp once you add Material's automatic
 * 8dp tap-spacing).
 */
@Composable
internal fun ReactionChip(item: ReactionItem, onClick: (() -> Unit)? = null) {
    val container = if (item.isChosen) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainer
    val countColor = if (item.isChosen) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = item.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatViews(item.count),
            style = MaterialTheme.typography.labelMedium,
            color = countColor,
        )
    }
}

@Composable
private fun StatPill(
    symbol: String,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        } else Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = symbol,
            tint = tint,
            size = 22.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostActionSheet(
    post: TimelinePost,
    interactions: PostInteractions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val isBookmarked = interactions.isBookmarked(post)

    // Run the user action immediately, then animate the sheet out and tell the parent to forget us.
    fun runAndDismiss(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            SheetItem(
                symbol = "bookmark",
                label = if (isBookmarked) "Прибрати зі збережених" else "Зберегти пост",
                onClick = { runAndDismiss { interactions.onBookmarkClick(post) } },
            )
            if (post.content.captionPlain.isNotBlank()) {
                SheetItem(
                    symbol = "content_copy",
                    label = "Скопіювати текст",
                    onClick = { runAndDismiss { interactions.onCopyClick(post) } },
                )
                val translated = interactions.isTranslated(post)
                SheetItem(
                    symbol = "translate",
                    label = if (translated) "Показати оригінал" else "Перекласти",
                    onClick = {
                        runAndDismiss {
                            if (translated) interactions.onClearTranslationClick(post)
                            else interactions.onTranslateClick(post)
                        }
                    },
                )
            }
            SheetItem(
                symbol = "ios_share",
                label = "Поділитися",
                onClick = { runAndDismiss { interactions.onShareClick(post) } },
            )
            SheetItem(
                symbol = "open_in_new",
                label = "Відкрити в Telegram",
                onClick = { runAndDismiss { interactions.onOpenClick(post) } },
            )
        }
    }
}

@Composable
private fun SheetItem(
    symbol: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 22.dp,
        )
        Spacer(Modifier.width(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
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

private fun formatViews(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "%.1fK".format(count / 1_000.0).trimEnd('0').trimEnd('.')
    else -> "%.1fM".format(count / 1_000_000.0).trimEnd('0').trimEnd('.')
}
