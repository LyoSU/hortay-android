package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
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
import dev.lyo.hortay.data.isUnreadAt
import dev.lyo.hortay.data.stableKey
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.parseHashtagWithScope
import androidx.compose.runtime.CompositionLocalProvider
import dev.lyo.hortay.ui.archive.components.DeletedBadge
import dev.lyo.hortay.ui.archive.components.EditedChip
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.InlineChipCorner
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.theme.asComposeShape
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.theme.rememberPressedSelectedCornerRadius
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(
    post: TimelinePost,
    interactions: PostInteractions = PostInteractions.Noop,
    clickable: Boolean = true,
    /**
     * Whether long-press opens the action sheet (reactions / share / open). Defaults to
     * [clickable] so feed + channel cards are unchanged, but the comments anchor passes
     * `actionsEnabled = true` while `clickable = false`: tapping it would just re-open the
     * screen you're already on, yet the user still needs the reaction picker + share/open.
     */
    actionsEnabled: Boolean = clickable,
    expanded: Boolean = false,
    onTapRevisions: (TimelinePost) -> Unit = {},
) {
    var sheetOpen by remember { mutableStateOf(false) }
    // Hosted at the card level (not inside the action sheet) so it survives the
    // action sheet dismissing when the user taps "Select text".
    var selectTextOpen by remember { mutableStateOf(false) }

    // Hand each section ONLY the primitive fields it actually paints. When TDLib emits
    // an UpdateMessageInteractionInfo and the post's `views` change, Compose passes a
    // new TimelinePost instance into PostCard — but the per-section args (avatar fields,
    // header fields, body content reference) remain identical, so Compose's skipping
    // logic prunes their recompositions. Without this split every interaction-info hit
    // re-runs the whole card.

    // Highlight tint when this card is the deep-link / quote-tap scroll target. Fades
    // from primaryContainer to transparent so the user can spot the just-landed post
    // after the auto-scroll, then the card returns to its normal background. Rides
    // M3 Expressive's [MotionScheme.fastEffectsSpec] — the same spring the navbar /
    // tab-swap / button-press chains use, so the highlight pop reads as part of the
    // app's motion vocabulary rather than a one-off tween.
    val isHighlighted = dev.lyo.hortay.ui.media.LocalIsHighlightedItem.current
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 0.35f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "post-highlight",
    )
    val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = highlightAlpha)

    // Unread accent strip on the left edge. A 3 dp vertical bar in
    // [MaterialTheme.colorScheme.primary] painted via [Modifier.drawBehind] (no
    // extra layout pass) when the post's message id sits above the chat's read
    // cursor. Mirrors Reeder / Gmail's pattern — a glance-level "this is new" cue
    // that takes zero horizontal space. Hidden on expanded surfaces
    // (CommentsScreen anchor — you're reading it now) and on discussion-thread
    // replies (their per-thread read state isn't tracked through this cursor).
    //
    // Animation rides [MotionScheme.fastEffectsSpec] so a dwell-driven ack —
    // [ChatPresence.viewMessages] flips this card's strip from full alpha to
    // zero on the same spring the navbar selection / button press / connection
    // banner use. One motion vocabulary across the app.
    // Per-key cursor read: the holder's `get` registers a Compose snapshot
    // dependency on this post's chatId only, so an UpdateChatReadInbox for
    // a different chat does not invalidate this card.
    val cursor = LocalReadCursors.current[post.chatId]
    // A tombstone is terminal, not "yet to be read" — never paint the unread strip on it.
    val isUnread = !expanded && !post.isDeleted && post.parentId == null && post.isUnreadAt(cursor)
    // Twin animations on the strip transition: alpha fade (effects spec) for the
    // colour disappearing, and a spatial-spring shrink that compresses the strip
    // toward its vertical centre. Together they read as "the read marker just
    // collapsed and vanished" instead of a passive fade-out — informative
    // feedback for the dwell-ack the user might otherwise miss.
    val unreadAlpha by animateFloatAsState(
        targetValue = if (isUnread) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "post-unread-strip-alpha",
    )
    val unreadShrink by animateFloatAsState(
        targetValue = if (isUnread) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "post-unread-strip-shrink",
    )
    val unreadStripColor = MaterialTheme.colorScheme.primary
    // No dwell-read afterglow: an N6 "marked read" glow on the left edge was tried
    // here and removed — the "next unread" button marks posts read in quick
    // succession, and on device the per-card glow read as the feed flickering at
    // its left edge, not as feedback. The strip's own collapse (alpha + shrink
    // springs above) is the read-ack signal; don't add transient flashes to it.
    // One unread signal only: the 3 dp edge strip. A full-card primary wash was tried
    // as a peripheral companion cue and removed in the visual-polish pass — with the
    // strip, the "Нові пости" divider, the arrivals pill and the unread FAB badge all
    // live on the same screen, a fifth concurrent signal tipped the feed from
    // "informative" into "noisy", and the tonal wash was the main reason interactive
    // fills (chips, pills) stopped reading as interactive. Don't reintroduce without
    // retiring another unread surface first.
    // The unread strip is a colour-only visual cue — invisible to TalkBack. Mirror
    // it into the accessibility tree as a state description so screen-reader users
    // get the same "this post is new" signal sighted users read from the strip.
    val unreadStateDescription = stringResource(R.string.post_unread_state)

    // C3 — card press scale. Riding ONE shared interactionSource: the inner Row's
    // combinedClickable feeds it, [collectIsPressedAsState] reads it, and the Column
    // animates 1.0 → 0.985 on press via a spatial spring. The graphicsLayer is gated
    // behind `pressScale != 1f` with the same `.then(if …)` idiom the deleted-dim uses
    // — a resting card installs NO compositing layer, so the common feed case stays
    // layer-free and skippable. The source is `remember`-stable so it captures no new
    // unstable scope.
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isPressed by cardInteractionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && (clickable || actionsEnabled)) 0.985f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "post-press-scale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (isUnread) stateDescription = unreadStateDescription }
            // Only deleted posts dim. `.then(...)` instead of `.alpha(if … 1f)` so a live
            // post never installs an offscreen compositing layer it doesn't need — the
            // common case stays layer-free.
            .then(if (post.isDeleted) Modifier.alpha(0.55f) else Modifier)
            // Press-scale layer is installed ONLY while pressed (pressScale < 1f); a
            // resting card stays compositing-layer-free.
            .then(
                if (pressScale != 1f) {
                    Modifier.graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                } else Modifier,
            )
            .background(highlightColor)
            .drawBehind {
                if (unreadAlpha <= 0f) return@drawBehind
                val stripWidth = 3.dp.toPx()
                val verticalInset = 14.dp.toPx()
                val cornerRadius = 2.dp.toPx()
                val fullHeight = (size.height - verticalInset * 2f).coerceAtLeast(0f)
                if (fullHeight <= 0f) return@drawBehind
                val visibleHeight = fullHeight * unreadShrink
                val verticalOffset = verticalInset + (fullHeight - visibleHeight) / 2f
                drawRoundRect(
                    color = unreadStripColor,
                    topLeft = Offset(0f, verticalOffset),
                    size = Size(stripWidth, visibleHeight),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    alpha = unreadAlpha,
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = cardInteractionSource,
                    indication = LocalIndication.current,
                    enabled = clickable || actionsEnabled,
                    onClick = { if (clickable) interactions.onPostClick(post) },
                    onLongClick = if (actionsEnabled) ({ sheetOpen = true }) else null,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Header tap routing — three branches, in priority order:
            //   1. [senderUserId] (personal-author mode, admin posting under their own
            //      identity, OR every discussion-thread comment) → user-profile sheet.
            //      The host channel is already surfaced as the "у Channel" subtitle,
            //      so making the header redundantly link there would waste the affordance.
            //   2. [senderChatId] (admin posting "as one of my other channels" —
            //      TDLib's foreign MessageSenderChat case) → drill into THAT chat
            //      through [onAuthorChatClick]. Without this branch the tap would
            //      land on the host channel, which the user can already reach via
            //      the subtitle chip — and the foreign chat would have no in-app
            //      entry surface at all.
            //   3. Channel-as-sender post → open the host channel filter.
            val userOpener = dev.lyo.hortay.ui.users.LocalUserProfileOpener.current
            val onSenderClick: () -> Unit = when {
                post.senderUserId != null -> {
                    val uid = post.senderUserId
                    ({ userOpener.open(uid) })
                }
                post.senderChatId != null -> {
                    val cid = post.senderChatId
                    ({ interactions.onAuthorChatClick(cid) })
                }
                else -> ({ interactions.onChannelClick(post) })
            }
            Avatar(
                name = post.senderName,
                thumb = post.avatarThumb,
                fileId = post.avatarFileId,
                avatarUrl = post.avatarUrl,
                onClick = onSenderClick,
            )
            Spacer(Modifier.width(12.dp))
            // Scope inline `#tag` taps to the post's channel when this is a channel
            // post with a known handle (matches Telegram-Android: tap `#foo` inside
            // channel X → "search #foo in X"). The `#tag@channel` text-entity form
            // always wins because the entity is self-describing — the wrapper only
            // injects scope when the tap text has no `@suffix` already.
            //
            // Skipped for comments (`parentId != null`) and posts whose senderHandle
            // is null — both fall through to the scaffold default, where
            // `parseHashtagWithScope` still splits explicit `#tag@channel` entities,
            // just without a captured default scope to substitute for unsuffixed
            // bare-`#tag` taps.
            val scopeHandle = if (post.parentId == null) post.senderHandle else null
            HashtagScope(scopeHandle) {
                Column(modifier = Modifier.weight(1f)) {
                    HeaderRow(
                    senderName = post.senderName,
                    authorSignature = post.authorSignature,
                    editDate = post.editDate,
                    date = post.date,
                    pinned = post.isPinned,
                    verification = post.verification,
                    // Visual cue that this tap drills into another channel — only the
                    // foreign-chat case (admin posting "as one of my other channels")
                    // navigates to a separate destination. User-profile sheet and
                    // host-channel filter stay on the same screen, so they get no
                    // chevron — keeps the affordance honest.
                    showDrillChevron = post.senderChatId != null,
                    onChannelClick = onSenderClick,
                    isDeleted = post.isDeleted,
                    revisionCount = post.revisionCount,
                    onTapRevisions = { onTapRevisions(post) },
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

                post.forwardOrigin?.let { origin ->
                    Spacer(Modifier.height(6.dp))
                    ForwardChip(
                        origin = origin,
                        onClick = when (origin) {
                            // Forwarded from a user we can resolve → user-profile sheet.
                            // Legacy forwards without a userId stay inert (no target to
                            // open) — preserves the current behaviour for those.
                            is ForwardOrigin.User -> origin.userId?.let { uid -> { userOpener.open(uid) } }
                            is ForwardOrigin.Channel,
                            is ForwardOrigin.Chat -> ({ interactions.onForwardSourceClick(post) })
                            is ForwardOrigin.HiddenUser -> null
                        },
                    )
                }

                post.reply?.let {
                    Spacer(Modifier.height(8.dp))
                    ReplyBlock(it, onClick = { interactions.onQuotedSourceClick(post) })
                }

                Spacer(Modifier.height(8.dp))
                val translation = interactions.translationFor(post)
                if (translation != null) {
                    TranslationChip(onDismiss = { interactions.onClearTranslationClick(post) })
                    Spacer(Modifier.height(8.dp))
                }
                // Capture (post → onPollVote(post, …)) once so PollBlock keeps its
                // [PollVoting] handler stable across recompositions (Immutable holder, no
                // skippability hit). Null in surfaces that don't wire voting — see
                // [PostInteractions.pollVotingEnabled].
                val pollVoting = remember(post.id, post.chatId, interactions) {
                    if (interactions.pollVotingEnabled) {
                        PollVoting { indices -> interactions.onPollVote(post, indices) }
                    } else null
                }
                // A deleted post keeps its original content (with valid TDLib fileIds) so
                // the card can still show what was there — but the message is gone
                // server-side and its media can't be re-fetched. [LocalMediaPassive] flips
                // every media renderer under this body to observe-only: render the cached
                // file / inline minithumb, but issue no downloads, autoplay or retry. This
                // is the fix for the "scroll hangs near deleted posts" report — see
                // [dev.lyo.hortay.ui.media.LocalMediaPassive]. Scoped to the body only; the
                // channel avatar above stays on the normal (shared, cached) download path.
                CompositionLocalProvider(LocalMediaPassive provides post.isDeleted) {
                    PostBody(
                        content = post.content,
                        onMediaClick = { _, idx -> interactions.onMediaClick(post, idx) },
                        expanded = expanded,
                        translation = translation,
                        onOpenInSource = { interactions.onOpenClick(post) },
                        pollVoting = pollVoting,
                    )
                }

                if (!post.isDeleted && (post.views > 0 || post.forwardCount > 0 || (post.commentCount ?: 0) > 0 || post.reactions.items.isNotEmpty())) {
                    Spacer(Modifier.height(10.dp))
                    ActionRow(
                        views = post.views,
                        forwardCount = post.forwardCount,
                        commentCount = post.commentCount,
                        reactions = post.reactions,
                        onCommentsClick = { interactions.onPostClick(post) },
                        onReactionTap = { item -> interactions.onReactionToggle(post, item) },
                        // Paid (⭐) reactions can't be cast from Hortay — the snackbar action
                        // route opens THIS SPECIFIC post in Telegram (not the client root),
                        // reusing [interactions.onOpenClick] which already minted the
                        // canonical share URL via PostActions.openInTelegram. Going through
                        // the snackbar (vs auto-opening on tap) gives the user a beat to
                        // bail in case the tap was a misclick.
                        onPaidReactionOpenPost = { interactions.onOpenClick(post) },
                    )
                }
                }
            }
        }

        // Inset divider — starts at the text column (16 dp card padding + 40 dp avatar
        // + 12 dp gap), the idiom Telegram / iOS lists use to tie a row to its avatar
        // while keeping the separation whisper-quiet. Full-bleed hairlines under every
        // card read as a table grid; the inset reads as typography.
        HorizontalDivider(
            modifier = Modifier.padding(start = 68.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        )
    }

    if (sheetOpen) {
        PostActionSheet(
            post = post,
            interactions = interactions,
            onSelectText = { selectTextOpen = true },
            onDismiss = { sheetOpen = false },
        )
    }
    if (selectTextOpen) {
        SelectTextSheet(post = post, onDismiss = { selectTextOpen = false })
    }
}

/**
 * Override [LocalHashtagTap] for the duration of [content] so unsuffixed `#tag`
 * taps inside the post body carry a default channel [scopeHandle]. Pass-through
 * (no override) when [scopeHandle] is null — e.g. comments, posts without a
 * channel handle. The wrapper composes the scope on top of the scaffold-level
 * default: `#tag@channel` entities still win (the suffix carried by the entity
 * is treated as the user's explicit choice; the captured scope only fills in
 * when the entity has no suffix). Idempotent across [content] recompositions
 * because the lambda is `remember`-keyed on `(default, scopeHandle)`.
 */
@Composable
private fun HashtagScope(scopeHandle: String?, content: @Composable () -> Unit) {
    val default = LocalHashtagTap.current
    if (scopeHandle.isNullOrBlank()) {
        content()
        return
    }
    val scoped = remember(default, scopeHandle) {
        { raw: String ->
            val (tag, suffix) = parseHashtagWithScope(raw)
            val effective = if (suffix != null) raw else "$tag@${scopeHandle.removePrefix("@")}"
            default(effective)
        }
    }
    CompositionLocalProvider(LocalHashtagTap provides scoped) {
        content()
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
private fun avatarBg(name: String): Color {
    // Palette identity is stable across recompositions for a given theme —
    // remember on the three colour values so we don't allocate a fresh
    // 3-element list on every avatar render. The theme reads happen in
    // @Composable scope before the remember block (which is a regular
    // lambda where @Composable reads are not allowed).
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    val palette = remember(primary, secondary, tertiary) {
        listOf(primary, secondary, tertiary)
    }
    return palette[(name.hashCode().rem(palette.size) + palette.size) % palette.size]
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
    showDrillChevron: Boolean,
    onChannelClick: () -> Unit,
    isDeleted: Boolean = false,
    revisionCount: Int = 0,
    onTapRevisions: () -> Unit = {},
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
        modifier = Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sender-name region — name+badge+chevron is the ONLY clickable surface that
        // drills into the channel; the trailing region (pencil / chip / date) keeps its
        // own click semantics. Two-layer layout is load-bearing: the outer `Box(weight(1f))`
        // owns the full horizontal slot so the trailing date stays pinned to the right
        // edge, while the inner `Row` sizes to its content and carries the clickable
        // surface — taps on the empty gap between name end and trailing block fall
        // through (they're inside the Box, outside the Row).
        // History: a previous iteration put `weight(1f, fill = true)` directly on the
        // clickable Row. That kept the date pinned right but the hit-target swallowed
        // the empty gap, so users complained that "the whole header taps". Then
        // `fill = false` shrank the hit-target but also collapsed the slot so the date
        // un-pinned from the right edge. The Box wrapper resolves both.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .clip(InlineChipCorner)
                    .clickable(role = Role.Button, onClick = onChannelClick),
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
                if (showDrillChevron) {
                    Spacer(Modifier.width(2.dp))
                    Symbol(
                        name = "chevron_right",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 14.dp,
                    )
                }
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
        // Pencil-icon-vs-chip resolution: when archive has captured revisions, the
        // chip subsumes the pencil — tapping opens the revision sheet AND
        // communicates "edited". When archive is off or this post was edited before
        // the feature was enabled (editDate > 0 but no captured versions), we keep
        // the original non-interactive pencil so the indicator never disappears for
        // existing users.
        if (isDeleted) {
            TimestampText(date)
            Spacer(Modifier.width(4.dp))
            DeletedBadge()
        } else if (revisionCount > 0) {
            EditedChip(count = revisionCount, onClick = onTapRevisions)
            Spacer(Modifier.width(4.dp))
            TimestampText(date)
        } else {
            if (editDate > 0L) {
                Symbol(
                    name = "edit",
                    contentDescription = stringResource(R.string.post_badge_edited),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 14.dp,
                )
                Spacer(Modifier.width(4.dp))
            }
            TimestampText(date)
        }
    }
}

/**
 * C4 — post-header timestamp. `labelSmall` in `onSurfaceVariant @ 0.8` so the relative
 * time recedes below the author name and edit/pin marks, and tabular figures (C2) so a
 * "5 хв" → "12 хв" tick never reflows the right-edge block. One composable shared by the
 * deleted / edited / plain header branches keeps the three timestamps in lockstep.
 */
@Composable
private fun TimestampText(date: Long) {
    Text(
        text = formatRelative(date),
        style = MaterialTheme.typography.labelSmall.tabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
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
            .clip(InlineChipCorner)
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
            contentDescription = stringResource(R.string.cd_verified_badge),
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
private fun WarningPill(label: String, color: Color) {
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
            .clip(InlineChipCorner)
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
            Modifier.clip(InlineChipCorner).clickable(role = Role.Button, onClick = onClick)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = "forward",
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
/**
 * Telegram-style reply preview, adapted to M3 Expressive.
 *
 *   • Background is `primary @ 10% alpha` over the host card's `surfaceContainer` —
 *     same idiom as the inline block-quote box (see [dev.lyo.hortay.ui.text.LinkAwareText])
 *     so quote-in-text and reply-to-post read as one design family. The previous
 *     opaque `surfaceContainer` fill was
 *     legible but visually inert; the tint pulls the chip into the primary-colour
 *     family that already owns the accent bar and author name.
 *   • Outer container is a [Box] so the trailing-corner reply glyph
 *     ("↰" — `sym_reply`) can hover over the tinted bg at the top-right edge.
 *     The glyph is suppressed when a thumbnail is present: the 44 dp media tile
 *     already signals "this is a quoted media post", and an icon over the tile's
 *     leading corner would crash visually.
 *   • Shape is [MaterialTheme.shapes.extraSmall] (8 dp) — small enough that the
 *     3 dp accent bar's corners read as designed-in softening rather than a
 *     clipping artefact, and one tier tighter than the host PostCard's `medium`
 *     so the chip nests cleanly inside it (M3E nested-radius rule).
 */
@Composable
private fun ReplyBlock(reply: ReplyPreview, onClick: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    .padding(
                        start = 10.dp,
                        // Reserve trailing room only when the reply glyph will sit there
                        // (no thumbnail path). With a thumbnail the icon is suppressed,
                        // so 8 dp is enough breathing room before the media tile.
                        end = if (reply.mediaThumb == null) 28.dp else 8.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
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
                        // D1 — nested reply thumbnail is rectangular photographic content,
                        // so it carries the hairline frame on the SAME shape it clips to;
                        // shapes.small (12 dp) is the M3E nested-tile radius (D2).
                        .mediaFrame(MaterialTheme.shapes.small)
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
        // Telegram-style reply glyph in the trailing corner. Auto-mirrored drawable
        // flips the arrow direction in RTL automatically (declared in sym_reply.xml).
        if (reply.mediaThumb == null) {
            Symbol(
                name = "reply",
                tint = accent.copy(alpha = 0.55f),
                size = 14.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp),
            )
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
    forwardCount: Int,
    commentCount: Int?,
    reactions: dev.lyo.hortay.data.Reactions,
    onCommentsClick: () -> Unit,
    onReactionTap: (ReactionItem) -> Unit = {},
    /**
     * Invoked when the user taps the snackbar action button for a paid (⭐)
     * reaction. The callsite already knows how to open *this specific* post in
     * Telegram (via [PostInteractions.onOpenClick] → [PostActions.openInTelegram]),
     * so ActionRow stays ignorant of post identity.
     */
    onPaidReactionOpenPost: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (views > 0) {
            StatPill("visibility", formatViews(views))
        }
        // `commentCount == null` = channel has no linked discussion group (comments
        // fundamentally disabled). `commentCount == 0` = group exists but no replies
        // yet. Both read as "nothing to see" to the user, so we collapse them into
        // one branch and only surface the pill when the count is meaningfully > 0 —
        // mirrors the empty-state contract on CommentsScreen (no "0 replies" subtitle).
        val safeCommentCount = commentCount ?: 0
        val hasComments = safeCommentCount > 0
        if (hasComments) {
            if (views > 0) Spacer(Modifier.width(14.dp))
            StatPill(
                symbol = "chat_bubble",
                text = formatViews(safeCommentCount),
                onClick = onCommentsClick,
            )
        }
        // Forward count — read-only, mirrors the stat Telegram shows on a channel
        // post. Uses the `forward` arrow glyph, matching the "forwarded from" badge.
        val hasForwards = forwardCount > 0
        if (hasForwards) {
            if (views > 0 || hasComments) Spacer(Modifier.width(14.dp))
            StatPill(symbol = "forward", text = formatViews(forwardCount))
        }
        if (reactions.items.isNotEmpty()) {
            // C1 — no VerticalSeparator before reactions. The hairline-outline reaction
            // ghosts (Phase-1) read as their own cluster, so the divider was redundant
            // chrome; a plain 14 dp gap separates the stats from the chips.
            if (views > 0 || hasComments || hasForwards) {
                Spacer(Modifier.width(14.dp))
            }
            // Paid (⭐) reactions can't be cast from Hortay — sending one needs the
            // star-amount confirmation flow we don't host. Tap surfaces a snackbar
            // explaining the situation, with an action button that opens *this
            // specific* post in Telegram (via the per-post callback, routed
            // through [PostActions.openInTelegram]). The chip is rendered at 0.6
            // alpha to read as informational rather than interactive (see
            // [ReactionChip.disabled]); the snackbar gate keeps a misclick from
            // bouncing the user out of Hortay before they can change their mind.
            val res = LocalContext.current.resources
            val bus = dev.lyo.hortay.ui.main.LocalUserMessageBus.current
            val openTelegramLabel = res.getString(R.string.action_open_telegram)
            val paidExplainer = res.getString(R.string.reactions_paid_explainer)
            // Key each chip by its reaction-bucket identity, NOT by position. TDLib ranks
            // reaction buckets by frequency and re-orders them on every
            // UpdateMessageInteractionInfo; a positional (unkeyed) loop would then reuse a
            // chip slot that was rendering custom emoji A to render custom emoji B, and the
            // underlying Coil AsyncImage keeps painting A's already-decoded bitmap until B
            // decodes — so two different reactions momentarily render the SAME custom emoji.
            // A stable key moves composition state with the bucket instead of reusing by slot.
            reactions.items.forEachIndexed { idx, item ->
                key(item.kind.stableKey) {
                    if (idx > 0) Spacer(Modifier.width(6.dp))
                    val isPaid = item.kind is ReactionKind.Paid
                    val tapHandler: () -> Unit = if (isPaid) {
                        {
                            bus?.post(
                                text = paidExplainer,
                                severity = dev.lyo.hortay.data.UserMessageBus.Severity.Info,
                                action = dev.lyo.hortay.data.UserMessageBus.Action.Run(
                                    label = openTelegramLabel,
                                    onClick = onPaidReactionOpenPost,
                                ),
                            )
                        }
                    } else {
                        {
                            // Toggle haptic on a real reaction add/remove — ToggleOff when
                            // un-reacting (the chip was already the user's choice), ToggleOn
                            // otherwise. Paid reactions skip this: their tap raises an
                            // explainer snackbar, not a state change.
                            haptics.performHapticFeedback(
                                if (item.isChosen) HapticFeedbackType.ToggleOff
                                else HapticFeedbackType.ToggleOn,
                            )
                            onReactionTap(item)
                        }
                    }
                    ReactionChip(item, onClick = tapHandler, disabled = isPaid)
                }
            }
        }
    }
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
 *     `primaryContainer`.
 *
 * Fill discipline (visual-polish pass): the resting chip is a hairline-outlined
 * ghost, NOT a `surfaceContainer` fill. A grey pill under every reaction of every
 * post was the single largest contributor to the feed's "tonal soup" — with ~3
 * chips per card nothing else could read as interactive. Outline at rest /
 * tonal fill when chosen restores the figure-ground split: lavender fill now
 * means exactly one thing in the feed — "yours".
 *
 * Cookie/Burst/Heart polygons were considered but Google's Expressive guidance
 * reserves them for 1:1 elements (FAB, IconButton, avatar, hero badge).
 * Non-uniformly scaling them to a wide chip deforms the character ridges into
 * elongated ovals — corner-radius morph on a stadium stays canonical at any
 * aspect, including 3-digit counts.
 */
@Composable
internal fun ReactionChip(
    item: ReactionItem,
    onClick: (() -> Unit)? = null,
    /**
     * Render the chip as informational rather than active. Currently used for
     * paid (⭐) reactions, which Hortay can't dispatch — the chip remains tappable
     * (the tap raises an explainer snackbar with "Open Telegram") but the alpha
     * + flat fill mark it as "secondary affordance" so users don't try to vote.
     */
    disabled: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val cornerRadius by rememberPressedSelectedCornerRadius(
        interactionSource = interactionSource,
        selected = item.isChosen,
        rest = 14.dp,
        pressed = 6.dp,
        selectedRadius = 24.dp,
        label = "reaction-corner",
    )
    val container by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (item.isChosen) Color.Transparent
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-border",
    )
    val countColor by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-fg",
    )
    val tintForCustom by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-tint",
    )
    val shape = RoundedCornerShape(cornerRadius)
    // The N5 micro-burst (particle spray on choose) was tried here and removed: the
    // particles flew outside the chip bounds by design and read as a rendering glitch
    // ("a stray piece of the digit above the button"), not celebration. Choose
    // feedback is the ghost→fill morph + haptic; don't reintroduce unclipped overlays.
    Row(
        modifier = Modifier
            .clip(shape)
            .background(container, shape)
            .border(1.dp, borderColor, shape)
            .let {
                if (onClick != null) it.clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ) else it
            }
            .then(
                if (disabled) Modifier.alpha(0.6f) else Modifier,
            )
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
            // Paid star reaction — Telegram renders a small ⭐ pill, no count
            // separator needed because the count is already the star total.
            is ReactionKind.Paid -> Text(
                text = "⭐",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(6.dp))
        // M3 — mini digit-roll on count changes: the new value slides in vertically
        // (up when the count grows, down when it shrinks) while the old slides out and
        // fades, Telegram's counter idiom. C2 tabular figures keep the width fixed so
        // the roll never reflows neighbours. Under reduced motion the roll collapses
        // to a plain crossfade. `clipToBounds` confines the sliding digits to the
        // counter's own box — the spatial spring overshoots by design, and without
        // the clip the outgoing digit peeked past the chip edge for a frame.
        // Specs are read in @Composable scope and captured by the transitionSpec
        // lambda (which is NOT composable, so it can't read MaterialTheme itself).
        val reducedMotion = rememberReducedMotion()
        val countStyle = MaterialTheme.typography.labelMedium.tabularFigures()
        val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
        AnimatedContent(
            targetState = item.count,
            modifier = Modifier.clipToBounds(),
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
                } else {
                    val up = targetState > initialState
                    (slideInVertically(spatialSpec) { h -> if (up) h else -h } + fadeIn(fadeSpec))
                        .togetherWith(
                            slideOutVertically(spatialSpec) { h -> if (up) -h else h } + fadeOut(fadeSpec),
                        )
                }
            },
            label = "reaction-count-roll",
        ) { count ->
            Text(
                text = formatViews(count),
                style = countStyle,
                color = countColor,
            )
        }
    }
}

