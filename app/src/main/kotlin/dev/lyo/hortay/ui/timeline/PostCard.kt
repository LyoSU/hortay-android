package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import dev.lyo.hortay.ui.components.AnchoredContextMenu
import dev.lyo.hortay.ui.components.ContextMenuAnchor
import dev.lyo.hortay.ui.components.ContextMenuCapsule
import dev.lyo.hortay.ui.components.ContextMenuCard
import dev.lyo.hortay.ui.components.ContextMenuDivider
import dev.lyo.hortay.ui.components.ContextMenuItem
import dev.lyo.hortay.ui.components.contextMenuAnchor
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.text.LocalQuoteHighlight
import dev.lyo.hortay.ui.text.QuoteHighlights
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
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelAvatarRef
import dev.lyo.hortay.data.ChannelContext
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.SenderVerification
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.toImmutableList
import dev.lyo.hortay.data.isUnreadAt
import dev.lyo.hortay.data.stableKey
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.rich.LocalRichBleedInset
import dev.lyo.hortay.ui.rich.RichBleedInset
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.parseHashtagWithScope
import androidx.compose.runtime.CompositionLocalProvider
import dev.lyo.hortay.ui.archive.components.DeletedBadge
import dev.lyo.hortay.ui.archive.components.EditedChip
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.theme.InlineChipCorner
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.theme.asComposeShape
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.theme.rememberPressedSelectedCornerRadius
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

// Card body chrome — the header Row's horizontal padding, the avatar diameter, and the gap between
// avatar and the body column. Named so the reading-mode bleed inset (declared at the PostBody call)
// derives from the SAME values the header lays out with, instead of a copy that silently drifts.
private val CARD_H_PADDING = 16.dp
// 36 dp, not 40: with name and time on one line the header is a single text line tall, and a
// 40 dp avatar towered over it. 36 dp still carries a recognisable channel photo.
private val AVATAR_SIZE = 36.dp
private val AVATAR_GAP = HortaySpacing.md

