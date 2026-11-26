@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.theme.HortaySpacing
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * One-time first-run question: which direction should the feed read? The default
 * ([FeedOrder.OldestUnreadFirst], "like a chat") is unusual and used to go unexplained.
 *
 * [current] preselects the stored order. [onConfirm] receives the chosen order; [onDismiss]
 * fires for any dismissal without a choice. The host marks the one-shot flag in both cases
 * so the sheet never appears twice.
 *
 * Layout: the two option cards carry only a label and a short, parallel subtitle so they stay
 * the same height; the fuller explanation of the selected option sits in one supporting line
 * under them. Everything outside the cards is start-aligned, like the app's other sheets.
 */
@Composable
fun FeedOrderOnboardingSheet(
    current: FeedOrder,
    onConfirm: (FeedOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<FeedOrder?>(null) }
    val selected = picked ?: current
    val haptics = LocalHapticFeedback.current
    val select: (FeedOrder) -> Unit = { order ->
        if (order != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        picked = order
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberExpandedSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HortaySpacing.xl)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_feed_order_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(HortaySpacing.md))
            FeedOrderOptions(selected = selected, onSelect = select)
            Spacer(Modifier.height(HortaySpacing.md))
            FeedOrderHint(selected = selected)
            Spacer(Modifier.height(ACTIONS_GAP))
            Button(
                onClick = { onConfirm(selected) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BUTTON_MIN_HEIGHT),
            ) {
                Text(stringResource(R.string.onboarding_feed_order_confirm))
            }
            Spacer(Modifier.height(HortaySpacing.md))
            Text(
                text = stringResource(
                    R.string.onboarding_feed_order_footer,
                    stringResource(R.string.nav_profile),
                    stringResource(R.string.settings_section_feed),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(HortaySpacing.xl))
        }
    }
}

/**
 * The two options side by side as equal-height cards, or stacked as full-width rows once the
 * font scale or the available width would squeeze the card labels.
 */
