package dev.lyo.hortay.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where an [AnchoredContextMenu] attaches: the layout node of the thing that was
 * long-pressed. A plain holder, not snapshot state — [contextMenuAnchor] rewrites it on
 * every placement (each scroll frame), and nothing may recompose because of that; the menu
 * reads it once, when it opens.
 */
@Stable
class ContextMenuAnchor {
    internal var coordinates: LayoutCoordinates? = null

    /** Where the last press landed, in the anchor's own space. */
    internal var pressPosition: Offset? = null

    /** The last press in [target]'s space, or null when there was none (keyboard, a11y). */
    internal fun pressIn(target: LayoutCoordinates): Offset? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        val press = pressPosition ?: return null
        if (!target.isAttached) return null
        return target.screenToLocal(coords.localToScreen(press))
    }

    /**
     * The part of the anchor that is actually on screen (clipped by its scrolling
     * ancestors), in [target]'s space — the menu's dialog is a different window, so the
     * rect travels through screen coordinates.
     */
    internal fun visibleBoundsIn(target: LayoutCoordinates): Rect? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        if (!target.isAttached) return null
        val inWindow = coords.boundsInWindow()
        if (inWindow.width < 1f || inWindow.height < 1f) return null
        val windowToScreen = coords.localToScreen(Offset.Zero) - coords.localToWindow(Offset.Zero)
        val topLeft = target.screenToLocal(inWindow.topLeft + windowToScreen)
        return Rect(topLeft, inWindow.size)
    }
}

/** Marks this node as the thing an [AnchoredContextMenu] opened with [anchor] points at. */
fun Modifier.contextMenuAnchor(anchor: ContextMenuAnchor): Modifier = this
    .onPlaced { anchor.coordinates = it }
    // Observes only (Initial pass, nothing consumed): remembers where the finger went down,
    // so a menu that cannot sit beside its anchor opens at the finger instead.
    .pointerInput(anchor) {
        awaitEachGesture {
            anchor.pressPosition = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
        }
    }

/** Receiver of the menu's content: the only way out of the menu from inside it. */
@Stable
interface ContextMenuScope {
    /** Runs [action] right away, then animates the menu out and reports the dismissal. */
    fun close(action: () -> Unit = {})
}

/**
 * A context menu that stays with the thing it is about: the screen dims except for a
 * window over [anchor] (which keeps drawing live underneath — playing video included),
 * [header] floats just above it and the [actions] card just below. Replaces the half-screen
 * bottom sheet the long-press used to open, which covered the very post it acted on.
 *
 * Placement, in the dialog's safe-drawing area: header above the anchor, actions below it,
 * under the finger horizontally; with room on one side only, both stack on that side.
 * When neither works the menu opens at the finger like a platform context menu — header
 * above the press, actions below — and the whole screen dims evenly, anchor included: a
 * lit window the menu itself covers reads as a highlight on something you cannot see. Both parts always fit — the actions card
 * gets whatever height the header leaves and scrolls past that.
 *
 * No backdrop blur on purpose: a cross-window blur would blur the anchor too, and keeping
 * it sharp would mean re-rendering or screenshotting the post, which loses live media.
 *
 * Hosted in a [Dialog] for free back handling and accessibility focus; the window recipe
 * (transparent, no system dim, no window animation) is the fullscreen viewer's.
 */
