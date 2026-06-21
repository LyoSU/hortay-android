@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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

/**
 * The floating feed cluster: the arrivals ALERT (former `NewPostsPill`) CENTRED,
 * and the ambient "next unread" button (former `UnreadCounterPill`) in the
 * trailing corner — one full-width container owning both anchors so the two can
 * never collide (the audit found the old independent pills stacked in one
 * corner region).
 *
 * Layout contract (settled over several on-device iterations with the owner):
 *  1. A single state-swapping control replaced the unread "↓ N" with the
 *     arrivals stadium whenever new posts landed — the button the user is
 *     actively tapping kept changing identity under the finger. Rejected.
 *  2. A side-by-side row read fine but pulled the arrivals alert out of centre.
 *  3. A constant one-row lift kept the pill overlap-free but left it hovering
 *     too high when no unread button was on screen. Rejected.
 *  4. Final: arrivals stay CENTRED (their pre-merge home, where an alert reads
 *     as an alert) at the slot bottom; the unread button keeps the trailing
 *     corner and never moves; and ONLY while the button is visible the pill
 *     lifts one row above it (animated [UNREAD_ROW_CLEARANCE] spring) —
 *     overlap-free at any screen width or label length, no measuring needed,
 *     and flush with the navbar whenever the corner is empty.
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
    // Lift the centred pill above the unread button's row ONLY while the button
    // is actually on screen; with the corner empty the pill rests flush at the
    // slot bottom. Animated so a mid-session button toggle reads as the pill
    // gliding out of the way, not teleporting. The spatial spring OVERSHOOTS by
    // design — on the way back to 0 it briefly dips negative, and a negative
    // padding is an IllegalArgumentException (caused a field crash), so the
    // value is clamped at the consumer.
    val pillLift by animateDpAsState(
        targetValue = if (unreadCount > 0) UNREAD_ROW_CLEARANCE else 0.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "arrivals-lift",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = pendingCount > 0,
            enter = scaleIn(spatialSpec, initialScale = 0.85f) + fadeIn(effectsSpec),
            exit = scaleOut(spatialSpec, targetScale = 0.85f) + fadeOut(effectsSpec),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = pillLift.coerceAtLeast(0.dp)),
        ) {
            ArrivalsPill(
                channels = pendingChannels,
                count = pendingCount,
                arrowGlyph = arrowGlyph,
                onClick = onArrivalsClick,
            )
        }
        AnimatedVisibility(
            visible = unreadCount > 0,
            enter = scaleIn(spatialSpec, initialScale = 0.85f) + fadeIn(effectsSpec),
            exit = scaleOut(spatialSpec, targetScale = 0.85f) + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            UnreadButton(
                count = unreadCount,
                onClick = onUnreadClick,
            )
        }
    }
}

@Composable
private fun ArrivalsPill(
    channels: ImmutableList<ChannelBadge>,
    count: Int,
    arrowGlyph: String,
    onClick: () -> Unit,
) {
    val label = newPostsLabel(LocalContext.current.resources, count)
    Surface(
        onClick = onClick,
        // CircleShape on a wide Row collapses to a true stadium bounded to the
        // content width — same idiom the former NewPostsPill used.
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channels.isNotEmpty()) {
                AvatarStack(channels = channels)
                Spacer(Modifier.width(10.dp))
            }
            Symbol(name = arrowGlyph, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.tabularFigures(),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun UnreadButton(
    count: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Clean pressed-scale cue on a regular circle. The former Circle→Burst shape
    // morph (FabPressMorph) deformed the silhouette mid-press and was read as a
    // rendering defect on device — interactive surfaces keep regular geometry.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "feed-unread-press",
    )
    val label = newPostsLabel(LocalContext.current.resources, count)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .scale(pressScale)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Symbol(name = "arrow_downward", size = 20.dp, contentDescription = null)
            Spacer(Modifier.width(6.dp))
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

private val AVATAR_SIZE = 28.dp
private val AVATAR_OFFSET = 18.dp

/**
 * Vertical clearance the centred arrivals pill keeps above the trailing "↓ N"
 * button's row while that button is VISIBLE: the button is ~48 dp tall, plus a
 * 10 dp visual gap. Animated in/out with the button's presence (see `pillLift`
 * in [FeedFloatingControl]) so the pill rests flush at the slot bottom whenever
 * the corner is empty — and the two can never overlap at any screen width.
 */
private val UNREAD_ROW_CLEARANCE = 58.dp
