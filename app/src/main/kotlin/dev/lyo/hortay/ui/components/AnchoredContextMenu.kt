package dev.lyo.hortay.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.lyo.hortay.data.warnUnlessCancelled
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    /** Whether that press is still held: the menu opened from a long-press, not a tap. */
    internal var pointerDown: Boolean = false

    /** The open menu's drag selection, fed with the held finger's moves (see [ContextMenuDragSink]). */
    internal var dragSink: ContextMenuDragSink? = null

    /** The anchor's snapshot node ([contextMenuAnchor]); null while the anchor is not attached. */
    internal var snapshotNode: AnchorSnapshotNode? = null

    /**
     * True only while the snapshot frame is recorded. The anchor's own transient chrome — an
     * unread strip, a highlight, a thread connector — reads this in its draw lambda and skips
     * itself, so the lifted copy and a shared screenshot show the post alone. A plain field,
     * read in the draw phase only: no recomposition, no layer.
     */
    var isCapturing: Boolean = false
        internal set

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

    /**
     * The anchor's whole bounds in [target]'s space, including any part scrolled off screen:
     * where the full snapshot from [capture] sits.
     */
    internal fun boundsIn(target: LayoutCoordinates): Rect? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        if (!target.isAttached) return null
        val size = coords.size
        if (size.width < 1 || size.height < 1) return null
        return Rect(target.screenToLocal(coords.localToScreen(Offset.Zero)), size.toSize())
    }

    internal fun toScreen(local: Offset): Offset? =
        coordinates?.takeIf { it.isAttached }?.localToScreen(local)

    /** A picture of the whole anchor as it draws on its next frame; null when it cannot be taken. */
    internal suspend fun capture(): ImageBitmap? = snapshotNode?.capture()?.await()
}

/** Marks this node as the thing an [AnchoredContextMenu] opened with [anchor] points at. */
fun Modifier.contextMenuAnchor(anchor: ContextMenuAnchor): Modifier = this
    .onPlaced { anchor.coordinates = it }
    // Observes only (Initial pass, nothing consumed): remembers where the finger went down,
    // so a menu that cannot sit beside its anchor opens at the finger instead, and keeps
    // following that finger — the gesture stays with this window after a long-press opens
    // the menu's dialog, so this is the only place the press-drag-release selection can
    // learn where it goes (see [ContextMenuDragSink]).
    .pointerInput(anchor) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            anchor.pressPosition = down.position
            anchor.pointerDown = true
            var ended = false
            try {
                var tracking = true
                while (tracking) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    val screen = change?.let { anchor.toScreen(it.position) }
                    when {
                        change == null -> tracking = false
                        change.pressed -> if (screen != null) anchor.dragSink?.move(screen)
                        else -> {
                            // A synthesized cancel also arrives as "not pressed"; only a real
                            // lift may run an action.
                            val action = event.motionEvent?.actionMasked
                            val lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                            if (lifted && screen != null) anchor.dragSink?.release(screen) else anchor.dragSink?.cancel()
                            ended = true
                            tracking = false
                        }
                    }
                }
            } finally {
                anchor.pointerDown = false
                if (!ended) anchor.dragSink?.cancel()
            }
        }
    }
    .then(AnchorSnapshotElement(anchor))

/** Receiver of the menu's content: the only way out of the menu from inside it. */
@Stable
interface ContextMenuScope {
    /** Runs [action] right away, then animates the menu out and reports the dismissal. */
    fun close(action: () -> Unit = {})

    /**
     * The picture of the anchor the menu lifts (the post alone, without the menu or the
     * blur), or null while it is not there — for a "share as image" action.
     */
    val anchorSnapshot: ImageBitmap?
}