@Composable
fun AnchoredContextMenu(
    anchor: ContextMenuAnchor,
    onDismiss: () -> Unit,
    header: @Composable ContextMenuScope.() -> Unit = {},
    actions: @Composable ContextMenuScope.() -> Unit = {},
) {
    val reducedMotion = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val enterSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val menuScope = remember {
        object : ContextMenuScope {
            private var closing = false
            override fun close(action: () -> Unit) {
                if (closing) return
                closing = true
                action()
                scope.launch {
                    if (reducedMotion) progress.snapTo(0f) else progress.animateTo(0f, exitSpec)
                }.invokeOnCompletion { currentOnDismiss() }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (reducedMotion) progress.snapTo(1f) else progress.animateTo(1f, enterSpec)
    }

    Dialog(
        onDismissRequest = { menuScope.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setWindowAnimations(0)
        }
        var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // Resolved once: the list underneath cannot scroll while the dialog is up.
        val hole = remember(root) { root?.let { anchor.visibleBoundsIn(it) } }
        val press = remember(root) { root?.let { anchor.pressIn(it) } }
        // Written by the menu's measure pass, read by the scrim's draw pass.
        var anchorLit by remember { mutableStateOf(false) }
        val scrimColor = MaterialTheme.colorScheme.scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { if (root == null) root = it }
                .pointerInput(Unit) { detectTapGestures { menuScope.close() } }
                .drawBehind {
                    val alpha = progress.value.coerceIn(0f, 1f) * HortayAlpha.SCRIM
                    if (hole == null || !anchorLit) {
                        drawRect(scrimColor, alpha = alpha)
                    } else {
                        val radius = HOLE_CORNER.toPx()
                        val window = Path().apply { addRoundRect(RoundRect(hole, CornerRadius(radius, radius))) }
                        clipPath(window, clipOp = ClipOp.Difference) { drawRect(scrimColor, alpha = alpha) }
                    }
                },
        ) {
            if (root != null) {
                MenuLayout(
                    hole = hole,
                    press = press,
                    onPlacement = { anchorLit = it },
                    progress = { progress.value },
                    header = { menuScope.header() },
                    actions = { menuScope.actions() },
                )
            }
        }
    }
}

@Composable
private fun MenuLayout(
    hole: Rect?,
    press: Offset?,
    onPlacement: (anchorLit: Boolean) -> Unit,
    progress: () -> Float,
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val insets = WindowInsets.safeDrawing
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(Modifier.menuEntrance(progress, TransformOrigin(0.5f, 1f))) { header() }
            Box(Modifier.menuEntrance(progress, TransformOrigin(0.5f, 0f))) { actions() }
        },
    ) { measurables, constraints ->
        val margin = SCREEN_MARGIN.roundToPx()
        val gap = ANCHOR_GAP.roundToPx()
        val left = insets.getLeft(this, layoutDirection) + margin
        val right = constraints.maxWidth - insets.getRight(this, layoutDirection) - margin
        val top = insets.getTop(this) + margin
        val bottom = constraints.maxHeight - insets.getBottom(this) - margin
        val availWidth = (right - left).coerceAtLeast(0)
        val availHeight = (bottom - top).coerceAtLeast(0)

        val headerPlaceable = measurables[0].measure(Constraints(maxWidth = availWidth, maxHeight = availHeight))
        // An empty header (no reactions to offer) takes no room and no gap.
        val headerSpace = if (headerPlaceable.height > 0) headerPlaceable.height + gap else 0
        val actionsPlaceable = measurables[1].measure(
            Constraints(maxWidth = availWidth, maxHeight = (availHeight - headerSpace).coerceAtLeast(0)),
        )

        val actionsHeight = actionsPlaceable.height
        val pressY = press?.y?.roundToInt() ?: ((top + bottom) / 2)
        val holeTop = hole?.top?.roundToInt() ?: 0
        val holeBottom = hole?.bottom?.roundToInt() ?: 0
        val roomAbove = holeTop - top
        val roomBelow = bottom - holeBottom - gap
        // Where the header's top and the actions' top go without covering the anchor:
        // around it, else stacked on whichever side has room; null when neither does.
        val besideY: Pair<Int, Int>? = when {
            hole == null -> null
            roomAbove >= headerSpace && roomBelow >= actionsHeight ->
                (holeTop - headerSpace) to (holeBottom + gap)
            roomBelow >= headerSpace + actionsHeight ->
                (holeBottom + gap) to (holeBottom + gap + headerSpace)
            roomAbove >= headerSpace + actionsHeight + gap ->
                (holeTop - gap - actionsHeight - headerSpace) to (holeTop - gap - actionsHeight)
            else -> null
        }
        // The anchor is lit only when the menu sits clear of it. Lighting a post the
        // menu then covers made it stand out and hid it at the same time.
        onPlacement(besideY != null)
        val clearance = FINGER_CLEARANCE.roundToPx()
        val actionsY = (besideY?.second ?: (pressY + clearance))
            .coerceAtMost(bottom - actionsHeight)
            .coerceAtLeast(top + headerSpace)
        val headerY = (besideY?.first ?: (pressY - clearance - headerSpace))
            .coerceAtMost(actionsY - headerSpace)
            .coerceAtLeast(top)
        val startX = if (layoutDirection == LayoutDirection.Rtl) right - actionsPlaceable.width else left
        val actionsX = (press?.x?.roundToInt()?.minus(actionsPlaceable.width / 2) ?: startX)
            .coerceAtMost(right - actionsPlaceable.width)
            .coerceAtLeast(left)

        layout(constraints.maxWidth, constraints.maxHeight) {
            headerPlaceable.place(left + (availWidth - headerPlaceable.width) / 2, headerY)
            actionsPlaceable.place(actionsX, actionsY)
        }
    }
}

/** Fade + a short scale out of the anchor's side; the spring's overshoot only reaches the scale. */
private fun Modifier.menuEntrance(progress: () -> Float, origin: TransformOrigin): Modifier = graphicsLayer {
    val value = progress()
    val scale = ENTRANCE_FROM_SCALE + (1f - ENTRANCE_FROM_SCALE) * value
    alpha = value.coerceIn(0f, 1f)
    scaleX = scale
    scaleY = scale
    transformOrigin = origin
}

/** The floating container of a context menu's [AnchoredContextMenu] header (the reaction capsule). */
@Composable
fun ContextMenuCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CAPSULE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = FLOAT_ELEVATION,
        content = content,
    )
}

/** The floating card of a context menu's actions; lay out [ContextMenuItem]s inside. */
@Composable
fun ContextMenuCard(content: @Composable () -> Unit) {
    Surface(
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = FLOAT_ELEVATION,
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = CARD_MIN_WIDTH, max = CARD_MAX_WIDTH)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

/** One compact action row; [destructive] tints it with the error colour. */
@Composable
fun ContextMenuItem(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val labelColor =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColor =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = symbol, tint = iconColor, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
    }
}

/** Hairline between groups of [ContextMenuItem]s. */
@Composable
fun ContextMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HortayAlpha.HAIRLINE),
    )
}

private val HOLE_CORNER = 20.dp
private val SCREEN_MARGIN = 12.dp
private val ANCHOR_GAP = 8.dp
private val FINGER_CLEARANCE = 28.dp
private val FLOAT_ELEVATION = 6.dp
private val CARD_MIN_WIDTH = 220.dp
private val CARD_MAX_WIDTH = 320.dp
private val CAPSULE_SHAPE = RoundedCornerShape(28.dp)
private val CARD_SHAPE = RoundedCornerShape(20.dp)
private const val ENTRANCE_FROM_SCALE = 0.9f
