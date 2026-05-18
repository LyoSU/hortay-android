package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Ambient jump-to-next-unread chip, bottom-end anchored, in **Telegram-Android's
 * in-chat scroll-to-bottom FAB silhouette** — a 56 dp circular Surface holding
 * the `↓` glyph centred, with a corner-anchored Badge carrying the live count.
 * Bound to [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst]:
 *
 *   - Count is the LIVE remaining-unread total in the visible feed
 *     (driven by [LocalReadCursors], not the frozen sort snapshot), so it
 *     ticks down as viewport-dwell acks land.
 *   - Tap → scroll the LazyColumn to the first still-unread post per the
 *     live cursor (skipping past posts the snapshot still has in the
 *     unread block but the live cursor has already acked).
 *
 * Silhouette-based hierarchy contrast with [NewPostsPill]:
 *   - [NewPostsPill] = ALERT — horizontal stadium with avatar stack +
 *     plural label, BottomCenter, `primary` container. Opt-in to NEW arrivals
 *     sitting *outside* the feed (`scopedPendingNew`).
 *   - [UnreadCounterPill] = AMBIENT — round FAB + corner badge, BottomEnd,
 *     `secondaryContainer`. Reports unread *inside* the feed; always present
 *     while count > 0.
 *
 * The two share an `arrow_downward` glyph but **the silhouettes are
 * Gestalt-disjoint** (stadium vs circle), so when both fire at once on
 * different anchors the user reads them as distinct affordances instead of
 * "two ↓ buttons that do similar things". A11y echoes this: the FAB has a
 * single merged `contentDescription`, so TalkBack announces one button per
 * pill, not a count and a label as separate nodes.
 *
 * Spring entrance keyed on `count > 0` (not on the count itself), same idiom
 * [NewPostsPill] uses — chip pops once on enter, then count updates without
 * re-springing on every dwell-ack.
 */
@Composable
fun UnreadCounterPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(count > 0) {
        if (count > 0) hasAppeared = true
    }
    val scale by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "unread-counter-scale",
    )
    val ctx = LocalContext.current
    val label = unreadRemainingLabel(ctx.resources, count)
    BadgedBox(
        // mergeDescendants collapses the Surface + Symbol + Badge into a single
        // accessibility node so TalkBack reads "12 непрочитаних лишилось" once
        // instead of stuttering through the badge digits and the icon label.
        modifier = modifier
            .scale(scale)
            .semantics(mergeDescendants = true) { contentDescription = label },
        badge = {
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = countText(count),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.size(FAB_SIZE),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(FAB_SIZE),
            ) {
                Symbol(
                    name = "arrow_downward",
                    size = 24.dp,
                    // Decorative — the merged contentDescription above carries
                    // the human label; surfacing the icon as its own a11y node
                    // would duplicate the announcement.
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * Plural-aware semantic label for screen readers / talkback ("12 непрочитаних
 * лишилось"). The visible badge only shows the bare number — short for
 * glanceability, matching Telegram's pattern — but the FAB's merged
 * `contentDescription` carries the full localised phrase so a11y users get
 * the same meaning.
 */
private fun unreadRemainingLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.unread_remaining_overflow)
    return res.getQuantityString(R.plurals.unread_remaining, n, n)
}

private fun countText(n: Int): String = if (n > 99) "99+" else n.toString()

/**
 * Standard M3 FAB diameter. Big enough for a comfortable thumb target on the
 * BottomEnd corner without crowding the centred [NewPostsPill] when both are
 * visible — a 56 dp circle at the right edge + a stadium chip at centre leaves
 * ~24 dp of breathing room on a 360 dp-width phone.
 */
private val FAB_SIZE = 56.dp
