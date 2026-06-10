@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.lyo.hortay.ui.theme.MorphShape
import dev.lyo.hortay.ui.theme.asComposeShape
import dev.lyo.hortay.ui.theme.tabularFigures

/**
 * The single bottom-anchored floating feed control. Merges the former
 * `NewPostsPill` (arrivals ALERT) and `UnreadCounterPill` (ambient "next unread"
 * FAB) into one morphing surface so the two affordances can never visually
 * collide near the navbar (the audit found them stacked in one corner region).
 *
 * Priority rule (driven entirely by the caller, who owns the contract state):
 *  - [pendingCount] > 0 → EXPANDED stadium: avatar stack + "N нових постів" +
 *    direction arrow. Tap = [onExpandedClick] (the proven arrivals-commit path:
 *    `acceptIds → awaitItemsCommitted → smartScrollTo`).
 *  - else if [unreadCount] > 0 → COLLAPSED circular "↓ N". Tap = [onCollapsedClick]
 *    (scroll to the live read→unread boundary).
 *
 * Both prior behaviours survive EXACTLY — only the chrome merges. The caller
 * still computes `scopedPendingNew` / `unreadRemaining` and supplies the two
 * onClick bodies verbatim; this composable only decides which silhouette is on
 * screen and hands the tap to the matching callback.
 *
 * Morph: the container interpolates corner geometry between the two states. The
 * collapsed state additionally rides the [HortayExpressive.FabPressMorph]
 * Circle→Burst press cue (capped at [PRESS_MORPH_AMPLITUDE]) the old FAB used.
 * State swap goes through [AnimatedContent] (fade) so the avatar stack + label
 * don't pop. Entrance scale is keyed on "is anything showing at all", so the
 * control pops once on first appearance, not on every count tick.
 *
 * a11y: ONE merged `contentDescription` per state via `mergeDescendants`, reusing
 * [R.plurals.new_posts] for both (the user mental model — "things I haven't read"
 * — is the same; silhouette + arrow disambiguate visually).
 */
@Composable
fun FeedFloatingControl(
    pendingCount: Int,
    pendingChannels: List<ChannelBadge>,
    unreadCount: Int,
    arrowGlyph: String,
    onExpandedClick: () -> Unit,
    onCollapsedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = pendingCount > 0
    val showing = expanded || unreadCount > 0

    var hasAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(showing) {
        if (showing) hasAppeared = true
    }
    val enterScale by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "feed-control-enter",
    )

    val ctx = LocalContext.current
    // Both states announce via the same plurals vocabulary — expanded uses the
    // pending arrivals count, collapsed uses the live unread-remaining count.
    val label = if (expanded) newPostsLabel(ctx.resources, pendingCount)
    else newPostsLabel(ctx.resources, unreadCount)

    // Hoisted out of the transitionSpec lambda: that scope is NOT @Composable, so
    // MaterialTheme.motionScheme can't be read inside it.
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
        },
        modifier = modifier
            .scale(enterScale)
            .semantics(mergeDescendants = true) { contentDescription = label },
        label = "feed-control-morph",
    ) { isExpanded ->
        if (isExpanded) {
            ExpandedArrivals(
                channels = pendingChannels,
                count = pendingCount,
                arrowGlyph = arrowGlyph,
                onClick = onExpandedClick,
            )
        } else {
            CollapsedUnread(
                count = unreadCount,
                onClick = onCollapsedClick,
            )
        }
    }
}

@Composable
private fun ExpandedArrivals(
    channels: List<ChannelBadge>,
    count: Int,
    arrowGlyph: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        // CircleShape on a wide Row collapses to a true stadium bounded to the
        // content width — same idiom the former NewPostsPill used.
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
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
                text = newPostsLabel(LocalContext.current.resources, count),
                style = MaterialTheme.typography.labelLarge.tabularFigures(),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CollapsedUnread(
    count: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Circle→Burst press cue, capped so the count stays legibly centred — the
    // exact behaviour the former UnreadCounterPill carried.
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed) PRESS_MORPH_AMPLITUDE else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "feed-control-press",
    )
    val shape = remember(pressProgress) {
        MorphShape(HortayExpressive.FabPressMorph, pressProgress)
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        interactionSource = interactionSource,
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
private fun AvatarStack(channels: List<ChannelBadge>) {
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
 * Shared by both control states (and the former two pills).
 */
private fun newPostsLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.new_posts_overflow)
    return res.getQuantityString(R.plurals.new_posts, n, n)
}

private fun countText(n: Int): String = if (n > 99) "99+" else n.toString()

/**
 * Cap on the Circle→Burst morph progress at press — the M3 Expressive reference
 * value. Going to 1.0 turns the resting circle into a full sunburst and the count
 * drifts off the visual centre.
 */
private const val PRESS_MORPH_AMPLITUDE = 0.4f

private val AVATAR_SIZE = 28.dp
private val AVATAR_OFFSET = 18.dp
