@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import dev.lyo.hortay.ui.theme.tabularFigures
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The floating feed cluster: the arrivals ALERT (former `NewPostsPill`) and the
 * ambient "next unread" button (former `UnreadCounterPill`) as the two halves of
 * one split button — one container owning both so the two can never collide.
 *
 * Layout contract (settled over several on-device iterations with the owner):
 *  1. A single state-swapping control replaced the unread "↓ N" with the
 *     arrivals stadium whenever new posts landed — the button the user is
 *     actively tapping kept changing identity under the finger. Rejected.
 *  2. A side-by-side row read fine but pulled the arrivals alert out of centre.
 *  3. A constant one-row lift kept the pill overlap-free but left it hovering
 *     too high when no unread button was on screen. Rejected.
 *  4. Arrivals CENTRED at the slot bottom, the unread button in the trailing
 *     corner, the pill lifting one row while the button showed. On device the
 *     wide bright pill plus the button stacked two controls over the text the
 *     user was reading. Replaced.
 *  5. Current: ONE compact split button in the trailing corner. The "↓ N"
 *     segment is the stable trailing half — it never changes meaning under the
 *     finger (the objection to #1); the arrivals segment (2 avatars + "↓ N")
 *     joins on its leading side while posts are pending. Two tap targets, one
 *     silhouette, one corner. Newest-on-top keeps the arrivals segment alone,
 *     centred under the header.
 *
 *  - [pendingCount] > 0 → arrivals stadium: avatar stack + "N нових постів" +
 *    direction arrow. Tap = [onArrivalsClick] (the proven arrivals-commit path:
 *    `acceptIds → awaitItemsCommitted → smartScrollTo`).
 *  - [unreadCount] > 0 → circular "↓ N". Tap = [onUnreadClick] (scroll to the
 *    live read→unread boundary). A plain circle with a pressed-scale cue — the
 *    earlier Circle→Burst press morph deformed the silhouette and the owner
 *    read it as a defect, not expressiveness; interactive surfaces stay
 *    regular shapes (circle/stadium), shape-morph stays loading-only.
 *
 * Both prior behaviours survive EXACTLY — the caller still computes
 * `scopedPendingNew` / `unreadRemaining` and supplies the two onClick bodies
 * verbatim; this composable only lays the two affordances out.
 *
 * a11y: each element carries its own merged `contentDescription`
 * (`mergeDescendants`), both phrased via [R.plurals.new_posts] — the shared
 * "things I haven't read" vocabulary the two former pills already used.
 */
@Composable
fun FeedFloatingControl(
    pendingCount: Int,
    pendingChannels: ImmutableList<ChannelBadge>,
    unreadCount: Int,
    arrowGlyph: String,
    onArrivalsClick: () -> Unit,
    onUnreadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val showArrivals = pendingCount > 0
    val showUnread = unreadCount > 0
    // Newest-on-top mounts the control under the header with an upward arrow; there it
    // stays centred. Everywhere else it is a compact split button in the trailing corner.
    val atTop = arrowGlyph == "arrow_upward"
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .align(if (atTop) Alignment.TopCenter else Alignment.BottomEnd)
                // animateContentSize clips to its bounds, which sliced the segments'
                // shadows flat at the edges. Pad the animated box so the shadow fits
                // inside the clip, and offset it back so the button keeps its spot.
                .offset(x = if (atTop) 0.dp else SHADOW_ROOM, y = if (atTop) -SHADOW_ROOM else SHADOW_ROOM)
                .animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
                .padding(SHADOW_ROOM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        ) {
            AnimatedVisibility(
                visible = showArrivals,
                enter = scaleIn(spatialSpec, initialScale = 0.85f) + fadeIn(effectsSpec),
                exit = scaleOut(spatialSpec, targetScale = 0.85f) + fadeOut(effectsSpec),
            ) {
                ArrivalsSegment(
                    channels = pendingChannels,
                    count = pendingCount,
                    arrowGlyph = arrowGlyph,
                    joinedToUnread = showUnread,
                    onClick = onArrivalsClick,
                )
            }
            AnimatedVisibility(
                visible = showUnread,
                enter = scaleIn(spatialSpec, initialScale = 0.85f) + fadeIn(effectsSpec),
                exit = scaleOut(spatialSpec, targetScale = 0.85f) + fadeOut(effectsSpec),
            ) {
                UnreadSegment(
                    count = unreadCount,
                    joinedToArrivals = showArrivals,
                    onClick = onUnreadClick,
                )
            }
        }
    }
}

