package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.MorphShape

/**
 * Ambient jump-to-next-unread chip in `OldestUnreadFirst` mode, bottom-end
 * anchored, full M3 Expressive realisation: an [FloatingActionButton] whose
 * resting [HortayExpressive.FabRest] (circle) morphs toward
 * [HortayExpressive.FabPressed] (burst) on press via the pre-built
 * [HortayExpressive.FabPressMorph]. A [BadgedBox] anchors the live count in
 * the top-right corner.
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
 *   - [UnreadCounterPill] = AMBIENT — round FAB silhouette in rest, morphing
 *     toward a soft burst on press, BottomEnd, `secondaryContainer`. Reports
 *     unread *inside* the feed; always present while count > 0.
 *
 * The two share an `arrow_downward` glyph but the silhouettes sit in
 * Gestalt-disjoint clusters (stadium vs FAB), so they read as distinct
 * affordances even when both fire at once. A11y echoes this: the FAB has a
 * single merged `contentDescription`, so TalkBack announces one button per
 * pill, not a count and a label as separate nodes.
 *
 * The press morph is deliberately capped at `PRESS_MORPH_AMPLITUDE` — a
 * subtle hint of the Burst polygon, not a full transformation. At full
 * progress the Badge's corner anchor would land inside a Burst "petal" tip
 * and read as misaligned; the capped amplitude keeps the badge corner
 * stable while still surfacing the Expressive cue. Same pattern M3
 * Expressive's reference FAB samples use for the press state.
 *
 * Spring entrance keyed on `count > 0` (not on the count itself), same idiom
 * [NewPostsPill] uses — chip pops once on enter, then count updates without
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
    val enterScale by animateFloatAsState(
        targetValue = if (hasAppeared) 1f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "unread-counter-enter",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Animate on the FAST spatial channel — press is a tactile state change,
    // it should resolve quickly. Same idiom Shape.kt's
    // `rememberPressedSelectedCornerRadius` uses for chip press squish.
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed) PRESS_MORPH_AMPLITUDE else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "unread-counter-press",
    )
    // `MorphShape` is `@Immutable` but its `progress` is part of equality, so a
    // fresh instance per frame is correct — Compose recomposes the FAB only
    // when `pressProgress` actually moves.
    val shape = remember(pressProgress) {
        MorphShape(HortayExpressive.FabPressMorph, pressProgress)
    }
    val ctx = LocalContext.current
    val label = unreadRemainingLabel(ctx.resources, count)
    BadgedBox(
        // mergeDescendants collapses the FAB + Symbol + Badge into a single
        // accessibility node so TalkBack reads "12 непрочитаних лишилось" once
        // instead of stuttering through the badge digits and the icon label.
        modifier = modifier
            .scale(enterScale)
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
        FloatingActionButton(
            onClick = onClick,
            shape = shape,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            // M3 FAB default elevation tokens — resting 6 dp, pressed 12 dp,
            // focused/hovered 8 dp. Calling the explicit defaults keeps us in
            // sync with the Material elevation spec if tokens shift in a
            // future BOM bump.
            elevation = FloatingActionButtonDefaults.elevation(),
            interactionSource = interactionSource,
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
 * Cap on the Circle→Burst morph progress at press. Going to 1.0 turns the
 * resting circle into a full sunburst; the Badge corner anchor would then
 * land on a Burst petal tip and visibly drift away from the FAB's bounding
 * box. 0.4 is the M3 Expressive reference value — enough to telegraph the
 * personality cue, not enough to break the badge geometry.
 */
private const val PRESS_MORPH_AMPLITUDE = 0.4f