/**
 * A context menu that stays with the thing it is about: the screen behind blurs, the
 * anchor itself lifts out of it — whole, sharp and at its natural size, as a card — and
 * [header] floats just above it with the [actions] card just below. Replaces the
 * half-screen bottom sheet the long-press used to open, which covered the very post it
 * acted on. A post lifted whole next to its menu is also what a reader wants to screenshot.
 *
 * The lift is a snapshot. A plain cross-window blur (blur the host, keep the anchor live in
 * a hole) cannot work: the blur is a property of the whole host window, so it blurs the
 * anchor along with everything else — the reason this menu once shipped with no blur at
 * all and only a dimmed scrim with a lit window over the anchor. Re-rendering the post
 * inside the menu's window would mean a second composition of a heavy card with its own
 * media loads. So the anchor's node records ONE frame of itself into a GraphicsLayer when
 * asked ([AnchorSnapshotNode]; a resting anchor holds no layer), the menu waits a frame or
 * two for that picture ([SNAPSHOT_TIMEOUT_MS] at most), draws it exactly over the original
 * and springs it to its lifted place while the host blurs ([BackdropBlur]). The price is
 * that the lifted copy is a still: a playing video shows the frame it was on (or its
 * poster, where the TextureView does not reach the snapshot) for as long as the menu is up.
 * On close the copy travels back onto the original, the blur fades, and only then does the
 * window go — the real post is under the copy at the same pixels, so it reappears without
 * a jump.
 *
 * Placement, in the dialog's safe-drawing area: header above the lifted anchor, actions
 * below it, under the finger horizontally; nothing ever overlaps the post. A post that fits
 * with the menu stays where it is, moved only as far as the menu needs. One that does not is
 * shrunk at most to [MIN_FIT_SCALE] — a first version scaled tall posts down to 0.4 and
 * fell back to lifting only their visible part, and readers found the post too small to
 * read or screenshot. Past that the capsule, the post at full size and the actions card
 * become ONE vertically scrollable column, as in Telegram, opened scrolled so the post
 * stays exactly where it was on screen; the actions are a scroll away.
 *
 * Without a snapshot (it did not come in time) nothing lifts: the menu sits beside the
 * anchor if it can and otherwise opens at the finger like a platform context menu — the
 * actions below the press with the header stacked straight on top of them. The anchor is
 * then blurred with the rest; on devices without blur (below API 31) a menu beside it leaves
 * a lit window over it in the scrim, while one at the finger dims the whole screen evenly —
 * a lit window the menu itself covers reads as a highlight on something you cannot see.
 *
 * The finger that long-pressed can slide straight onto an action and lift to run it
 * ([ContextMenuSelection]); lifting anywhere else leaves the menu open for taps.
 *
 * Hosted in a [Dialog] for free back handling and accessibility focus; the window recipe
 * (transparent, no system dim, no window animation) is the fullscreen viewer's. The lifted
 * copy is decorative for accessibility: the actions are the menu's a11y surface.
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
    val haptics = LocalHapticFeedback.current
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var ready by remember { mutableStateOf(false) }
    val menuScope = remember {
        object : ContextMenuScope {
            private var closing = false
            override val anchorSnapshot: ImageBitmap? get() = snapshot
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
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val selection = remember(touchSlop) {
        ContextMenuSelection(touchSlop) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    DisposableEffect(anchor, selection) {
        // A held finger means the long-press opened the menu: its clickable already gave the
        // long-press tick, and the finger may now slide onto an action. Anything else (the
        // "⋯" button, a keyboard or accessibility action) gets the tick here instead.
        if (anchor.pointerDown) anchor.dragSink = selection
        else haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDispose {
            if (anchor.dragSink === selection) anchor.dragSink = null
        }
    }
    LaunchedEffect(Unit) {
        // The entrance starts from the snapshot drawn over the original, so it waits for it —
        // a frame or two — and opens without a lift if it does not come in time.
        snapshot = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { anchor.capture() }
        ready = true
        if (reducedMotion) progress.snapTo(1f) else progress.animateTo(1f, enterSpec)
    }
    // Composed here, outside the Dialog, so it blurs the host window and not the menu's own.
    BackdropBlur(progress = { progress.value })

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
        val full = remember(root) { root?.let { anchor.boundsIn(it) } }
        val press = remember(root) { root?.let { anchor.pressIn(it) } }
        // Written by the menu's measure pass, read by the scrim's draw pass.
        var anchorLit by remember { mutableStateOf(false) }
        val liftFrame = remember { LiftFrame() }
        val scrollState = rememberScrollableState { delta -> liftFrame.scrollBy(delta) }
        val scrimColor = MaterialTheme.colorScheme.scrim
        val scrimAlpha = backdropScrimAlpha()
        // What the rows' transparent pixels show in place: the lists sit on `background`
        // (the dissolve effect backs its snapshots with the same colour).
        val backing = MaterialTheme.colorScheme.background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced {
                    if (root == null) root = it
                    selection.root = it
                }
                .scrollable(scrollState, Orientation.Vertical)
                .pointerInput(Unit) { detectTapGestures { menuScope.close() } }
                .drawBehind {
                    val alpha = progress.value.coerceIn(0f, 1f) * scrimAlpha
                    if (hole == null || !anchorLit) {
                        drawRect(scrimColor, alpha = alpha)
                    } else {
                        val radius = HOLE_CORNER.toPx()
                        val window = Path().apply { addRoundRect(RoundRect(hole, CornerRadius(radius, radius))) }
                        clipPath(window, clipOp = ClipOp.Difference) { drawRect(scrimColor, alpha = alpha) }
                    }
                },
        ) {
            if (root != null && ready) {
                val bitmap = snapshot
                CompositionLocalProvider(LocalContextMenuSelection provides selection) {
                    MenuLayout(
                        hole = hole,
                        full = full.takeIf { bitmap != null },
                        press = press,
                        liftFrame = liftFrame,
                        onPlacement = { anchorLit = it },
                        progress = { progress.value },
                        lifted = {
                            if (bitmap != null && full != null) {
                                LiftedAnchor(bitmap, liftFrame, backing) { progress.value }
                            } else {
                                Box(Modifier)
                            }
                        },
                        header = { menuScope.header() },
                        actions = { menuScope.actions() },
                    )
                }
            }
        }
    }
}

/**
 * Where the lifted copy travels — from [source] (its place on screen) to [target], in the
 * column's unscrolled space — and how far that column is scrolled. Written by the layout.
 */