/** Split-button halves: full stadium on the outer edge, a tight radius where they meet. */
private fun segmentShape(roundStart: Boolean, roundEnd: Boolean): Shape = RoundedCornerShape(
    topStart = if (roundStart) SEGMENT_HEIGHT / 2 else INNER_CORNER,
    bottomStart = if (roundStart) SEGMENT_HEIGHT / 2 else INNER_CORNER,
    topEnd = if (roundEnd) SEGMENT_HEIGHT / 2 else INNER_CORNER,
    bottomEnd = if (roundEnd) SEGMENT_HEIGHT / 2 else INNER_CORNER,
)

@Composable
private fun ArrivalsSegment(
    channels: ImmutableList<ChannelBadge>,
    count: Int,
    arrowGlyph: String,
    joinedToUnread: Boolean,
    onClick: () -> Unit,
) {
    val label = newPostsLabel(LocalContext.current.resources, count)
    Surface(
        onClick = onClick,
        shape = segmentShape(roundStart = true, roundEnd = !joinedToUnread),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        // Polite live region: this segment only exists while `pendingCount > 0`, so its
        // very appearance IS the "new posts arrived" announcement — TalkBack users get
        // the same signal sighted users get from it sliding into view.
        modifier = Modifier
            .height(SEGMENT_HEIGHT)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            modifier = Modifier.padding(start = if (channels.isNotEmpty()) 6.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channels.isNotEmpty()) {
                AvatarStack(channels = channels.take(MAX_AVATARS).toImmutableList())
                Spacer(Modifier.width(8.dp))
            }
            Symbol(name = arrowGlyph, size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                text = countText(count),
                style = MaterialTheme.typography.labelLarge.tabularFigures(),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun UnreadSegment(
    count: Int,
    joinedToArrivals: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Clean pressed-scale cue on a regular shape — the former Circle→Burst morph read as
    // a rendering defect on device; interactive surfaces keep regular geometry.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "feed-unread-press",
    )
    val label = newPostsLabel(LocalContext.current.resources, count)
    Surface(
        onClick = onClick,
        shape = segmentShape(roundStart = !joinedToArrivals, roundEnd = true),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 6.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .height(SEGMENT_HEIGHT)
            .scale(pressScale)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Symbol(name = "arrow_downward", size = 18.dp, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
                text = countText(count),
                style = MaterialTheme.typography.labelLarge.tabularFigures(),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AvatarStack(channels: ImmutableList<ChannelBadge>) {
    val borderColor = MaterialTheme.colorScheme.primary
    val avatarShape = HortayExpressive.Avatar.asComposeShape()
    Box(modifier = Modifier.height(AVATAR_SIZE)) {
        channels.forEachIndexed { idx, ch ->
            Box(
                modifier = Modifier
                    .padding(start = (idx * AVATAR_OFFSET.value).dp)
                    .size(AVATAR_SIZE)
                    .clip(avatarShape)
                    .border(2.dp, borderColor, avatarShape),
            ) {
                TdAvatar(
                    name = ch.title,
                    thumb = ch.thumb,
                    fileId = ch.fileId,
                    remoteUrl = ch.avatarUrl,
                    size = AVATAR_SIZE,
                    textStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Plural form for "new post(s)" / unread count. Defers to Android's CLDR-driven
 * [android.content.res.Resources.getQuantityString]. Counts above 99 clamp to a
 * static overflow label so transient state flickers can't surface alarming numbers.
 * Shared by both affordances (and the former two pills).
 */
private fun newPostsLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.new_posts_overflow)
    return res.getQuantityString(R.plurals.new_posts, n, n)
}

private fun countText(n: Int): String = if (n > 99) "99+" else n.toString()

private val AVATAR_SIZE = 32.dp
private val AVATAR_OFFSET = 20.dp
private const val MAX_AVATARS = 2
private val SEGMENT_HEIGHT = 44.dp
private val INNER_CORNER = 6.dp
private val SEGMENT_GAP = 2.dp
private val SHADOW_ROOM = 12.dp