@Composable
private fun FeedOrderOptions(
    selected: FeedOrder,
    onSelect: (FeedOrder) -> Unit,
) {
    val largeFont = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (largeFont || maxWidth < MIN_SIDE_BY_SIDE_WIDTH) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(HortaySpacing.md),
            ) {
                FEED_ORDER_OPTIONS.forEach { order ->
                    FeedOrderOptionCard(
                        order = order,
                        selected = order == selected,
                        stacked = true,
                        onSelect = { onSelect(order) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(HortaySpacing.md),
            ) {
                FEED_ORDER_OPTIONS.forEach { order ->
                    FeedOrderOptionCard(
                        order = order,
                        selected = order == selected,
                        stacked = false,
                        onSelect = { onSelect(order) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * One option. Selected: the same secondary-container fill the Settings feed-order control
 * uses, a 2 dp primary outline and a check badge in the top end corner. Unselected: the sheet's
 * own surface with a 1 dp outlineVariant hairline.
 */
@Composable
private fun FeedOrderOptionCard(
    order: FeedOrder,
    selected: Boolean,
    stacked: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val colorSpec: AnimationSpec<Color> =
        if (rememberReducedMotion()) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
    val container by animateColorAsState(
        targetValue = if (selected) cs.secondaryContainer else BottomSheetDefaults.ContainerColor,
        animationSpec = colorSpec,
        label = "feedOrderCardContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) cs.onSecondaryContainer else cs.onSurface,
        animationSpec = colorSpec,
        label = "feedOrderCardContent",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) cs.primary else cs.outlineVariant,
        animationSpec = colorSpec,
        label = "feedOrderCardOutline",
    )
    val shape = RoundedCornerShape(CARD_CORNER)
    val title = stringResource(
        if (order == FeedOrder.Newest) R.string.settings_feed_order_newest_title
        else R.string.settings_feed_order_oldest_title,
    )
    val description = stringResource(
        if (order == FeedOrder.Newest) R.string.onboarding_feed_order_newest_desc
        else R.string.onboarding_feed_order_oldest_desc,
    )
    val newestOnTop = order == FeedOrder.Newest
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        border = BorderStroke(if (selected) 2.dp else 1.dp, outline),
        modifier = modifier
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        if (stacked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(HortaySpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedOrderSchematic(newestOnTop = newestOnTop)
                Spacer(Modifier.width(HortaySpacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(HortaySpacing.xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = HortayAlpha.QUIET),
                    )
                }
                Spacer(Modifier.width(HortaySpacing.sm))
                SelectedBadge(visible = selected)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(HortaySpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FeedOrderSchematic(newestOnTop = newestOnTop)
                    Spacer(Modifier.height(HortaySpacing.md))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(HortaySpacing.xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = HortayAlpha.QUIET),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SelectedBadge(
                    visible = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(HortaySpacing.sm),
                )
            }
        }
    }
}

/**
 * Supporting line under the cards with the fuller explanation of the selected option. Both
 * texts are laid out on top of each other and crossfade, so the line keeps the height of the
 * longer one and the button below never jumps when the selection changes.
 */
@Composable
private fun FeedOrderHint(selected: FeedOrder) {
    val alphaSpec: AnimationSpec<Float> =
        if (rememberReducedMotion()) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
    Box(Modifier.fillMaxWidth()) {
        FEED_ORDER_OPTIONS.forEach { order ->
            key(order) {
                val shown = order == selected
                val alpha by animateFloatAsState(
                    targetValue = if (shown) 1f else 0f,
                    animationSpec = alphaSpec,
                    label = "feedOrderHintAlpha",
                )
                Text(
                    text = stringResource(
                        if (order == FeedOrder.Newest) R.string.onboarding_feed_order_newest_hint
                        else R.string.onboarding_feed_order_oldest_hint,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { this.alpha = alpha }
                        .then(if (shown) Modifier else Modifier.clearAndSetSemantics {}),
                )
            }
        }
    }
}

/**
 * Small filled check in the primary colour. Always occupies its slot so the card content never
 * shifts; only its alpha and scale animate. Decorative: the card's own `selected` semantics
 * already carry the state.
 */
@Composable
private fun SelectedBadge(visible: Boolean, modifier: Modifier = Modifier) {
    val progressSpec: AnimationSpec<Float> =
        if (rememberReducedMotion()) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = progressSpec,
        label = "feedOrderBadge",
    )
    val fill = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onPrimary
    Canvas(
        modifier
            .size(BADGE_SIZE)
            .graphicsLayer {
                alpha = progress
                val scale = BADGE_MIN_SCALE + (1f - BADGE_MIN_SCALE) * progress
                scaleX = scale
                scaleY = scale
            },
    ) {
        drawCircle(color = fill)
        val w = size.width
        val check = Path().apply {
            moveTo(w * 0.29f, w * 0.52f)
            lineTo(w * 0.44f, w * 0.66f)
            lineTo(w * 0.72f, w * 0.37f)
        }
        drawPath(
            path = check,
            color = mark,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Decorative schematic on one fixed grid for both options: three start-aligned bars of fixed
 * widths, the newest one (top or bottom) in the primary colour, and an arrow in the same slot
 * pointing to where new posts arrive. The bar column mirrors in RTL with the Row; the arrow is
 * vertical and needs no mirroring.
 */
@Composable
private fun FeedOrderSchematic(newestOnTop: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = LocalContentColor.current.copy(alpha = HortayAlpha.HAIRLINE)
    val highlighted = if (newestOnTop) 0 else SCHEMATIC_BAR_WIDTHS.lastIndex
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(SCHEMATIC_BAR_GAP)) {
            SCHEMATIC_BAR_WIDTHS.forEachIndexed { index, width ->
                SchematicBar(width = width, color = if (index == highlighted) accent else quiet)
            }
        }
        Spacer(Modifier.width(HortaySpacing.sm))
        Symbol(
            name = if (newestOnTop) "arrow_upward" else "arrow_downward",
            contentDescription = null,
            tint = accent,
            size = SCHEMATIC_ARROW_SIZE,
        )
    }
}

@Composable
private fun SchematicBar(width: Dp, color: Color) {
    Box(
        Modifier
            .size(width = width, height = SCHEMATIC_BAR_HEIGHT)
            .clip(RoundedCornerShape(SCHEMATIC_BAR_HEIGHT / 2))
            .background(color),
    )
}

private val FEED_ORDER_OPTIONS = listOf(FeedOrder.Newest, FeedOrder.OldestUnreadFirst)

/** Font scale from which the side-by-side cards would squeeze their labels. */
private const val STACK_FONT_SCALE = 1.5f

/** Below this content width two cards no longer fit their labels on one line each. */
private val MIN_SIDE_BY_SIDE_WIDTH = 280.dp

private val CARD_CORNER = 20.dp
private val BUTTON_MIN_HEIGHT = 48.dp

/** Gap between the content and the action button, as in the archive onboarding sheet. */
private val ACTIONS_GAP = 20.dp

private val BADGE_SIZE = 20.dp
private const val BADGE_MIN_SCALE = 0.6f

private val SCHEMATIC_BAR_WIDTHS = listOf(44.dp, 32.dp, 40.dp)
private val SCHEMATIC_BAR_HEIGHT = 8.dp
private val SCHEMATIC_BAR_GAP = 8.dp
private val SCHEMATIC_ARROW_SIZE = 24.dp