@Stable
private class LiftFrame {
    var source by mutableStateOf(Rect.Zero)
    var target by mutableStateOf(Rect.Zero)

    /** How far the menu column is scrolled up, 0..[maxScroll]. */
    var scroll by mutableFloatStateOf(0f)
    var maxScroll = 0f
    var scrollPlaced = false

    /** Whole pixels: the copy's placement and its travel must agree to the pixel. */
    val scrollPx: Int get() = scroll.roundToInt()

    fun scrollBy(delta: Float): Float {
        val next = (scroll - delta).coerceIn(0f, maxScroll)
        val consumed = scroll - next
        scroll = next
        return consumed
    }
}

/**
 * The anchor's snapshot over an opaque [backing]. Measured and placed at [LiftFrame.target]
 * (scrolled); the layer maps it back onto the source rect at progress 0, so it opens and
 * closes exactly over the original.
 */
@Composable
private fun LiftedAnchor(
    bitmap: ImageBitmap,
    frame: LiftFrame,
    backing: Color,
    progress: () -> Float,
) {
    Box(
        Modifier
            .clearAndSetSemantics {}
            .graphicsLayer {
                val placed = frame.target
                if (placed.width <= 0f) return@graphicsLayer
                val to = placed.translate(0f, -frame.scrollPx.toFloat())
                val from = frame.source
                val p = progress()
                val settled = p.coerceIn(0f, 1f)
                val scale = lerp(from.width, to.width, p) / to.width
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerp(from.left, to.left, p) - to.left
                translationY = lerp(from.top, to.top, p) - to.top
                scaleX = scale
                scaleY = scale
                // The corner radius is drawn before the layer's scale: divide it out so the
                // card's corners read the same at every step of the travel.
                shape = RoundedCornerShape(LIFT_CORNER.toPx() * settled / scale.coerceAtLeast(MIN_FIT_SCALE))
                clip = true
                shadowElevation = LIFT_ELEVATION.toPx() * settled
            }
            .drawBehind {
                drawRect(backing)
                drawImage(
                    image = bitmap,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            },
    )
}

@Composable
private fun MenuLayout(
    hole: Rect?,
    /** The anchor's whole bounds when a snapshot is there to lift; null otherwise. */
    full: Rect?,
    press: Offset?,
    liftFrame: LiftFrame,
    onPlacement: (anchorLit: Boolean) -> Unit,
    progress: () -> Float,
    lifted: @Composable () -> Unit,
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val insets = WindowInsets.safeDrawing
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            lifted()
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

        val headerPlaceable = measurables[1].measure(Constraints(maxWidth = availWidth, maxHeight = availHeight))
        // An empty header (no reactions to offer) takes no room and no gap.
        val headerSpace = if (headerPlaceable.height > 0) headerPlaceable.height + gap else 0
        // With a lift the column scrolls, so the card keeps its natural height (up to a
        // screen; past that it scrolls on its own). Without one it gets what the header leaves.
        val actionsMax = if (full != null) availHeight else (availHeight - headerSpace).coerceAtLeast(0)
        val actionsPlaceable = measurables[2].measure(Constraints(maxWidth = availWidth, maxHeight = actionsMax))
        val actionsHeight = actionsPlaceable.height
        val belowSpace = if (actionsHeight > 0) actionsHeight + gap else 0
        val startX = if (layoutDirection == LayoutDirection.Rtl) right - actionsPlaceable.width else left
        fun actionsXFor(pressX: Float?) = (pressX?.roundToInt()?.minus(actionsPlaceable.width / 2) ?: startX)
            .coerceAtMost(right - actionsPlaceable.width)
            .coerceAtLeast(left)

        if (full != null) {
            val room = (availHeight - headerSpace - belowSpace).toFloat()
            val fitScale = (room / full.height).coerceAtMost(1f)
            val scrolls = fitScale < MIN_FIT_SCALE
            val scale = if (scrolls) 1f else fitScale
            val w = (full.width * scale).roundToInt().coerceAtLeast(1)
            val h = (full.height * scale).roundToInt().coerceAtLeast(1)
            // In place horizontally at full size; a shrunk copy centres on where it was,
            // kept inside the window.
            val x = (if (scale >= 1f) full.left else full.center.x - w / 2f)
                .coerceAtMost((constraints.maxWidth - w).toFloat())
                .coerceAtLeast(0f)
                .roundToInt()
            val y: Int
            if (scrolls) {
                // One column — capsule, post, card — scrolled so the post sits where it was.
                y = top + headerSpace
                liftFrame.maxScroll = (y + h + belowSpace - bottom).toFloat().coerceAtLeast(0f)
                if (!liftFrame.scrollPlaced) {
                    liftFrame.scroll = (y - full.top).coerceIn(0f, liftFrame.maxScroll)
                    liftFrame.scrollPlaced = true
                } else {
                    liftFrame.scroll = liftFrame.scroll.coerceIn(0f, liftFrame.maxScroll)
                }
            } else {
                // Moved only as far as the menu needs: just under the header, just over the card.
                y = full.top
                    .coerceAtMost((bottom - belowSpace - h).toFloat())
                    .coerceAtLeast((top + headerSpace).toFloat())
                    .roundToInt()
                liftFrame.maxScroll = 0f
                liftFrame.scroll = 0f
            }
            liftFrame.source = full
            liftFrame.target = Rect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
            onPlacement(false)
            val liftPlaceable = measurables[0].measure(Constraints.fixed(w, h))
            // The finger's spot on the copy, so the card stays under it after a shrink.
            val pressX = press?.let { x + (it.x - full.left) * scale }
            val actionsX = actionsXFor(pressX)
            return@Layout layout(constraints.maxWidth, constraints.maxHeight) {
                // Read here, in placement only: scrolling re-places, it never re-measures.
                val offset = liftFrame.scrollPx
                liftPlaceable.place(x, y - offset)
                headerPlaceable.place(left + (availWidth - headerPlaceable.width) / 2, y - headerSpace - offset)
                actionsPlaceable.place(actionsX, y + h + gap - offset)
            }
        }

        liftFrame.maxScroll = 0f
        liftFrame.scroll = 0f
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
        // The anchor is lit only when the menu sits clear of it, and only without a backdrop
        // blur: lighting a post the menu then covers made it stand out and hid it at the same
        // time, and a hole over a blurred post shows nothing but blur.
        onPlacement(besideY != null && !backdropBlurAvailable)
        val clearance = FINGER_CLEARANCE.roundToPx()
        val actionsY = (besideY?.second ?: (pressY + clearance))
            .coerceAtMost(bottom - actionsHeight)
            .coerceAtLeast(top + headerSpace)
        // At the finger the header sits right on top of the card, one [ANCHOR_GAP] apart, so
        // capsule and card read as one menu. It used to keep its own [FINGER_CLEARANCE] above
        // the press, which opened a ~64 dp hole between them with a slice of the dimmed post
        // showing through — two floating things instead of one.
        val headerY = (besideY?.first ?: (actionsY - headerSpace))
            .coerceAtMost(actionsY - headerSpace)
            .coerceAtLeast(top)
        // Nothing lifts: the copy is measured empty and never placed, so it never draws.
        measurables[0].measure(Constraints.fixed(0, 0))

        layout(constraints.maxWidth, constraints.maxHeight) {
            headerPlaceable.place(left + (availWidth - headerPlaceable.width) / 2, headerY)
            actionsPlaceable.place(actionsXFor(press?.x), actionsY)
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

private class AnchorSnapshotElement(val anchor: ContextMenuAnchor) : ModifierNodeElement<AnchorSnapshotNode>() {
    override fun create() = AnchorSnapshotNode(anchor)
    override fun update(node: AnchorSnapshotNode) = node.update(anchor)
    override fun InspectorInfo.inspectableProperties() {
        name = "contextMenuAnchorSnapshot"
    }
    override fun equals(other: Any?) = other is AnchorSnapshotElement && other.anchor === anchor
    override fun hashCode() = System.identityHashCode(anchor)
}

/**
 * Takes the picture an [AnchoredContextMenu] lifts. Draws straight through — no layer —
 * until [capture] is asked for: a resting card must stay layer-free (see PostCard's press
 * scale). On a request the next draw records the content once more into a [GraphicsLayer]
 * at the node's full size (so a part scrolled off screen is in the picture too), with
 * [ContextMenuAnchor.isCapturing] set so the anchor's transient chrome stays out of it, and
 * then draws the content normally. The recorded layer is never part of what the window
 * draws, so it is freed as soon as it has been turned into a bitmap.
 */
internal class AnchorSnapshotNode(private var anchor: ContextMenuAnchor) : Modifier.Node(), DrawModifierNode {

    private var graphics: GraphicsContext? = null
    private var request: CompletableDeferred<ImageBitmap?>? = null
    private var capturing = false

    fun update(anchor: ContextMenuAnchor) {
        if (anchor === this.anchor) return
        unregister()
        this.anchor = anchor
        if (isAttached) anchor.snapshotNode = this
    }

    override fun onAttach() {
        graphics = requireGraphicsContext()
        anchor.snapshotNode = this
    }

    override fun onReset() {
        request?.complete(null)
        request = null
    }

    override fun onDetach() {
        unregister()
        request?.complete(null)
        request = null
    }

    private fun unregister() {
        if (anchor.snapshotNode === this) anchor.snapshotNode = null
    }

    fun capture(): Deferred<ImageBitmap?> {
        request?.let { return it }
        if (!isAttached) return CompletableDeferred(null)
        return CompletableDeferred<ImageBitmap?>().also {
            request = it
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val pending = request
        val graphics = graphics
        if (pending == null || graphics == null || capturing) {
            drawContent()
            return
        }
        request = null
        val source = graphics.createGraphicsLayer()
        anchor.isCapturing = true
        try {
            source.record { this@draw.drawContent() }
        } finally {
            anchor.isCapturing = false
        }
        drawContent()
        val texScale = liftCaptureScale(size.width, size.height)
        val pixels = IntSize(
            ceil(size.width * texScale).toInt().coerceAtLeast(1),
            ceil(size.height * texScale).toInt().coerceAtLeast(1),
        )
        val density = requireDensity()
        val direction = requireLayoutDirection()
        capturing = true
        coroutineScope.launch {
            try {
                val bitmap = runCatching {
                    if (texScale >= 1f) {
                        source.toImageBitmap()
                    } else {
                        val crop = graphics.createGraphicsLayer()
                        try {
                            crop.record(density, direction, pixels) {
                                scale(texScale, texScale, pivot = Offset.Zero) { drawLayer(source) }
                            }
                            crop.toImageBitmap()
                        } finally {
                            graphics.releaseGraphicsLayer(crop)
                        }
                    }
                }.warnUnlessCancelled(TAG, "snapshot").getOrNull()
                pending.complete(bitmap)
            } finally {
                pending.complete(null)
                graphics.releaseGraphicsLayer(source)
                capturing = false
                if (request != null && isAttached) invalidateDraw()
            }
        }
    }
}

private fun liftCaptureScale(width: Float, height: Float): Float {
    val px = width * height
    return if (px <= MAX_LIFT_CAPTURE_PIXELS) 1f else sqrt(MAX_LIFT_CAPTURE_PIXELS / px)
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
    val selection = LocalContextMenuSelection.current
    val key = rememberMenuSelectionKey(selection, onClick)
    val hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = HOVER_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPlaced { selection?.place(key, it) }
            .drawBehind { if (selection?.hovered === key) drawRect(hoverColor) }
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = symbol, tint = iconColor, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
    }
}

/**
 * One of a context menu's most-used actions, drawn as a tile by [ContextMenuTiles]. [label] is
 * the short word under the icon; [longLabel] is the full action wording, used as the
 * screen-reader label and as the row label when the tiles fall back to rows.
 */
@Immutable
class ContextMenuTile(
    val symbol: String,
    val label: String,
    val longLabel: String,
    val onClick: () -> Unit,
    /** A toggle that is currently on (a saved post): filled icon in the primary colour. */
    val selected: Boolean = false,
)

/**
 * The menu's most-used actions as one row of equal tonal tiles, icon over a short label, at
 * the top of a [ContextMenuCard]. Three full-height rows became one ~64 dp row, which is
 * most of what makes the post menu shorter and easier to scan.
 *
 * Falls back to ordinary [ContextMenuItem] rows (with [ContextMenuTile.longLabel]) when
 *  - fewer than two tiles are left (a missing action must not leave a hole or a lone tile);
 *  - a tile would be too narrow for its label: any single word wider than the tile, or a
 *    label that needs more than about two lines. That is the font-scale-2.0 and narrow-window
 *    case, where squeezed tiles would break words mid-way.
 * The decision is made from the labels' text intrinsics against the widest card the window
 * allows — not from a measured constraint — because the card sizes itself with
 * `IntrinsicSize.Max`, which a SubcomposeLayout (BoxWithConstraints) cannot answer.
 */
@Composable
fun ContextMenuTiles(tiles: List<ContextMenuTile>) {
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val labels = tiles.map { it.label }
    val asTiles = remember(labels, style, density, fontFamilyResolver, windowWidthPx) {
        tilesFit(labels, style, density, fontFamilyResolver, windowWidthPx)
    }
    if (!asTiles) {
        tiles.forEach { ContextMenuItem(symbol = it.symbol, label = it.longLabel, onClick = it.onClick) }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = TILE_ROW_H_PADDING, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        val selection = LocalContextMenuSelection.current
        tiles.forEach { tile ->
            val key = rememberMenuSelectionKey(selection, tile.onClick)
            val hovered = selection?.hovered === key
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = TILE_MIN_HEIGHT)
                    .onPlaced { selection?.place(key, it) }
                    .clip(TILE_SHAPE)
                    .background(
                        if (hovered) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .clickable(role = Role.Button, onClick = tile.onClick)
                    .padding(horizontal = TILE_H_PADDING, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Symbol(
                    name = tile.symbol,
                    tint = if (tile.selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 24.dp,
                    filled = tile.selected,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tile.label,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // The short word is for the eye; a screen reader gets the full action.
                    modifier = Modifier.clearAndSetSemantics { contentDescription = tile.longLabel },
                )
            }
        }
    }
}

private fun tilesFit(
    labels: List<String>,
    style: TextStyle,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    windowWidthPx: Int,
): Boolean {
    if (labels.size < 2) return false
    with(density) {
        val windowCard = if (windowWidthPx > 0) windowWidthPx - (SCREEN_MARGIN * 2).toPx() else Float.MAX_VALUE
        val card = minOf(CARD_MAX_WIDTH.toPx(), windowCard)
        val row = card - (TILE_ROW_H_PADDING * 2).toPx() - TILE_GAP.toPx() * (labels.size - 1)
        val content = row / labels.size - (TILE_H_PADDING * 2).toPx()
        if (content <= 0f) return false
        return labels.all { label ->
            val intrinsics = ParagraphIntrinsics(
                text = label,
                style = style,
                density = density,
                fontFamilyResolver = fontFamilyResolver,
            )
            intrinsics.minIntrinsicWidth <= content && intrinsics.maxIntrinsicWidth <= content * TILE_MAX_LINES_BUDGET
        }
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
private val LIFT_CORNER = 20.dp
private val LIFT_ELEVATION = 8.dp

/** How long the entrance waits for the anchor's snapshot before opening without a lift. */
private const val SNAPSHOT_TIMEOUT_MS = 150L

/**
 * The most a lifted post shrinks to fit with the menu; below it the menu scrolls instead.
 * Barely noticeable, and it saves a scroll for a post a few rows too tall.
 */
private const val MIN_FIT_SCALE = 0.9f

/** Snapshots above this many pixels are scaled down (memory: 4 bytes per pixel). */
private const val MAX_LIFT_CAPTURE_PIXELS = 4_000_000f

private val ROW_MIN_HEIGHT = 52.dp

/** The tint of the row under a sliding finger: a pressed state, without a ripple. */
private const val HOVER_ALPHA = 0.08f

private const val TAG = "ContextMenu"
private val SCREEN_MARGIN = 12.dp
private val ANCHOR_GAP = 8.dp
private val FINGER_CLEARANCE = 28.dp
private val FLOAT_ELEVATION = 6.dp
private val CARD_MIN_WIDTH = 260.dp
private val CARD_MAX_WIDTH = 300.dp
private val CAPSULE_SHAPE = RoundedCornerShape(28.dp)
private val CARD_SHAPE = RoundedCornerShape(20.dp)
private const val ENTRANCE_FROM_SCALE = 0.9f
private val TILE_SHAPE = RoundedCornerShape(14.dp)
private val TILE_MIN_HEIGHT = 64.dp
private val TILE_GAP = 6.dp
private val TILE_ROW_H_PADDING = 8.dp
private val TILE_H_PADDING = 6.dp

/**
 * A label may take up to two lines in a tile; a little under 2× the tile width leaves room
 * for the uneven break between words, so a label that passes never ends in an ellipsis.
 */
private const val TILE_MAX_LINES_BUDGET = 1.8f