@Composable
private fun StatPill(
    symbol: String,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // C1 — 14 dp stat glyphs. The earlier 18/22 dp let views / comments /
            // forward icons visually outweigh their labelMedium counts and made the
            // action row read at the same level as the post body above it. Dropping
            // to 14 dp + labelMedium counts seats the whole row a full visual level
            // below the body — Threads / Telegram keep stat glyphs in this band.
            size = 14.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            // C2 — tabular figures so a view-count tick (views ↑) never reflows the
            // pill width and shifts its neighbours.
            style = MaterialTheme.typography.labelMedium.tabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostActionSheet(
    post: TimelinePost,
    interactions: PostInteractions,
    onSelectText: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
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
            // Reaction picker strip — cast a reaction the post doesn't already carry,
            // Telegram-style. Available reactions are fetched once when the sheet opens
            // (a single on-demand RPC; empty in guest mode → strip is hidden).
            var available by remember(post.id) { mutableStateOf<List<ReactionKind>>(emptyList()) }
            LaunchedEffect(post.id) { available = interactions.availableReactions(post) }
            if (available.isNotEmpty()) {
                val chosenKeys = remember(post.reactions) {
                    post.reactions.items.filter { it.isChosen }.map { it.kind.stableKey }.toSet()
                }
                ReactionPickerStrip(
                    reactions = available,
                    chosenKeys = chosenKeys,
                    onPick = { kind ->
                        val already = kind.stableKey in chosenKeys
                        haptics.performHapticFeedback(
                            if (already) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        runAndDismiss {
                            interactions.onReactionToggle(post, ReactionItem(kind, count = 0, isChosen = already))
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            // Primary actions live in an Expressive ButtonGroup; Comments only when the
            // channel has a linked discussion group (commentCount != null).
            PostQuickActions(
                showComments = post.commentCount != null,
                onComments = { runAndDismiss { interactions.onPostClick(post) } },
                onShare = { runAndDismiss { interactions.onShareClick(post) } },
                onOpen = { runAndDismiss { interactions.onOpenClick(post) } },
            )
            // Secondary actions stay a vertical list.
            SheetItem(
                symbol = "bookmark",
                label = stringResource(if (isBookmarked) R.string.post_unsave else R.string.post_save),
                onClick = {
                    // Toggle haptic on the save/unsave state flip, before the action runs.
                    haptics.performHapticFeedback(
                        if (isBookmarked) HapticFeedbackType.ToggleOff
                        else HapticFeedbackType.ToggleOn,
                    )
                    runAndDismiss { interactions.onBookmarkClick(post) }
                },
            )
            if (post.content.captionPlain.isNotBlank()) {
                SheetItem(
                    symbol = "content_copy",
                    label = stringResource(R.string.post_copy_text),
                    onClick = { runAndDismiss { interactions.onCopyClick(post) } },
                )
                SheetItem(
                    symbol = "description",
                    label = stringResource(R.string.post_select_text),
                    onClick = { runAndDismiss { onSelectText() } },
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
            if (interactions.canReport(post)) {
                SheetItem(
                    symbol = "flag",
                    label = stringResource(R.string.report_action),
                    onClick = { runAndDismiss { interactions.onReportClick(post) } },
                )
            }
        }
    }
}

/**
 * "Select text" sheet — renders the post's caption inside a [SelectionContainer] with no
 * surrounding long-press gesture, so the system text toolbar (Copy / Select all / Share)
 * works uncontested. Opened from the action sheet's "Select text" item; this is how the
 * feed reconciles "long-press = action menu" with "let me select part of the text".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTextSheet(post: TimelinePost, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(
                    text = post.content.captionPlain,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 40.dp),
                )
            }
        }
    }
}

/**
 * Telegram-style reaction picker: a horizontally-scrollable row of circular reaction
 * targets. Reactions the user already cast read as "selected" (tertiaryContainer); the
 * rest sit on surfaceContainerHigh. Tapping casts (or retracts) via the same optimistic
 * [PostInteractions.onReactionToggle] path the inline chips use. The emoji glyph itself
 * is the screen-reader label for unicode reactions; custom-emoji targets stay unlabeled,
 * matching the existing [ReactionChip] a11y contract.
 */
@Composable
internal fun ReactionPickerStrip(
    reactions: List<ReactionKind>,
    chosenKeys: Set<String>,
    onPick: (ReactionKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reactions.forEach { kind ->
            val chosen = kind.stableKey in chosenKeys
            val background =
                if (chosen) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable(role = Role.Button) { onPick(kind) },
                contentAlignment = Alignment.Center,
            ) {
                when (kind) {
                    is ReactionKind.Emoji -> Text(
                        text = kind.text,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    is ReactionKind.CustomEmoji -> CustomEmojiInlineView(
                        customEmojiId = kind.customEmojiId,
                        modifier = Modifier.size(24.dp),
                        contentDescription = null,
                    )
                    // Paid reactions are filtered out upstream (can't be cast from Hortay).
                    is ReactionKind.Paid -> Unit
                }
            }
        }
    }
}

/**
 * Primary post actions as a Material 3 Expressive [ButtonGroup] — connected buttons with
 * the press-squeeze morph. Comments is conditional on a linked discussion group existing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PostQuickActions(
    showComments: Boolean,
    onComments: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
) {
    val commentsLabel = stringResource(R.string.archive_scope_comments)
    val shareLabel = stringResource(R.string.post_share)
    val openLabel = stringResource(R.string.post_open_telegram)
    ButtonGroup(
        // Mandatory since material3 1.5.0-alpha23. With two-three fixed items the
        // group never overflows on a phone-width card, but the API needs a menu
        // affordance for the degenerate case (split-screen + max font scale).
        overflowIndicator = { menuState ->
            IconButton(
                onClick = { if (menuState.isShowing) menuState.dismiss() else menuState.show() },
            ) {
                Symbol(name = "more_horiz", size = 18.dp)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // material3 1.5.0-alpha23 ButtonGroupMeasurePolicy shrinks maxWidth for the
            // overflow indicator without clamping it against minWidth, so under the tight
            // constraints fillMaxWidth produces (min == max) any label overflow — long
            // uk/ru labels on a three-button row — crashes measure with "maxWidth must
            // be >= than minWidth". Loosening minWidth lets the group take its designed
            // overflow path (trailing "…" menu) instead.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            },
    ) {
        // clickableItem renders [label] as the button text; [icon] is the leading glyph.
        if (showComments) {
            clickableItem(
                onClick = onComments,
                label = commentsLabel,
                icon = { Symbol(name = "chat_bubble", size = 18.dp) },
            )
        }
        clickableItem(
            onClick = onShare,
            label = shareLabel,
            icon = { Symbol(name = "share", size = 18.dp) },
        )
        clickableItem(
            onClick = onOpen,
            label = openLabel,
            icon = { Symbol(name = "open_in_new", size = 18.dp) },
        )
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

private fun formatViews(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "%.1fK".format(count / 1_000.0).trimEnd('0').trimEnd('.')
    else -> "%.1fM".format(count / 1_000_000.0).trimEnd('0').trimEnd('.')
}

