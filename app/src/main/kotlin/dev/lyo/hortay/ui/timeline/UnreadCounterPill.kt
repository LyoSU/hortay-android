package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Ambient "↓ N" jump-to-next-unread chip, bottom-end anchored. Mirrors
 * Telegram-Android's in-chat scroll-to-bottom FAB semantics, repurposed for
 * the [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst] queue:
 *
 *   - Count is the LIVE remaining-unread total in the visible feed
 *     (driven by [LocalReadCursors], not the frozen sort snapshot), so it
 *     ticks down as viewport-dwell acks land.
 *   - Tap → scroll the LazyColumn to the first still-unread post per the
 *     live cursor (skipping past posts the snapshot still has in the
 *     unread block but the live cursor has already acked).
 *
 * Visual hierarchy contrast with [NewPostsPill]:
 *   - NewPostsPill = ALERT (primary container, BottomCenter, plural label,
 *     opt-in to NEW arrivals).
 *   - UnreadCounterPill = AMBIENT (secondaryContainer, BottomEnd, compact
 *     "↓ N" form, always present while unread > 0). Different role = different
 *     anchor = no visual conflict when both fire at once.
 *
 * Spring entrance keyed on `count > 0` (not on the count itself), same idiom
 * NewPostsPill uses — chip pops once on enter, then count updates without
 * re-springing on every dwell-ack.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    Surface(
        onClick = onClick,
        modifier = modifier.scale(scale),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                name = "arrow_downward",
                size = 18.dp,
                contentDescription = label,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = countText(count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Plural-aware semantic label for screen readers / talkback ("12 непрочитаних
 * лишилось"). The visible chip only shows the bare number — short for
 * glanceability, matching Telegram's pattern — but `contentDescription`
 * carries the full localised phrase so a11y users get the same meaning.
 */
private fun unreadRemainingLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.unread_remaining_overflow)
    return res.getQuantityString(R.plurals.unread_remaining, n, n)
}

private fun countText(n: Int): String = if (n > 99) "99+" else n.toString()
