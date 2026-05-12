package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape

/**
 * Floating "new posts" pill — Twitter-style alert chip that surfaces unrevealed feed
 * updates. M3 Expressive flavour:
 *
 *  - Container shape: `MaterialShapes.Pill` rather than [CircleShape]. The Pill polygon
 *    has subtly flattened sides at the cap → reads as "tablet" and pairs with the chip
 *    affordance more cleanly than a perfect ellipse.
 *  - Avatar masks: `Cookie6Sided` instead of circle. Inside a primary-coloured pill the
 *    polygonal chip masks read as "stickers", giving the chip a hand-assembled feel that
 *    a row of perfect circles can't match. Six sides was the sweet spot (4 too coarse,
 *    9 too busy at 28 dp).
 *  - Entrance choreography: a single spring-driven `scale(0.85 → 1.0)` on first
 *    appearance. Tied to `pendingCount > 0` rather than a recomposition keyed off the
 *    count itself, so the chip pops once when the count enters non-zero, not every time
 *    the count ticks up. Subsequent count changes use the existing AnimatedContent label
 *    swap upstream — separating "pill enters" from "pill updates" keeps the motion
 *    legible: only one expressive cue per state change.
 */
@Composable
fun NewPostsPill(
    channels: List<ChannelBadge>,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Direction-of-action glyph next to the count. `arrow_upward` (default)
     * fits Newest mode's top-anchored placement — "tap to bring me to fresh
     * content above". `arrow_downward` mirrors that for OldestUnreadFirst's
     * bottom-anchored pill — "tap to bring me to fresh content below".
     */
    arrowGlyph: String = "arrow_upward",
) {
    var hasAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCount > 0) {
        if (pendingCount > 0) hasAppeared = true
    }
    val scale by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "pill-scale",
    )
    // CircleShape on Surface is Compose's idiomatic stadium clip — collapses to
    // semicircular ends on a wide Row, identical visually to MaterialShapes.Pill
    // but always bounded to the actual content width so trailing characters
    // don't get sliced. The polygon Pill character is preserved on smaller
    // surfaces (counter pill in the media viewer); for the new-posts chip the
    // expressive cue lives in the avatar Cookie6 mask + the spring-driven scale
    // entrance, not the chip silhouette.
    Surface(
        onClick = onClick,
        modifier = modifier.scale(scale),
        shape = androidx.compose.foundation.shape.CircleShape,
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
            val ctx = LocalContext.current
            Text(
                text = newPostsLabel(ctx.resources, pendingCount),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AvatarStack(channels: List<ChannelBadge>) {
    val borderColor = MaterialTheme.colorScheme.primary
    // Cookie6Sided polygon clip on the avatar image — the image is the "content",
    // we WANT it sliced to the polygon silhouette (that's the decorative effect).
    // Convex enough at 28 dp that no part of a centred avatar falls outside the
    // inscribed circle.
    val avatarShape = HortayExpressive.Avatar.asComposeShape()
    Box(modifier = Modifier.height(AVATAR_SIZE)) {
        // Render right-to-left so the first channel sits on top of the stack.
        channels.forEachIndexed { idx, ch ->
            Box(
                modifier = Modifier
                    .padding(start = (idx * AVATAR_OFFSET.value).dp)
                    .size(AVATAR_SIZE)
                    .zIndex((channels.size - idx).toFloat())
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
 * Plural form for "new post(s)". Defers to Android's CLDR-driven [android.content.res.Resources.getQuantityString]
 * — Ukrainian gets one/few/many forms, English gets one/other, both via the same plurals
 * resource. Counts above 99 are clamped to a static overflow label so transient state
 * flickers can't surface alarming numbers.
 */
private fun newPostsLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.new_posts_overflow)
    return res.getQuantityString(R.plurals.new_posts, n, n)
}

private val AVATAR_SIZE = 28.dp
private val AVATAR_OFFSET = 18.dp
