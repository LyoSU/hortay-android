package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelContext
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.SenderVerification
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.theme.asComposeShape
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
    /**
     * When false, the bottom HorizontalDivider is omitted. Used by [ThreadedPostPair] so the
     * parent post visually flows into the reply without a divider between them.
     */
    showDivider: Boolean = true,
    /**
     * When true, the inline `ReplyBlock` blockquote is hidden. Used by [ThreadedPostPair] for
     * the reply post — its parent is rendered as a full card above, so the small blockquote
     * preview would just duplicate that.
     */
    suppressInlineReply: Boolean = false,
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
                avatarUrl = post.avatarUrl,
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

                // "у Channel" subtitle for personal-author posts (TDLib's new channel mode
                // where admins post under their own identity). Tap behaves the same as the
                // avatar/header — switches the feed filter to the host channel.
                post.channelContext?.let { ctx ->
                    Spacer(Modifier.height(2.dp))
                    InChannelChip(
                        ctx = ctx,
                        onClick = { interactions.onChannelClick(post) },
                    )
                }

                post.forwardOrigin?.let {
                    Spacer(Modifier.height(6.dp))
                    ForwardChip(
                        origin = it,
                        onClick = if (it is ForwardOrigin.Channel || it is ForwardOrigin.Chat) {
                            { interactions.onForwardSourceClick(post) }
                        } else null,
                    )
                }

                if (!suppressInlineReply) {
                    post.reply?.let {
                        Spacer(Modifier.height(8.dp))
                        ReplyBlock(it, onClick = { interactions.onQuotedSourceClick(post) })
                    }
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
                        onReactionTap = { item -> interactions.onReactionToggle(post, item) },
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
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
private fun Avatar(
    name: String,
    thumb: ByteArray?,
    fileId: Int?,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        TdAvatar(
            name = name,
            thumb = thumb,
            fileId = fileId,
            size = 40.dp,
            background = avatarBg(name),
            remoteUrl = avatarUrl,
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
            .clickable(role = Role.Button, onClick = onChannelClick),
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
                contentDescription = stringResource(R.string.post_badge_pinned),
                tint = MaterialTheme.colorScheme.primary,
                size = 14.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (editDate > 0L) {
            Symbol(
                name = "edit",
                contentDescription = stringResource(R.string.post_badge_edited),
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
            text = stringResource(R.string.post_translated_chip),
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

/**
 * Tiny "у &lt;Channel&gt;" affordance shown beneath the author name for personal-author
 * posts. Pip avatar (16 dp) + name in `onSurfaceVariant`; clickable hand-off lets the
 * reader jump to the host channel filter. We deliberately avoid a chip background — at
 * this size a fill would compete with the surrounding text density.
 */
@Composable
private fun InChannelChip(ctx: ChannelContext, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TdAvatar(
            name = ctx.name,
            thumb = ctx.avatarThumb,
            fileId = ctx.avatarFileId,
            size = 16.dp,
            background = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.post_in_channel, ctx.name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ForwardChip(origin: ForwardOrigin, onClick: (() -> Unit)?) {
    Row(
        modifier = if (onClick != null) {
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick)
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
            text = stringResource(R.string.post_forwarded_from, forwardLabel(origin)),
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
 * Twitter-style quote card surfacing the post being replied to. Visual contract:
 *   • subtle surfaceContainer fill + 12 dp rounded corners
 *   • left accent bar (4 dp) in primary tint — signals "tap to open the original"
 *   • author + (if present) excerpt OR a kind label like "Фото" / "Відео" when the parent
 *     is media-only with no caption — Telegram's own clients do exactly this so the user
 *     immediately knows whether the reply is referring to text, a photo, voice, etc.
 *   • optional 44 dp thumbnail on the right — shown when TDLib gave us a parent media
 *     snapshot (photos, videos, animations, video notes, stickers, document covers).
 *
 * The whole card is clickable (`onClick`) and dispatches to [PostInteractions.onQuotedSourceClick].
 * TimelineScreen wires that to switch the channel filter when the parent is in the loaded feed,
 * or to a `tg://openmessage` deep link otherwise.
 */
@Composable
private fun ReplyBlock(reply: ReplyPreview, onClick: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    // Reply preview reads as Tier-C (dense reading inside the card) — uses
    // the medium shape token so the bumped HortayShapes scale (18 dp) ripples
    // through here without per-call-site edits.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick)
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = reply.authorName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Resolve a body line. Priority:
            //   1. The actual excerpt (quote text, or first line of the parent's text).
            //   2. A localized kind label ("Фото", "Голосове" …) when the parent is media-only.
            //   3. Skip the second line entirely — should be rare (text post with no text).
            val bodyText = reply.excerpt.ifBlank { reply.mediaKind.label() }
            if (bodyText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (reply.excerpt.isBlank()) {
                        // Show a tiny kind icon when the text is the kind label itself —
                        // matches Telegram's "[icon] Photo / Video / Voice" layout.
                        reply.mediaKind.symbolName()?.let { name ->
                            Symbol(
                                name = name,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 14.dp,
                            )
                            Spacer(Modifier.width(6.dp))
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
        // Thumbnail (when available) — 44 dp square at the trailing edge.
        reply.mediaThumb?.let { thumb ->
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
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

// Label / symbol mappings live in `ReplyKindResources.kt` so PostCard and CommentsScreen
// share one source of truth — keeps icons and wording in lockstep across surfaces.

/**
 * Stats + per-emoji reaction chips on a single horizontal-scrollable line. Twitter/Reddit-style:
 * tap is reserved for the post itself, secondary actions live in the long-press sheet so this row
 * stays read-only and uncluttered.
 */
@Composable
private fun ActionRow(
    views: Int,
    commentCount: Int?,
    reactions: dev.lyo.hortay.data.Reactions,
    onCommentsClick: () -> Unit,
    onReactionTap: (ReactionItem) -> Unit = {},
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
                ReactionChip(item, onClick = { onReactionTap(item) })
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
 * Reaction chip — industry-canonical "pill stadium with emoji + count" structure.
 * Telegram, Discord, Slack, Reddit, iMessage all use the same primitive: a single
 * rounded container with the emoji glyph followed by its count. M3 Expressive's
 * contribution is the **three-state corner-radius morph** that gives the chip
 * tactile feedback without breaking the familiar layout:
 *
 *   - **Rest**: 14 dp corners — soft rounded rectangle.
 *   - **Pressed**: 6 dp corners — squishes inward when the user holds, the
 *     compressed-under-thumb tactile cue documented in M3 Expressive's
 *     interaction-states spec.
 *   - **Selected (own reaction)**: 24 dp corners — fully pill-rounded, the
 *     persistent "this one is yours" affordance. Container also crossfades to
 *     `tertiaryContainer` (primary reserved for CTAs).
 *
 * Cookie/Burst/Heart polygons were considered but Google's Expressive guidance
 * reserves them for 1:1 elements (FAB, IconButton, avatar, hero badge).
 * Non-uniformly scaling them to a wide chip deforms the character ridges into
 * elongated ovals — corner-radius morph on a stadium stays canonical at any
 * aspect, including 3-digit counts.
 */
@Composable
internal fun ReactionChip(item: ReactionItem, onClick: (() -> Unit)? = null) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cornerRadius by androidx.compose.animation.core.animateDpAsState(
        targetValue = when {
            isPressed -> 6.dp
            item.isChosen -> 24.dp
            else -> 14.dp
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "reaction-corner",
    )
    val container by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-bg",
    )
    val countColor by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-fg",
    )
    val tintForCustom by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-tint",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(container, shape)
            .let {
                if (onClick != null) it.clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onClick,
                ) else it
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val k = item.kind) {
            is ReactionKind.Emoji -> Text(
                text = k.text,
                style = MaterialTheme.typography.titleMedium,
            )
            is ReactionKind.CustomEmoji -> CustomEmojiInlineView(
                customEmojiId = k.customEmojiId,
                modifier = Modifier.size(20.dp),
                tintColor = tintForCustom,
                contentDescription = null,
            )
        }
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
    // StatPill follows the same Expressive vocabulary as the reaction chip — when
    // tappable, it reads as a real affordance with a polygon-shaped ripple. The
    // Pill silhouette (subtly flattened ellipse) matches the new-posts pill in the
    // viewer chrome, so all "stat-style" affordances share one visual idiom.
    val tappableShape = HortayExpressive.Pill.asComposeShape()
    Row(
        modifier = if (onClick != null) {
            Modifier
                .clip(tappableShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp)
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
                label = stringResource(if (isBookmarked) R.string.post_unsave else R.string.post_save),
                onClick = { runAndDismiss { interactions.onBookmarkClick(post) } },
            )
            if (post.content.captionPlain.isNotBlank()) {
                SheetItem(
                    symbol = "content_copy",
                    label = stringResource(R.string.post_copy_text),
                    onClick = { runAndDismiss { interactions.onCopyClick(post) } },
                )
                if (interactions.translateEnabled) {
                    val translated = interactions.isTranslated(post)
                    SheetItem(
                        symbol = "translate",
                        label = stringResource(if (translated) R.string.post_show_original else R.string.post_translate),
                        onClick = {
                            runAndDismiss {
                                if (translated) interactions.onClearTranslationClick(post)
                                else interactions.onTranslateClick(post)
                            }
                        },
                    )
                }
            }
            SheetItem(
                symbol = "ios_share",
                label = stringResource(R.string.post_share),
                onClick = { runAndDismiss { interactions.onShareClick(post) } },
            )
            SheetItem(
                symbol = "open_in_new",
                label = stringResource(R.string.post_open_telegram),
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
            .clickable(role = Role.Button, onClick = onClick)
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

@Composable
private fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> stringResource(R.string.time_just_now)
        diffMin < 60 -> stringResource(R.string.time_minutes_short, diffMin.toInt())
        diffMin < 60 * 24 -> stringResource(R.string.time_hours_short, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> stringResource(R.string.time_days_short, (diffMin / (60 * 24)).toInt())
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private fun formatViews(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "%.1fK".format(count / 1_000.0).trimEnd('0').trimEnd('.')
    else -> "%.1fM".format(count / 1_000_000.0).trimEnd('0').trimEnd('.')
}

/**
 * Threads-style stacked pair: [parent] post on top, [reply] post below, joined by a thin
 * vertical connector line aligned with the avatar column. Used by the feed when a post
 * replies to another post that's also present in the loaded feed — TimelineScreen merges
 * them into a single LazyColumn slot and suppresses the parent's standalone entry.
 *
 * Why drawBehind + onSizeChanged instead of `Modifier.height(IntrinsicSize.Min)` with a
 * `weight(1f)` filler line in a left rail: PostBody contains a LazyRow (album gallery),
 * and LazyRow does not support intrinsic measurement — IntrinsicSize.Min would crash.
 * The connector positions are deterministic from PostCard's known padding (16.dp horizontal,
 * 14.dp vertical) and avatar size (40.dp), so we just measure the parent's row height once
 * and draw the line in the wrapping column's draw layer.
 */
@Composable
fun ThreadedPostPair(
    parent: TimelinePost,
    reply: TimelinePost,
    interactions: PostInteractions = PostInteractions.Noop,
) {
    var parentHeightPx by remember { mutableIntStateOf(0) }
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (parentHeightPx == 0) return@drawBehind
                // Avatar geometry mirrors PostCard's outer Row:
                //   horizontal padding 16.dp, then a 40.dp avatar at row start
                //   → avatar centerX = 16 + 20 = 36.dp
                //   vertical padding 14.dp top → avatar bottom = 14 + 40 = 54.dp from row top
                // We pull the line ends 6.dp inside the avatars so the line doesn't quite
                // touch the circular edge — looks crisper at 1× DPI.
                val avatarCenterX = THREAD_AVATAR_CENTER_X.toPx()
                val parentAvatarBottom = (THREAD_ROW_VERTICAL + THREAD_AVATAR_SIZE + THREAD_LINE_INSET).toPx()
                val replyAvatarTop = parentHeightPx.toFloat() +
                    (THREAD_ROW_VERTICAL - THREAD_LINE_INSET).toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(avatarCenterX, parentAvatarBottom),
                    end = Offset(avatarCenterX, replyAvatarTop),
                    strokeWidth = THREAD_LINE_WIDTH.toPx(),
                )
            },
    ) {
        Box(modifier = Modifier.onSizeChanged { parentHeightPx = it.height }) {
            PostCard(
                post = parent,
                interactions = interactions,
                showDivider = false,
            )
        }
        PostCard(
            post = reply,
            interactions = interactions,
            suppressInlineReply = true,
        )
    }
}

private val THREAD_ROW_VERTICAL = 14.dp
private val THREAD_AVATAR_SIZE = 40.dp
private val THREAD_AVATAR_CENTER_X = 36.dp // 16.dp horizontal padding + 20.dp avatar half-width
private val THREAD_LINE_INSET = 6.dp
private val THREAD_LINE_WIDTH = 2.dp