// Vertical padding of the card body. 16 dp (was 14): with the hairline now spanning the full
// width, a little more air above and below each post keeps the stack from reading as a table.
private val CARD_V_PADDING = HortaySpacing.lg

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
    /** What this merged-feed row stands for (forwards / a folded series); see [clusterFeed]. */
    annotation: FeedAnnotation? = null,
    onExpandSeries: () -> Unit = {},
    /**
     * Whether a personal-author post names its host channel ("in <Channel>" + avatar badge).
     * The channel screen passes `false`: every post there has the same host, already named
     * in the screen's own header.
     */
    showHostChannel: Boolean = true,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val hostChannel = post.channelContext.takeIf { showHostChannel }
    val menuAnchor = remember { ContextMenuAnchor() }
    // Picker → chip hand-off for the choose animation: the sheet dismisses as it toggles,
    // so the chip in this card's action row plays it (see [ReactionEffectRequest]).
    val reactionEffect = rememberReactionEffectRequest()
    // Hosted at the card level (not inside the action sheet) so it survives the
    // action sheet dismissing when the user taps "Select text".
    var selectTextOpen by remember { mutableStateOf(false) }
    var summaryOpen by remember { mutableStateOf(false) }
    val summarizer = LocalPostSummarizer.current

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
    val cursorHolder = LocalReadCursors.current
    // A tombstone is terminal, not "yet to be read" — never paint the unread strip on it.
    // A merged-feed row that folds other posts ([annotation], see [clusterFeed]) stays
    // unread while any of them is: a hidden forward copy, or a folded series member the
    // reader has not opened yet. Same per-key reads, one per chat the row stands for.
    val isUnread = !expanded && !post.isDeleted && post.parentId == null && (
        if (annotation == null) post.isUnreadAt(cursorHolder[post.chatId])
        else rowHasUnread(post, annotation) { cursorHolder[it] }
        )
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
            .contextMenuAnchor(menuAnchor)
            .semantics { if (isUnread) stateDescription = unreadStateDescription }
            // Only deleted posts dim. `.then(...)` instead of `.alpha(if … 1f)` so a live
            // post never installs an offscreen compositing layer it doesn't need — the
            // common case stays layer-free.
            .then(if (post.isDeleted) Modifier.alpha(HortayAlpha.FAINT) else Modifier)
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
                    topLeft = Offset(
                        if (layoutDirection == LayoutDirection.Rtl) size.width - stripWidth else 0f,
                        verticalOffset,
                    ),
                    size = Size(stripWidth, visibleHeight),
                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                    alpha = unreadAlpha,
                )
            },
    ) {
        // Full-width layout: avatar + name + time share one header row and the body
        // spans the whole card width below it, so photos, albums and text get the ~18%
        // of the width a left avatar column used to eat. Telegram's own channel layout.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = cardInteractionSource,
                    indication = LocalIndication.current,
                    enabled = clickable || actionsEnabled,
                    onClick = { if (clickable) interactions.onPostClick(post) },
                    onLongClickLabel = if (actionsEnabled) stringResource(R.string.post_more_actions) else null,
                    onLongClick = if (actionsEnabled) ({ sheetOpen = true }) else null,
                )
                .padding(horizontal = CARD_H_PADDING, vertical = CARD_V_PADDING),
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
                Column(modifier = Modifier.fillMaxWidth()) {
                // A forward whose source can be drawn is headed by that SOURCE, the way a
                // repost reads everywhere else: the content is the original author's, so
                // their face and name sit next to it, and the channel that forwarded it
                // is a quiet line above. Before, the forwarding channel headed a post it
                // did not write and the real author was a grey line that was easy to miss.
                val origin = post.forwardOrigin
                val source = remember(origin) { origin?.headerSource() }
                // Forwarded from a user we can resolve → user-profile sheet. Legacy
                // forwards without a userId stay inert (no target to open).
                val onSourceClick: (() -> Unit)? = when (origin) {
                    is ForwardOrigin.User -> origin.userId?.let { uid -> { userOpener.open(uid) } }
                    is ForwardOrigin.Channel,
                    is ForwardOrigin.Chat -> ({ interactions.onForwardSourceClick(post) })
                    is ForwardOrigin.HiddenUser, null -> null
                }
                val alsoForwardedBy = annotation?.forwardedBy ?: kotlinx.collections.immutable.persistentListOf()
                if (source != null) {
                    // Inside the forwarding channel's own screen "forwarded by <this
                    // channel>" repeats the screen title on every forward — unless a
                    // named author did the forwarding, or other channels did too.
                    val showForwarder = showHostChannel || post.channelContext != null
                    if (showForwarder || alsoForwardedBy.isNotEmpty()) {
                        val forwarders = remember(post, alsoForwardedBy, showForwarder) {
                            buildList {
                                if (showForwarder) add(post.asForwarderBadge())
                                addAll(alsoForwardedBy)
                            }.toImmutableList()
                        }
                        ForwardedByRow(
                            forwarders = forwarders,
                            label = if (forwarders.size == 1) R.string.post_forwarded_by else R.string.feed_forwarded_by_many,
                            onClick = onSenderClick.takeIf { showForwarder },
                        )
                        Spacer(Modifier.height(HortaySpacing.sm))
                    }
                } else if (alsoForwardedBy.isNotEmpty()) {
                    ForwardedByRow(alsoForwardedBy, label = R.string.feed_also_forwarded_by)
                    Spacer(Modifier.height(HortaySpacing.sm))
                }
                val onHeaderClick = if (source != null) onSourceClick ?: {} else onSenderClick
                Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = source?.name ?: post.senderName,
                    thumb = if (source != null) source.avatar.thumb else post.avatarThumb,
                    fileId = if (source != null) source.avatar.fileId else post.avatarFileId,
                    avatarUrl = if (source != null) null else post.avatarUrl,
                    hostChannel = hostChannel.takeIf { source == null },
                    onClick = onHeaderClick,
                )
                Spacer(Modifier.width(AVATAR_GAP))
                Column(modifier = Modifier.weight(1f)) {
                    HeaderRow(
                    senderName = source?.name ?: post.senderName,
                    editDate = post.editDate,
                    date = post.date,
                    pinned = post.isPinned,
                    // The badge verifies the forwarding channel, not the source it heads.
                    verification = post.verification.takeIf { source == null },
                    // Visual cue that this tap drills into another channel — the
                    // foreign-chat case (admin posting "as one of my other channels") and
                    // a forward's source channel navigate to a separate destination.
                    // User-profile sheet and host-channel filter stay on the same screen,
                    // so they get no chevron — keeps the affordance honest.
                    showDrillChevron = if (source != null) origin !is ForwardOrigin.User else post.senderChatId != null,
                    onChannelClick = onHeaderClick,
                    isDeleted = post.isDeleted,
                    revisionCount = post.revisionCount,
                    onTapRevisions = { onTapRevisions(post) },
                    hostChannel = hostChannel.takeIf { source == null },
                    onHostChannelClick = { interactions.onChannelClick(post) },
                )

                // Quiet second line: the author signature of a signed channel post. Kept off
                // the name line so a long channel name + time + marks never has to share
                // the width with a third, equally truncatable string.
                (if (source != null) source.signature else post.authorSignature)?.takeIf { it.isNotBlank() }?.let { signature ->
                    Text(
                        text = signature,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HortayAlpha.QUIET),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
                // Visible entry to the post's actions (react, save, share, open, copy…).
                // Long-press still works, but a hidden gesture was the ONLY way in before,
                // and most readers never found it. Nudged into the card's end padding so the
                // dots, not their invisible button slack, line up with the content edge.
                if (actionsEnabled && !post.isDeleted) {
                    IconButton(
                        onClick = { sheetOpen = true },
                        modifier = Modifier
                            .offset(x = HortaySpacing.sm)
                            .size(36.dp),
                    ) {
                        Symbol(
                            name = "more_horiz",
                            contentDescription = stringResource(R.string.post_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 20.dp,
                        )
                    }
                }
                }

                // The text fallback for a source that cannot head the card (no avatar).
                if (origin != null && source == null) {
                    Spacer(Modifier.height(6.dp))
                    ForwardChip(origin = origin, onClick = onSourceClick)
                }

                post.reply?.let {
                    Spacer(Modifier.height(HortaySpacing.sm))
                    ReplyBlock(
                        it,
                        // A reply to the same sender's own earlier post: the header right
                        // above already names them.
                        showAuthor = it.otherChannelAvatar != null ||
                            it.replyToChatId != post.chatId ||
                            it.authorName != post.senderName,
                        onClick = {
                            // A hand-picked quote lights up its fragment where the tap lands.
                            if (it.isQuote) QuoteHighlights.show(it.replyToChatId, it.replyToMessageId, it.excerpt)
                            interactions.onQuotedSourceClick(post)
                        },
                    )
                }

                Spacer(Modifier.height(HortaySpacing.sm))
                val translation = interactions.translationFor(post)
                if (translation != null) {
                    TranslationChip(onDismiss = { interactions.onClearTranslationClick(post) })
                    Spacer(Modifier.height(HortaySpacing.sm))
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
                val quoteHighlight = QuoteHighlights.forPost(post.chatId, post.albumMessageIds + post.id)
                CompositionLocalProvider(
                    LocalMediaPassive provides post.isDeleted,
                    LocalQuoteHighlight provides quoteHighlight,
                ) {
                    val body: @Composable () -> Unit = {
                        PostBody(
                            content = post.content,
                            onMediaClick = { _, idx -> interactions.onMediaClick(post, idx) },
                            expanded = expanded,
                            translation = translation,
                            onOpenInSource = { interactions.onOpenClick(post) },
                            pollVoting = pollVoting,
                        )
                    }
                    if (expanded) {
                        // Reading mode bleeds rich media / tables to the card edge (= the window
                        // edge on a phone). The body is inset by the card's horizontal padding on
                        // both sides, so it DECLARES exactly that distance and the rich layer keeps
                        // no PostCard constant. Feed cards render collapsed and never read it.
                        val bleedInset = RichBleedInset(left = CARD_H_PADDING, right = CARD_H_PADDING)
                        CompositionLocalProvider(LocalRichBleedInset provides bleedInset) { body() }
                    } else {
                        body()
                    }
                }

                post.factCheck?.let { note ->
                    Spacer(Modifier.height(10.dp))
                    FactCheckPlate(note)
                }
                val seriesHidden = annotation?.seriesHidden ?: 0
                if (seriesHidden > 0) {
                    Spacer(Modifier.height(10.dp))
                    SeriesMoreButton(count = seriesHidden, latest = annotation?.seriesLatest, onClick = onExpandSeries)
                }
                // Save / Share ride the action row whenever the card offers actions at all —
                // the same gate as the "⋯" sheet, which keeps both as well.
                val showPostActions = actionsEnabled && !post.isDeleted
                if (!post.isDeleted && (showPostActions || post.views > 0 || post.forwardCount > 0 || (post.commentCount ?: 0) > 0 || post.reactions.items.isNotEmpty())) {
                    // xxs, not the old 10 dp: the row's 48 dp touch targets (and the reaction
                    // chips' touch boxes) carry their own invisible slack above the glyphs,
                    // which now supplies the visible gap under the body.
                    Spacer(Modifier.height(HortaySpacing.xxs))
                    // Read in composition: the screen's isBookmarked reads its bookmark
                    // snapshot state, so a save/unsave anywhere repaints this icon.
                    val isSaved = showPostActions && interactions.isBookmarked(post)
                    ActionRow(
                        views = post.views,
                        forwardCount = post.forwardCount,
                        commentCount = post.commentCount,
                        reactions = post.reactions,
                        // The comments anchor (clickable = false) is already the thread, so its
                        // count stays a read-only stat there.
                        onCommentsClick = if (clickable) ({ interactions.onPostClick(post) }) else null,
                        showPostActions = showPostActions,
                        isSaved = isSaved,
                        onSaveClick = { interactions.onBookmarkClick(post) },
                        onShareClick = { interactions.onShareClick(post) },
                        onReactionTap = { item -> interactions.onReactionToggle(post, item) },
                        effectRequest = reactionEffect,
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

        // Hairline between cards, edge to edge (Threads). It used to be inset to the content
        // margins so it read as a quiet break rather than a table grid; with the full-width
        // body the inset line looked like a stray underline of the action row instead, and
        // the extra vertical padding ([CARD_V_PADDING]) now does the "not a grid" work.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        )
    }

    if (sheetOpen) {
        PostContextMenu(
            post = post,
            anchor = menuAnchor,
            interactions = interactions,
            effectRequest = reactionEffect,
            onSelectText = { selectTextOpen = true },
            onSummarize = if (post.canSummarize && summarizer != null) ({ summaryOpen = true }) else null,
            onDismiss = { sheetOpen = false },
        )
    }
    if (selectTextOpen) {
        SelectTextSheet(post = post, onDismiss = { selectTextOpen = false })
    }
    if (summaryOpen && summarizer != null) {
        PostSummarySheet(post = post, summarizer = summarizer, onDismiss = { summaryOpen = false })
    }
}

/** What heads a forward's card: the original source, when it has an avatar to draw. */
private class ForwardSource(val name: String, val signature: String?, val avatar: ChannelAvatarRef)

private fun ForwardOrigin.headerSource(): ForwardSource? = when (this) {
    is ForwardOrigin.Channel -> avatar?.let { ForwardSource(channelName, authorSignature, it) }
    is ForwardOrigin.Chat -> avatar?.let { ForwardSource(chatName, authorSignature, it) }
    is ForwardOrigin.User -> avatar?.takeIf { userId != null }?.let { ForwardSource(userName, null, it) }
    is ForwardOrigin.HiddenUser -> null
}

/** The post's own sender (the channel, or its named author) as a forwarder of the row above. */
private fun TimelinePost.asForwarderBadge() = ChannelBadge(
    chatId = chatId,
    title = senderName,
    thumb = avatarThumb,
    fileId = avatarFileId,
    avatarUrl = avatarUrl,
    latestPostDate = date,
)

/**
 * "Forwarded by A" / "Also forwarded by A, B and N more" above a post: who brought this
 * original into the feed. Either the post's own channel when the source heads the card,
 * the channels whose copies [clusterFeed] hid, or both. Up to three overlapping avatars.
 * [onClick] opens the first forwarder (the post's own channel).
 */
@Composable
private fun ForwardedByRow(
    forwarders: kotlinx.collections.immutable.ImmutableList<ChannelBadge>,
    @androidx.annotation.StringRes label: Int,
    onClick: (() -> Unit)? = null,
) {
    val names = when (forwarders.size) {
        0 -> ""
        1 -> forwarders[0].title
        2 -> stringResource(R.string.feed_forwarders_two, forwarders[0].title, forwarders[1].title)
        else -> {
            val more = forwarders.size - 2
            pluralStringResource(
                R.plurals.feed_forwarders_more,
                more,
                forwarders[0].title,
                forwarders[1].title,
                more,
            )
        }
    }
    Row(
        modifier = if (onClick != null) {
            Modifier.clip(InlineChipCorner).clickable(role = Role.Button, onClick = onClick)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = "line_forward", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Box {
            forwarders.take(3).forEachIndexed { i, ch ->
                Box(
                    modifier = Modifier
                        .padding(start = (i * 12).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
                ) {
                    TdAvatar(
                        name = ch.title,
                        thumb = ch.thumb,
                        fileId = ch.fileId,
                        remoteUrl = ch.avatarUrl,
                        size = 20.dp,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(label, names),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Telegram's official fact-check note, as a warm-tinted plate under the post body. */
@Composable
private fun FactCheckPlate(note: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(name = "verified", tint = MaterialTheme.colorScheme.onTertiaryContainer, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.post_fact_check_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** "N more posts in this series" — expands a burst [clusterFeed] folded under this post. */
@Composable
private fun SeriesMoreButton(count: Int, latest: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = "dynamic_feed", tint = MaterialTheme.colorScheme.primary, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pluralStringResource(R.plurals.feed_series_more, count, count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!latest.isNullOrBlank()) {
                Text(
                    text = latest,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Symbol(name = "chevron_right", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
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
    /**
     * The channel a personal-author post was published in. Its avatar rides the author's as
     * a corner badge, so a feed of several multi-author channels shows at a glance which
     * channel a face belongs to; the name is spelled out on the header line.
     */
    hostChannel: ChannelContext?,
    onClick: () -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        ) {
            TdAvatar(
                name = name,
                thumb = thumb,
                fileId = fileId,
                size = AVATAR_SIZE,
                background = avatarBg(name),
                remoteUrl = avatarUrl,
            )
        }
        if (hostChannel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = HOST_BADGE_OVERHANG, y = HOST_BADGE_OVERHANG)
                    .size(HOST_BADGE_SIZE)
                    .clip(CircleShape)
                    // A ring in the page colour separates the badge from the avatar under it.
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp),
            ) {
                TdAvatar(
                    name = hostChannel.name,
                    thumb = hostChannel.avatarThumb,
                    fileId = hostChannel.avatarFileId,
                    size = HOST_BADGE_SIZE - 3.dp,
                    background = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** The host channel's avatar badge on a personal-author post's avatar. */
private val HOST_BADGE_SIZE = 18.dp

/** How far the badge hangs past the avatar's corner. */
private val HOST_BADGE_OVERHANG = 3.dp

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
 * The header line next to the avatar: the sender name (+ verification, + drill chevron), then
 * the quiet metadata — relative time and the deleted / revisions / edited / pinned marks — on
 * the SAME line, Threads-style ("Channel  5 хв"). The author signature, when present, sits on a
 * quiet line below (laid out by the caller).
 *
 * A personal-author post (a channel whose admins post under their own names) names its
 * channel in the metadata, before the time: "Author  у Channel · 5 хв". It used to be a
 * separate chip with its own avatar on a line under the header, which gave such channels a
 * taller header than every other post and one more container before the text. The channel
 * name is the one metadata piece that may be ellipsised — when it does not fit beside the
 * name, the whole metadata drops under it (see [NameAndMetaLayout]) and it gets the full line.
 *
 * History: the metadata used to be stacked under the name (Threads / Bluesky idiom) so a long
 * channel name never fought the timestamp for one line. [NameAndMetaLayout] keeps that
 * guarantee without the second line: the time and marks are measured first and never
 * ellipsised, the name takes the remaining width and ellipsises, and only when that leftover
 * would squeeze the name below a readable width (font scale 2.0, a long Arabic locale time,
 * several marks) does the metadata drop under the name — the old stacked layout, as a fallback.
 *
 * Only the name drills into the sender ([onChannelClick]); the metadata keeps its own
 * affordances (the host channel, the revisions chip) and is otherwise inert, so the empty gap after a short
 * name never swallows taps meant for the card.
 */
@Composable
private fun HeaderRow(
    senderName: String,
    editDate: Long,
    date: Long,
    pinned: Boolean,
    verification: SenderVerification?,
    showDrillChevron: Boolean,
    onChannelClick: () -> Unit,
    isDeleted: Boolean = false,
    revisionCount: Int = 0,
    onTapRevisions: () -> Unit = {},
    hostChannel: ChannelContext? = null,
    onHostChannelClick: () -> Unit = {},
) {
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HortayAlpha.QUIET)
    NameAndMetaLayout(
        name = {
            Row(
                modifier = Modifier
                    .clip(InlineChipCorner)
                    .clickable(role = Role.Button, onClick = onChannelClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
        },
        meta = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hostChannel != null) {
                    Text(
                        text = stringResource(R.string.post_in_channel, hostChannel.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(InlineChipCorner)
                            .clickable(role = Role.Button, onClick = onHostChannelClick),
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = metaColor,
                    )
                }
                TimestampText(date)
                // Pencil-icon-vs-chip resolution: when archive has captured revisions, the
                // chip subsumes the pencil — tapping opens the revision sheet AND
                // communicates "edited". When archive is off or this post was edited before
                // the feature was enabled (editDate > 0 but no captured versions), we keep
                // the original non-interactive pencil so the indicator never disappears for
                // existing users.
                when {
                    isDeleted -> {
                        Spacer(Modifier.width(6.dp))
                        DeletedBadge()
                    }
                    revisionCount > 0 -> {
                        Spacer(Modifier.width(6.dp))
                        EditedChip(count = revisionCount, onClick = onTapRevisions)
                    }
                    editDate > 0L -> {
                        Spacer(Modifier.width(6.dp))
                        Symbol(
                            name = "line_edit",
                            contentDescription = stringResource(R.string.post_badge_edited),
                            tint = metaColor,
                            size = HEADER_MARK_SIZE,
                        )
                    }
                }
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Symbol(
                        name = "line_pin",
                        contentDescription = stringResource(R.string.post_badge_pinned),
                        tint = MaterialTheme.colorScheme.primary,
                        size = HEADER_MARK_SIZE,
                    )
                }
            }
        },
    )
}

/** Edited / pinned marks after the header time: the post card's line glyphs at text height. */
private val HEADER_MARK_SIZE = 14.dp

/** Gap between the sender name and the time when they share one line. */
private val NAME_META_GAP = 6.dp

/**
 * Below this share of the line the name would be cut to a stub ("Укр…"); the metadata then
 * drops under the name instead of squeezing it. Never triggers at the default font scale for
 * a typical "5 хв" — it is the large-font / many-marks fallback.
 */
private const val NAME_MIN_SHARE = 0.4f

/**
 * Two slots on one line, aligned on their first baselines: [name] takes whatever width [meta]
 * leaves and ellipsises inside it; [meta] is measured first at its natural width so it is never
 * truncated. When the leftover can't hold [NAME_MIN_SHARE] of the line (or the whole name, if
 * shorter), [meta] moves to its own line under [name]. RTL is handled by `placeRelative`.
 */
@Composable
private fun NameAndMetaLayout(
    name: @Composable () -> Unit,
    meta: @Composable () -> Unit,
) {
    Layout(contents = listOf(name, meta)) { (nameMeasurables, metaMeasurables), constraints ->
        val nameMeasurable = nameMeasurables.first()
        val maxWidth = constraints.maxWidth
        val gap = NAME_META_GAP.roundToPx()
        val metaPlaceable = metaMeasurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val nameNeeded = minOf(
            nameMeasurable.maxIntrinsicWidth(constraints.maxHeight),
            (maxWidth * NAME_MIN_SHARE).toInt(),
        )
        val leftover = maxWidth - metaPlaceable.width - gap
        val oneLine = leftover >= nameNeeded
        val namePlaceable = nameMeasurable.measure(
            Constraints(maxWidth = if (oneLine) leftover.coerceAtLeast(0) else maxWidth),
        )
        if (oneLine) {
            val nameBaseline = namePlaceable[FirstBaseline]
            val metaBaseline = metaPlaceable[FirstBaseline]
            val (nameY, metaY) = if (nameBaseline != AlignmentLine.Unspecified && metaBaseline != AlignmentLine.Unspecified) {
                val baseline = maxOf(nameBaseline, metaBaseline)
                (baseline - nameBaseline) to (baseline - metaBaseline)
            } else {
                val h = maxOf(namePlaceable.height, metaPlaceable.height)
                ((h - namePlaceable.height) / 2) to ((h - metaPlaceable.height) / 2)
            }
            val height = maxOf(nameY + namePlaceable.height, metaY + metaPlaceable.height)
            layout(maxWidth, height) {
                namePlaceable.placeRelative(0, nameY)
                metaPlaceable.placeRelative(namePlaceable.width + gap, metaY)
            }
        } else {
            layout(maxWidth, namePlaceable.height + metaPlaceable.height) {
                namePlaceable.placeRelative(0, 0)
                metaPlaceable.placeRelative(0, namePlaceable.height)
            }
        }
    }
}

/**
 * C4 — post-header timestamp. `bodyMedium` (14 sp, Regular) in `onSurfaceVariant @ QUIET`: it
 * shares the name's line now, so it is one step under the 15 sp SemiBold name in both size and
 * weight instead of the old 11 sp `labelSmall` that read as a caption under it. Tabular figures
 * (C2) so a "5 хв" → "12 хв" tick never reflows the marks after it. One composable shared by
 * the deleted / edited / plain header branches keeps the three timestamps in lockstep.
 */
@Composable
private fun TimestampText(date: Long) {
    Text(
        text = formatRelative(date),
        style = MaterialTheme.typography.bodyMedium.tabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HortayAlpha.QUIET),
        maxLines = 1,
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
            .clickable(role = Role.Button, onClick = onDismiss),
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
            label = stringResource(R.string.post_badge_scam),
            color = MaterialTheme.colorScheme.error,
        )
        SenderVerification.Fake -> WarningPill(
            label = stringResource(R.string.post_badge_fake),
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
 * "Forwarded from X" under the header — the fallback for a source that cannot head the
 * card itself (guest pages and hidden senders carry no avatar). One quiet line in the metadata colour — the same
 * voice as the "Also forwarded by" row and the header time — with only the source's name
 * lifted to the text colour when it leads somewhere. It used to be all tertiary, a second
 * accent colour between the header and a reply block that already carries the primary one.
 */
@Composable
private fun ForwardChip(origin: ForwardOrigin, onClick: (() -> Unit)?) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val nameColor = MaterialTheme.colorScheme.onSurface
    // A hidden source can come without any name (guest pages omit it).
    val source = forwardLabel(origin)
    val label = if (source.isBlank()) stringResource(R.string.post_forwarded)
    else stringResource(R.string.post_forwarded_from, source)
    val text = remember(label, source, onClick != null, nameColor) {
        buildAnnotatedString {
            append(label)
            val at = if (source.isBlank() || onClick == null) -1 else label.indexOf(source)
            if (at >= 0) {
                addStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium), at, at + source.length)
            }
        }
    }
    Row(
        modifier = if (onClick != null) {
            Modifier.clip(InlineChipCorner).clickable(role = Role.Button, onClick = onClick)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = "line_forward", tint = quiet, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = quiet,
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
 *   • optional 36 dp thumbnail on the right — shown when TDLib gave us a parent media
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
 *     The glyph is suppressed when a thumbnail is present: the 36 dp media tile
 *     already signals "this is a quoted media post", and an icon over the tile's
 *     leading corner would crash visually.
 *   • Compact by default: the block identifies the parent, it does not preview it. A plain
 *     reply shows one line of the parent (a hand-picked quote up to [REPLY_QUOTE_MAX_LINES]),
 *     and the author line is dropped ([showAuthor] = false) when the parent is the same
 *     sender's own earlier post — the card's header already says who that is. Two lines of
 *     parent text plus a name made every reply open with a block as tall as a short post.
 *   • Shape is [MaterialTheme.shapes.extraSmall] (8 dp) — small enough that the
 *     3 dp accent bar's corners read as designed-in softening rather than a
 *     clipping artefact, and one tier tighter than the host PostCard's `medium`
 *     so the chip nests cleanly inside it (M3E nested-radius rule).
 */
@Composable
private fun ReplyBlock(reply: ReplyPreview, showAuthor: Boolean = true, onClick: () -> Unit = {}) {
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
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
            ) {
                if (showAuthor) Row(verticalAlignment = Alignment.CenterVertically) {
                    // A reply to ANOTHER channel's post carries that channel's avatar, so
                    // it reads as a cross-post (tapping it leaves this channel) rather
                    // than as one more message of the same thread.
                    reply.otherChannelAvatar?.let { avatar ->
                        TdAvatar(
                            name = reply.authorName,
                            thumb = avatar.thumb,
                            fileId = avatar.fileId,
                            size = 16.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = reply.authorName,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
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
                            // A plain reply only has to identify its parent; a hand-picked
                            // quote is content the author chose, so it gets room.
                            maxLines = if (reply.isQuote) REPLY_QUOTE_MAX_LINES else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Thumbnail (when available) — [REPLY_THUMB_SIZE] square at the trailing edge.
            reply.mediaThumb?.let { thumb ->
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp, top = 4.dp, bottom = 4.dp)
                        .size(REPLY_THUMB_SIZE)
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
                // Another channel's post: an "opens elsewhere" chevron instead of the reply arrow.
                name = if (reply.otherChannelAvatar != null) "chevron_right" else "reply",
                tint = accent.copy(alpha = HortayAlpha.FAINT),
                size = 14.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

/** Lines of a hand-picked quote shown in the reply block. */
private const val REPLY_QUOTE_MAX_LINES = 3

/** The parent's media thumbnail in the reply block. */
private val REPLY_THUMB_SIZE = 36.dp

// Label / symbol mappings live in `ReplyKindResources.kt` so PostCard and CommentsScreen
// share one source of truth — keeps icons and wording in lockstep across surfaces.

/**
 * Reactions, then the action line. Reactions wrap onto as many lines as they need
 * instead of scrolling sideways — a single scrolling line clipped the last chip at the
 * screen edge, which read as broken.
 *
 * The action line is ONE evenly spaced run from the leading edge, Threads-style: comments
 * (tappable, opens the thread) · forward (the share control, carrying the forward count) ·
 * Save · views (read-only metadata, last). Every item is the same [ActionItem] — the same [ACTION_ICON_SIZE] line
 * glyph, the same count style, the same padding — so the eye reads one rhythm, not "stats on
 * the left, buttons on the right". Save used to live only inside the "⋯" sheet even though
 * the app has a whole Saved tab; one tap on the bookmark now keeps a post, and the sheet
 * keeps Save / Share as well.
 *
 * Why Save / Share follow the stats instead of being pushed to the trailing edge (where they
 * sat before, behind a flexible spacer): on a post with no stats the pair floated alone at
 * the far right with an empty line to its left, and on a post with stats the spacer opened a
 * hole in the middle of the line, so the row read as two unrelated clusters. Following the
 * stats, a stat-less post shows Share · Save where every other post's line starts, and a
 * post with large counts ("13,5 тис.") simply pushes them right. The line is a [FlowRow], so
 * at large font scales the run wraps (views first) instead of clipping or overlapping.
 *
 * Comments with no count stay hidden, including `commentCount == 0` posts that do have a
 * discussion group: Hortay only reads threads, so a bare comment glyph would invite an action
 * that lands on an empty "no comments yet" thread — and the card tap already opens it.
 *
 * Height budget: Save, Share and the comments item each reserve a 48 dp touch target
 * ([minimumInteractiveComponentSize]), which is taller than the old ~28 dp stats line. The
 * slack above the glyphs replaces the spacer that used to sit over this row, and the row
 * reports [ACTION_ROW_BOTTOM_OVERHANG] less height than it lays out so the slack under
 * the glyphs overlaps the card's own bottom padding instead of stacking on it — the card
 * grows by a few dp, not by the full touch-target delta. Horizontally the items' own padding
 * bleeds past the content edge ([bleedHorizontal]) so the first glyph lines up with the text.
 *
 * Glyph size history: the C1 pass shrank the stat glyphs to 14 dp because the weight-500
 * Material glyphs at 18/22 dp outweighed their 12 sp counts and lifted the row to the body's
 * visual level, while icon-only Save / Share needed 20 dp to read as buttons — two glyph
 * sizes on one line. The line now uses the thin `line_*` stroke family (see [Symbol]) at one
 * 20 dp size: a 1.9-stroke outline carries far less ink than a filled weight-500 glyph, so it
 * sits beside a quiet 14 sp count without the C1 problem coming back. Don't put weight-500
 * glyphs back on this line at 20 dp — that is exactly what C1 removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    views: Int,
    forwardCount: Int,
    commentCount: Int?,
    reactions: dev.lyo.hortay.data.Reactions,
    /** Null renders the comment count as a read-only stat (the comments anchor itself). */
    onCommentsClick: (() -> Unit)?,
    /** Whether Save / Share render at all — the same gate as the "⋯" action sheet. */
    showPostActions: Boolean = false,
    isSaved: Boolean = false,
    onSaveClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onReactionTap: (ReactionItem) -> Unit = {},
    /**
     * Invoked when the user taps the snackbar action button for a paid (⭐)
     * reaction. The callsite already knows how to open *this specific* post in
     * Telegram (via [PostInteractions.onOpenClick] → [PostActions.openInTelegram]),
     * so ActionRow stays ignorant of post identity.
     */
    onPaidReactionOpenPost: () -> Unit = {},
    /** Picker → chip hand-off for the choose animation; see [ReactionEffectRequest]. */
    effectRequest: ReactionEffectRequest? = null,
) {
    val haptics = LocalHapticFeedback.current
    if (reactions.items.isNotEmpty()) {
        // Paid (⭐) reactions can't be cast from Hortay — sending one needs the
        // star-amount confirmation flow we don't host. Tap surfaces a snackbar
        // explaining the situation, with an action button that opens *this
        // specific* post in Telegram (via the per-post callback, routed
        // through [PostActions.openInTelegram]). The chip is rendered at the
        // [HortayAlpha.FAINT] alpha to read as informational rather than interactive (see
        // [ReactionChip.disabled]); the snackbar gate keeps a misclick from
        // bouncing the user out of Hortay before they can change their mind.
        val res = LocalContext.current.resources
        val bus = dev.lyo.hortay.ui.main.LocalUserMessageBus.current
        val openTelegramLabel = res.getString(R.string.action_open_telegram)
        val paidExplainer = res.getString(R.string.reactions_paid_explainer)
        // Rows of chips stack with NO extra gap: every chip reserves a
        // [REACTION_TOUCH_MIN_HEIGHT] touch box around its drawn pill (see [ReactionChip]),
        // and that slack is the visible gap between chip rows.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HortaySpacing.sm),
        ) {
            // Key each chip by its reaction-bucket identity, NOT by position. TDLib ranks
            // reaction buckets by frequency and re-orders them on every
            // UpdateMessageInteractionInfo; a positional (unkeyed) loop would then reuse a
            // chip slot that was rendering custom emoji A to render custom emoji B, and the
            // underlying Coil AsyncImage keeps painting A's already-decoded bitmap until B
            // decodes — so two different reactions momentarily render the SAME custom emoji.
            // A stable key moves composition state with the bucket instead of reusing by slot.
            reactions.items.forEach { item ->
                key(item.kind.stableKey) {
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
                    ReactionChip(item, onClick = tapHandler, disabled = isPaid, effectRequest = effectRequest)
                }
            }
        }
    }
    // `commentCount == null` = channel has no linked discussion group (comments
    // fundamentally disabled). `commentCount == 0` = group exists but no replies
    // yet. Both read as "nothing to see" to the user, so we collapse them into
    // one branch and only surface the item when the count is meaningfully > 0 —
    // mirrors the empty-state contract on CommentsScreen (no "0 replies" subtitle).
    val safeCommentCount = commentCount ?: 0
    val hasComments = safeCommentCount > 0
    if (!hasComments && views <= 0 && forwardCount <= 0 && !showPostActions) return
    FlowRow(
        modifier = Modifier
            .bleedHorizontal(ACTION_ITEM_H_PADDING)
            .fillMaxWidth()
            .trimBottom(ACTION_ROW_BOTTOM_OVERHANG),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasComments) {
            ActionItem(
                symbol = "line_comment",
                count = formatViews(safeCommentCount),
                onClick = onCommentsClick,
            )
        }
        // Forwarding is ONE item: the forward arrow carries the forward count and, where the
        // card offers actions, is the Share button. They used to be two neighbours — a
        // read-only "↗ 3" that looked tappable and was not, and a separate Share glyph —
        // which asked the reader to tell a stat from a button by trial. A count of how many
        // times a post was shared belongs on the control that shares it (Threads, X).
        if (showPostActions || forwardCount > 0) {
            ActionItem(
                symbol = "line_forward",
                count = if (forwardCount > 0) formatViews(forwardCount) else null,
                contentDescription = if (showPostActions) stringResource(R.string.post_share) else null,
                onClick = if (showPostActions) onShareClick else null,
            )
        }
        if (showPostActions) {
            val saveTint by animateColorAsState(
                targetValue = if (isSaved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "post-save-tint",
            )
            ActionItem(
                symbol = "line_bookmark",
                filled = isSaved,
                tint = saveTint,
                contentDescription = stringResource(if (isSaved) R.string.post_unsave else R.string.post_save),
                onClick = {
                    // Same toggle haptic as the sheet's Save and the reaction chips.
                    haptics.performHapticFeedback(
                        if (isSaved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    onSaveClick()
                },
            )
        }
        // Views trail the run: the only item that is never a control, so it reads as the
        // line's closing metadata instead of sitting between two buttons.
        if (views > 0) {
            ActionItem(symbol = "line_views", count = formatViews(views))
        }
    }
}

/**
 * Widens the content by [bleed] on both sides and centres it back, so children whose padding
 * is invisible touch slack line their visible glyphs up with the surrounding content edge.
 * Reports the original width; `placeRelative` keeps the bleed symmetric in RTL.
 */
private fun Modifier.bleedHorizontal(bleed: Dp): Modifier = layout { measurable, constraints ->
    val bleedPx = bleed.roundToPx()
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + bleedPx * 2,
            maxWidth = constraints.maxWidth + bleedPx * 2,
        )
    } else constraints
    val placeable = measurable.measure(widened)
    val width = (placeable.width - bleedPx * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) {
        placeable.placeRelative(-bleedPx, 0)
    }
}

/**
 * Lays the content out at full height but reports [trim] less, so the bottom [trim] of it
 * (invisible touch-target slack) overlaps whatever padding follows instead of adding to the
 * card height. Placement is unchanged; nothing is clipped.
 */
private fun Modifier.trimBottom(trim: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trimPx = trim.roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - trimPx) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * One item of the post's action line: a [ACTION_ICON_SIZE] line glyph with an optional count
 * after it. Tappable items (comments, Save, Share) get a 48 dp touch target and a pill ripple;
 * read-only stats (views, and the forward count on a card without actions) are the same shape without a click, so the whole line
 * shares one rhythm. Every item carries [ACTION_ITEM_H_PADDING] on both sides — that padding
 * IS the spacing between items (no extra arrangement gap), which keeps the visible gap between
 * neighbouring glyph groups even whether an item is tappable or not.
 */
@Composable
private fun ActionItem(
    symbol: String,
    count: String? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    filled: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = HortayExpressive.Pill.asComposeShape()
    Row(
        modifier = if (onClick != null) {
            // The M3 IconButton recipe: minimum touch size first, then shape + clickable.
            Modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
        } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ACTION_ITEM_H_PADDING, vertical = HortaySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                name = symbol,
                contentDescription = contentDescription,
                tint = tint,
                size = ACTION_ICON_SIZE,
                filled = filled,
            )
            if (count != null) {
                Spacer(Modifier.width(HortaySpacing.xs))
                Text(
                    text = count,
                    // C2 — tabular figures so a view-count tick never reflows the item
                    // width and shifts its neighbours.
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Glyph size of every item on the action line (line family, see [ActionRow]). */
private val ACTION_ICON_SIZE = 20.dp

/**
 * Horizontal padding of each action-line item: half the visible gap between neighbouring
 * glyph groups (12 + 12 = 24 dp, the Threads spacing), bled past the content edge at the ends.
 */
private val ACTION_ITEM_H_PADDING = HortaySpacing.md

/**
 * Touch-target slack under the action line's glyphs that overlaps the card's bottom
 * padding instead of growing the card (see [ActionRow] and [trimBottom]).
 */
private val ACTION_ROW_BOTTOM_OVERHANG = 6.dp

/** Minimum touch height of a reaction chip; the drawn pill stays its own (smaller) size. */
private val REACTION_TOUCH_MIN_HEIGHT = 40.dp

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
    /**
     * Picker → chip hand-off: when it names this chip's reaction and the reaction reads as
     * chosen, the chip plays the choose animation once (see [ReactionEffectRequest]).
     */
    effectRequest: ReactionEffectRequest? = null,
) {
    // Choose animation (Telegram's centre + around one-shot, see [ReactionEmojiGlyph]).
    // Incremented ONLY by the user's own action — a tap that turns this reaction chosen,
    // or a picker request for it — never by an incoming reaction update, which can flip
    // `isChosen` (another device) but goes through neither path. Lives in `remember`, so
    // a chip entering composition (scroll-back, recycled item, new bucket) starts at 0
    // and plays nothing.
    var chooseEffectTrigger by remember { mutableIntStateOf(0) }
    val isUnicode = item.kind is ReactionKind.Emoji
    if (effectRequest != null && isUnicode) {
        val itemKey = item.kind.stableKey
        val requested by remember(effectRequest, itemKey) {
            derivedStateOf { effectRequest.key == itemKey }
        }
        LaunchedEffect(requested, item.isChosen) {
            if (requested && item.isChosen) {
                chooseEffectTrigger++
                effectRequest.consume()
            }
        }
    }
    val chipClick: (() -> Unit)? = onClick?.let { click ->
        {
            if (isUnicode && !disabled && !item.isChosen) chooseEffectTrigger++
            click()
        }
    }
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
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = HortayAlpha.FAINT),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reaction-bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (item.isChosen) MaterialTheme.colorScheme.primary.copy(alpha = HortayAlpha.QUIET)
        else Color.Transparent,
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
    // The N5 micro-burst (a generic particle spray on choose) was tried here and removed:
    // the particles flew outside the chip bounds by design and read as a rendering glitch
    // ("a stray piece of the digit above the button"), not celebration. What plays now is
    // Telegram's own reaction artwork (centre + around animation, see [ReactionEmojiGlyph]),
    // owner-requested to match the Telegram app: only on the user's own choose, only with
    // the Telegram emoji style, never under reduced motion, centred on the glyph. That is
    // why the pill no longer clips its content — the shape clip moved to the ripple layer,
    // so the burst can draw past the pill. Don't add other unclipped overlays here.
    //
    // Touch box vs drawn pill: the pill draws ~30 dp tall, too small a target in a wrapped
    // row of neighbours. The clickable sits on an outer box at least
    // [REACTION_TOUCH_MIN_HEIGHT] tall with the pill centred inside; the press ripple is
    // re-attached to the pill itself via [indication] on the SAME interactionSource, so the
    // ripple and the corner morph still trace the pill, not the invisible touch box.
    val pillIndication = LocalIndication.current
    Box(
        modifier = if (chipClick != null) {
            Modifier
                .heightIn(min = REACTION_TOUCH_MIN_HEIGHT)
                .semantics { selected = item.isChosen }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = chipClick,
                )
        } else Modifier,
        contentAlignment = Alignment.Center,
    ) {
        // The pill: shape-aware background + border, NO content clip (see the burst note
        // above). The ripple keeps the pill shape through its own clipped layer, drawn
        // after the content exactly as the old `clip + indication` chain drew it.
        Box(
            modifier = Modifier
                .background(container, shape)
                .border(1.dp, borderColor, shape),
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (disabled) Modifier.alpha(HortayAlpha.FAINT) else Modifier,
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (val k = item.kind) {
                    is ReactionKind.Emoji -> ReactionEmojiGlyph(
                        emoji = k.text,
                        style = MaterialTheme.typography.titleSmall,
                        chooseEffectTrigger = chooseEffectTrigger,
                    )
                    is ReactionKind.CustomEmoji -> CustomEmojiInlineView(
                        customEmojiId = k.customEmojiId,
                        modifier = Modifier.size(18.dp),
                        tintColor = tintForCustom,
                        contentDescription = null,
                    )
                    // Paid star reaction — Telegram renders a small ⭐ pill, no count
                    // separator needed because the count is already the star total.
                    is ReactionKind.Paid -> Text(
                        text = "⭐",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.width(5.dp))
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
            if (chipClick != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .indication(interactionSource, pillIndication),
                )
            }
        }
    }
}

/**
 * The post's long-press / "⋯" menu: the reaction capsule above the post and a compact
 * card of actions below it, both anchored to the card (see [AnchoredContextMenu]).
 *
 * Deliberately no Comments action and no button group: tapping the card already opens
 * the thread, Share and Save also sit in the card's own action row, and two filled
 * buttons on top of the list outweighed everything else in the menu.
 */
@Composable
private fun PostContextMenu(
    post: TimelinePost,
    anchor: ContextMenuAnchor,
    interactions: PostInteractions,
    effectRequest: ReactionEffectRequest,
    onSelectText: () -> Unit,
    onSummarize: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isBookmarked = interactions.isBookmarked(post)
    // Available reactions are fetched once when the menu opens (a single on-demand RPC;
    // empty in guest mode → no capsule).
    var available by remember(post.id) { mutableStateOf<List<ReactionKind>>(emptyList()) }
    LaunchedEffect(post.id) { available = interactions.availableReactions(post) }
    val chosenKeys = remember(post.reactions) {
        post.reactions.items.filter { it.isChosen }.map { it.kind.stableKey }.toSet()
    }

    AnchoredContextMenu(
        anchor = anchor,
        onDismiss = onDismiss,
        header = {
            if (available.isNotEmpty()) {
                ContextMenuCapsule {
                    ReactionPickerStrip(
                        reactions = available,
                        chosenKeys = chosenKeys,
                        onPick = { kind ->
                            val already = kind.stableKey in chosenKeys
                            haptics.performHapticFeedback(
                                if (already) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                            )
                            // Choosing (not retracting) plays the choose animation on the
                            // card's chip once the optimistic flip shows it as chosen.
                            if (!already) effectRequest.request(kind)
                            close {
                                interactions.onReactionToggle(post, ReactionItem(kind, count = 0, isChosen = already))
                            }
                        },
                    )
                }
            }
        },
        actions = {
            ContextMenuCard {
                ContextMenuItem(
                    symbol = "bookmark",
                    label = stringResource(if (isBookmarked) R.string.post_unsave else R.string.post_save),
                    onClick = {
                        haptics.performHapticFeedback(
                            if (isBookmarked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        close { interactions.onBookmarkClick(post) }
                    },
                )
                ContextMenuItem(
                    symbol = "share",
                    label = stringResource(R.string.post_share),
                    onClick = { close { interactions.onShareClick(post) } },
                )
                ContextMenuItem(
                    symbol = "open_in_new",
                    label = stringResource(R.string.post_open_telegram),
                    onClick = { close { interactions.onOpenClick(post) } },
                )
                val hasText = post.content.captionPlain.isNotBlank()
                if (hasText || onSummarize != null) ContextMenuDivider()
                if (hasText) {
                    ContextMenuItem(
                        symbol = "content_copy",
                        label = stringResource(R.string.post_copy_text),
                        onClick = { close { interactions.onCopyClick(post) } },
                    )
                    ContextMenuItem(
                        symbol = "description",
                        label = stringResource(R.string.post_select_text),
                        onClick = { close { onSelectText() } },
                    )
                    if (interactions.translateEnabled) {
                        val translated = interactions.isTranslated(post)
                        ContextMenuItem(
                            symbol = "translate",
                            label = stringResource(if (translated) R.string.post_show_original else R.string.post_translate),
                            onClick = {
                                close {
                                    if (translated) interactions.onClearTranslationClick(post)
                                    else interactions.onTranslateClick(post)
                                }
                            },
                        )
                    }
                }
                if (onSummarize != null) {
                    ContextMenuItem(
                        symbol = "lightbulb",
                        label = stringResource(R.string.post_summarize),
                        onClick = { close { onSummarize() } },
                    )
                }
                if (interactions.canReport(post)) {
                    ContextMenuDivider()
                    ContextMenuItem(
                        symbol = "flag",
                        label = stringResource(R.string.report_action),
                        destructive = true,
                        onClick = { close { interactions.onReportClick(post) } },
                    )
                }
            }
        },
    )
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
    val sheetState = rememberExpandedSheetState()
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
 * Telegram-style reaction picker. Collapsed it is one row: the leading reactions (TDLib's
 * top ones come first in [reactions]) and, when there are more than fit, an expand button
 * in the last slot. Expanded it is a grid of every available reaction on the same column
 * rhythm, scrolling inside a capped height — no sideways scrolling to hunt through.
 *
 * Reactions the user already cast read as "selected" (a tertiaryContainer disc); the rest
 * are bare emoji on the menu capsule the strip is hosted in. Tapping casts (or retracts) via the same optimistic
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
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        val perRow = ((maxWidth + REACTION_SLOT_GAP) / (REACTION_SLOT + REACTION_SLOT_GAP))
            .toInt()
            .coerceAtLeast(2)
        val overflows = reactions.size > perRow
        if (expanded) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(perRow),
                modifier = Modifier.fillMaxWidth().heightIn(max = REACTION_SLOT * REACTION_GRID_MAX_ROWS),
                horizontalArrangement = Arrangement.spacedBy(REACTION_SLOT_GAP),
                verticalArrangement = Arrangement.spacedBy(REACTION_SLOT_GAP),
            ) {
                items(reactions, key = { it.stableKey }) { kind ->
                    Box(contentAlignment = Alignment.Center) {
                        ReactionPickerTarget(
                            kind = kind,
                            chosen = kind.stableKey in chosenKeys,
                            appearOrder = NO_APPEAR,
                            onPick = onPick,
                        )
                    }
                }
            }
        } else {
            val shown = if (overflows) reactions.take(perRow - 1) else reactions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(REACTION_SLOT_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEachIndexed { index, kind ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        ReactionPickerTarget(
                            kind = kind,
                            chosen = kind.stableKey in chosenKeys,
                            // Pops in as the strip opens, staggered left to right.
                            appearOrder = index,
                            onPick = onPick,
                        )
                    }
                }
                if (overflows) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(REACTION_DISC)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { expanded = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Symbol(
                                name = "keyboard_arrow_down",
                                contentDescription = stringResource(R.string.reaction_picker_more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // Keep a short list on the same column rhythm instead of stretching it.
                    repeat(perRow - shown.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerTarget(
    kind: ReactionKind,
    chosen: Boolean,
    appearOrder: Int,
    onPick: (ReactionKind) -> Unit,
) {
    // The strip sits in a floating capsule, so only a reaction the user already cast
    // carries a disc; the rest are bare emoji on the capsule's own surface.
    val background = if (chosen) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(REACTION_DISC)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button) { onPick(kind) },
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            is ReactionKind.Emoji -> ReactionEmojiGlyph(
                emoji = kind.text,
                style = MaterialTheme.typography.headlineSmall,
                appearOrder = appearOrder,
            )
            is ReactionKind.CustomEmoji -> CustomEmojiInlineView(
                customEmojiId = kind.customEmojiId,
                modifier = Modifier.size(28.dp),
                contentDescription = null,
            )
            // Paid reactions are filtered out upstream (can't be cast from Hortay).
            is ReactionKind.Paid -> Unit
        }
    }
}

private val REACTION_DISC = 44.dp
private val REACTION_SLOT = 48.dp
private val REACTION_SLOT_GAP = 4.dp

/** Rows of the expanded reaction grid visible at once; the half row hints at scrolling. */
private const val REACTION_GRID_MAX_ROWS = 5.5f

internal fun formatViews(count: Int): String {
    fun compact(value: Double, suffix: String): String {
        val text = String.format(java.util.Locale.ROOT, "%.1f", value)
        return (if (text.endsWith(".0")) text.dropLast(2) else text) + suffix
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count / 1_000.0, "K")
        else -> compact(count / 1_000_000.0, "M")
    }
}


/**
 * Telegram sends some reactions without the emoji variation selector — "❤" is a bare
 * U+2764, and "❤‍🔥" is U+2764 + ZWJ + 🔥 with no U+FE0F in between. Text fonts carry a
 * monochrome glyph for such a text-default code point, so the heart drew black, and the
 * ZWJ sequence — which is only defined with the selector — fell apart into two emoji.
 * Adding the selector after a lone code point, and after a BMP symbol that leads into a
 * ZWJ, asks for the colour emoji and lets the sequence ligate. Display only: the
 * reaction key sent back to Telegram stays as received.
 */
internal fun String.withEmojiPresentation(): String {
    if (codePointCount(0, length) == 1) {
        return if (endsWith(EMOJI_VARIATION_SELECTOR)) this else this + EMOJI_VARIATION_SELECTOR
    }
    if (ZERO_WIDTH_JOINER !in this) return this
    val out = StringBuilder(length + 2)
    for (i in indices) {
        val c = this[i]
        out.append(c)
        val next = getOrNull(i + 1)
        if (next == ZERO_WIDTH_JOINER && !c.isSurrogate() && c != EMOJI_VARIATION_SELECTOR[0]) {
            out.append(EMOJI_VARIATION_SELECTOR)
        }
    }
    return out.toString()
}

private const val EMOJI_VARIATION_SELECTOR = "\uFE0F"
private const val ZERO_WIDTH_JOINER = '\u200D'
