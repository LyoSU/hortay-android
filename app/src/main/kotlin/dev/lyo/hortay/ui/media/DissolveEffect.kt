package dev.lyo.hortay.ui.media

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lyo.hortay.data.LiveDeletion
import dev.lyo.hortay.data.warnUnlessCancelled
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The "Thanos" dissolve: a post or comment that is on screen when it is deleted live breaks
 * into drifting particles in place, the way Telegram dissolves deleted messages, while the list
 * closes over it. Particle maths: [DissolveField] (pure, after DrKLO/Telegram `ThanosEffect`).
 *
 * Pieces, per screen:
 *  * [rememberDissolveController] — collects the repository's [LiveDeletion] hints (emitted
 *    BEFORE the list drops the rows; only for permanent, non-cached deletes that remove whole
 *    rows — never for tombstones, album trims, hides, filters, refreshes or logout) while the
 *    screen is RESUMED.
 *  * [dissolveSource] on each row — the row draws through its own [GraphicsLayer] (behind a
 *    plain `graphicsLayer {}` so scrolling only moves it and never re-records it), so the
 *    pixels of the last frame are always at hand: when a hint names the row, the visible part
 *    of that layer is snapshotted ([GraphicsLayer.toImageBitmap]), the row stops drawing, and
 *    the snapshot is handed to the overlay. A row that is not on screen has no node, so an
 *    off-screen delete just disappears. Under reduced motion the row draws plainly.
 *  * [dissolveOverlay] on the list — ONE overlay per screen; it draws the running effects
 *    over the list and nothing at all while none runs.
 *
 * Rules:
 *  * **No idle clock.** Frame time comes from the spoiler engine's shared on-demand
 *    Choreographer clock ([SpoilerParticles.markRevealing] / [SpoilerParticles.frameTimeMs]),
 *    read in the DRAW phase only — never a recomposition per frame. The overlay asks for the
 *    clock only while an effect is running, so it stops a few frames after the last one ends.
 *  * **A couple of draw calls per effect.** API 29+ draws the particles with
 *    `Canvas.drawVertices` (triangles, a [BitmapShader] over the snapshot, per-vertex alpha
 *    modulating it) — one call, at most [DISSOLVE_MAX_QUADS] cells (16-bit indices), every
 *    array offset 0, argument shapes checked by [dissolveVerticesArgsValid]; per frame the CPU only rewrites the vertex/colour arrays in place, no allocation. API
 *    26–28 hardware canvases can't draw vertices, so they fall back to one `drawBitmap` per
 *    cell on a much coarser grid ([LEGACY_MAX_CELLS]).
 *  * **Opaque until released.** The list reflows at once, so a neighbour lands under the
 *    picture while the snapshot is transparent around glyphs. Cells the sweep has not released
 *    are backed by the surface the rows sit on (one solid-quad `drawVertices` pass under the
 *    particles, or a `drawRect` per cell on 26–28), so the old row fully covers the neighbour
 *    and the front uncovers it; released cells fly unbacked. See [DissolveField].
 *  * **Capped.** At most [MAX_CONCURRENT] effects at once (a channel purge); further rows just
 *    disappear. Cells per effect are bounded by [VERTICES_MAX_CELLS] / [VERTICES_CELL_BUDGET].
 *  * **Why not GL / AGSL.** A GL surface or TextureView per effect costs a surface, a context
 *    and a compositor layer for 1.5 s of eye candy, and breaks with the list's clipping and
 *    insets; AGSL is fragment-only, so particles that fly out of their cell would need an
 *    inverse search per pixel. Closed-form vertex positions + `drawVertices` keep it in
 *    the normal display list at a few hundred µs of CPU per frame.
 *
 * The list itself is not animated here: the feed and channel reflow instantly (their
 * `animateItem` was retired — see `TimelineFeedColumn`), comments keep their placement spring.
 */
@Stable
internal class DissolveController internal constructor(private val scope: CoroutineScope) {
    /** False under reduced motion: rows draw plainly and deletions don't dissolve. */
    internal var enabled = false

    /** ARGB of the surface the rows sit on; backs cells the sweep has not released yet. */
    internal var backing = 0

    internal var overlay: LayoutCoordinates? = null

    private val sources = HashMap<RowRef, DissolveSourceNode>()
    private val runs = ArrayList<DissolveRun>()
    private var pending = 0
    private val version = mutableIntStateOf(0)
    private var indices: ShortArray? = null

    private data class RowRef(val chatId: Long, val messageId: Long)

    /** A hint from the repository: rows with these ids are about to leave the list. */
    fun onDeleted(deletion: LiveDeletion) {
        if (!enabled) return
        for (id in deletion.messageIds) {
            val node = sources[RowRef(deletion.chatId, id)] ?: continue
            if (pending + runs.size >= MAX_CONCURRENT) return
            node.dissolve()
        }
    }

    internal fun register(node: DissolveSourceNode, chatId: Long, messageId: Long) {
        sources[RowRef(chatId, messageId)] = node
    }

    internal fun unregister(node: DissolveSourceNode, chatId: Long, messageId: Long) {
        val ref = RowRef(chatId, messageId)
        if (sources[ref] === node) sources.remove(ref)
    }

    internal fun admits(visible: Rect, density: Float): Boolean =
        dissolveAdmits(enabled, pending + runs.size, visible.width, visible.height, MIN_VISIBLE_DP * density)

    internal fun launchCapture(block: suspend CoroutineScope.() -> Unit) {
        pending++
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } finally {
                pending--
            }
        }
    }

    internal fun start(bitmap: ImageBitmap, visible: Rect, texScale: Float, density: Float, rtl: Boolean) {
        val vertices = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val maxCells = if (vertices) {
            min(VERTICES_MAX_CELLS, VERTICES_CELL_BUDGET / (runs.size + 1))
        } else {
            LEGACY_MAX_CELLS
        }
        val minCellPx = (if (vertices) VERTICES_MIN_CELL_DP else LEGACY_MIN_CELL_DP) * density
        val grid = dissolveGrid(visible.width, visible.height, minCellPx, maxCells)
        if (grid.count == 0) return
        val field = DissolveField(
            left = visible.left,
            top = visible.top,
            width = visible.width,
            height = visible.height,
            texScale = texScale,
            grid = grid,
            dp = density,
            rtl = rtl,
            seed = System.nanoTime().toInt(),
            backingColor = backing,
        )
        val quadIndices = if (vertices) {
            indices ?: dissolveQuadIndices(DISSOLVE_MAX_QUADS).also { indices = it }
        } else {
            null
        }
        runs += DissolveRun(bitmap, field, quadIndices, SpoilerParticles.nowMs(), backing)
        version.intValue++
    }

    /** Draw phase of the overlay: every running effect, then nothing once they are done. */
    internal fun DrawScope.drawRuns() {
        // Read first, always: a new run invalidates this draw through it.
        val generation = version.intValue
        if (runs.isEmpty() || generation < 0) return
        SpoilerParticles.markRevealing()
        val now = SpoilerParticles.frameTimeMs()
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        canvas.clipRect(0f, 0f, size.width, size.height)
        val it = runs.iterator()
        while (it.hasNext()) {
            val run = it.next()
            val t = (now - run.startMs).coerceAtLeast(0L) / 1000f * DISSOLVE_TIME_SCALE
            if (t >= run.field.endTime) it.remove() else run.draw(canvas, t)
        }
        canvas.restoreToCount(save)
    }
}

/**
 * Whether a removal may dissolve: the effect is enabled (no reduced motion), fewer than
 * [MAX_CONCURRENT] effects are running or being captured, and the row shows at least a
 * [minVisiblePx] × [minVisiblePx] patch on screen.
 */
internal fun dissolveAdmits(
    enabled: Boolean,
    running: Int,
    visibleWidth: Float,
    visibleHeight: Float,
    minVisiblePx: Float,
): Boolean = enabled && running < MAX_CONCURRENT && visibleWidth >= minVisiblePx && visibleHeight >= minVisiblePx

/**
 * Scale at which a [width] × [height] px patch is captured: full resolution up to
 * [MAX_CAPTURE_PIXELS], uniformly smaller beyond (a tall post filling the screen).
 */
internal fun dissolveCaptureScale(width: Float, height: Float): Float {
    val px = width * height
    return if (px <= MAX_CAPTURE_PIXELS) 1f else sqrt(MAX_CAPTURE_PIXELS / px)
}

/** The screen's controller, collecting [deletions] (null: nothing ever dissolves) while RESUMED. */
@Composable
internal fun rememberDissolveController(
    deletions: Flow<LiveDeletion>?,
    // The surface behind the rows: what a row's transparent pixels show, and what the list
    // shows where a row was. All three lists sit on the Scaffold's `background`.
    backing: Color = MaterialTheme.colorScheme.background,
): DissolveController {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { DissolveController(scope) }
    val reduced = rememberReducedMotion()
    val backingArgb = backing.toArgb()
    SideEffect {
        controller.enabled = deletions != null && !reduced
        controller.backing = backingArgb
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (deletions != null) {
        LaunchedEffect(controller, deletions, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                deletions.collect { controller.onDeleted(it) }
            }
        }
    }
    return controller
}

/** The screen's single dissolve overlay; apply to the list the rows live in. */
internal fun Modifier.dissolveOverlay(controller: DissolveController): Modifier =
    // The layer below keeps the list in its own display list, so the per-frame redraw of
    // the overlay while an effect runs never re-records the list.
    this then DissolveOverlayElement(controller) then Modifier.graphicsLayer {}

/** Marks a row that dissolves when message [messageId] of [chatId] is deleted live. */
internal fun Modifier.dissolveSource(controller: DissolveController, chatId: Long, messageId: Long): Modifier =
    // The plain layer in front keeps scrolling from re-recording the row's own layer.
    this then Modifier.graphicsLayer {} then DissolveSourceElement(controller, chatId, messageId)

private class DissolveRun(
    bitmap: ImageBitmap,
    val field: DissolveField,
    private val indices: ShortArray?,
    val startMs: Long,
    backingColor: Int,
) {
    private val bitmap = bitmap.asAndroidBitmap()
    private val paint = Paint().apply {
        isFilterBitmap = true
        if (indices != null) shader = BitmapShader(this@DissolveRun.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    // Backing quads carry their colour per vertex; white paint leaves it unmodulated.
    private val backingPaint = Paint().apply {
        color = if (indices != null) AndroidColor.WHITE else backingColor or AndroidColor.BLACK
    }
    private val src = AndroidRect()
    private val dst = RectF()

    // The vertex call's argument shapes are fixed for the run (one call, offsets 0, arrays
    // sized for exactly [DissolveField.count] cells), so they are validated once: an
    // out-of-range argument reaches native memcpy unchecked and crashes the process.
    private val n = field.count
    private val backingValid = indices != null && dissolveVerticesArgsValid(
        n * 8, field.backVerts, 0, null, 0, field.backColors, 0, indices, 0, n * 6,
    )
    private val particlesValid = indices != null && dissolveVerticesArgsValid(
        n * 8, field.verts, 0, field.texs, 0, field.colors, 0, indices, 0, n * 6,
    )

    fun draw(canvas: Canvas, t: Float) {
        field.update(t)
        if (indices != null) drawVertices(canvas, indices) else drawCells(canvas)
    }

    /**
     * API 29+: a backing pass while any cell is at rest, then the particle pass — each ONE
     * call with every offset 0 (the platform offsets a null array blindly, and its offset
     * handling has a history of bugs), at most [DISSOLVE_MAX_QUADS] cells.
     */
    private fun drawVertices(canvas: Canvas, indices: ShortArray) {
        if (backingValid && field.restCount > 0) {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                n * 8, field.backVerts, 0,
                null, 0,
                field.backColors, 0,
                indices, 0, n * 6,
                backingPaint,
            )
        }
        if (particlesValid) {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                n * 8, field.verts, 0,
                field.texs, 0,
                field.colors, 0,
                indices, 0, n * 6,
                paint,
            )
        }
    }

    /** API 26–28: per cell, a backing rect while at rest, then the cell's pixels. */
    private fun drawCells(canvas: Canvas) {
        val texs = field.texs
        val verts = field.verts
        for (i in 0 until field.count) {
            val a = field.alphaOf(i)
            val o = i * 8
            dst.set(verts[o], verts[o + 1], verts[o + 4], verts[o + 5])
            if (field.isAtRest(i)) canvas.drawRect(dst, backingPaint)
            if (a > 0) {
                src.set(texs[o].toInt(), texs[o + 1].toInt(), ceil(texs[o + 4]).toInt(), ceil(texs[o + 5]).toInt())
                paint.alpha = a
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
        }
    }
}

private class DissolveOverlayElement(val controller: DissolveController) : ModifierNodeElement<DissolveOverlayNode>() {
    override fun create() = DissolveOverlayNode(controller)
    override fun update(node: DissolveOverlayNode) {
        node.controller = controller
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "dissolveOverlay"
    }
    override fun equals(other: Any?) = other is DissolveOverlayElement && other.controller === controller
    override fun hashCode() = System.identityHashCode(controller)
}

private class DissolveOverlayNode(var controller: DissolveController) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {

    override fun onPlaced(coordinates: LayoutCoordinates) {
        controller.overlay = coordinates
    }

    override fun onDetach() {
        controller.overlay = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        with(controller) { drawRuns() }
    }
}

private class DissolveSourceElement(
    val controller: DissolveController,
    val chatId: Long,
    val messageId: Long,
) : ModifierNodeElement<DissolveSourceNode>() {
    override fun create() = DissolveSourceNode(controller, chatId, messageId)
    override fun update(node: DissolveSourceNode) = node.update(controller, chatId, messageId)
    override fun InspectorInfo.inspectableProperties() {
        name = "dissolveSource"
        properties["chatId"] = chatId
        properties["messageId"] = messageId
    }
    override fun equals(other: Any?) =
        other is DissolveSourceElement && other.controller === controller &&
            other.chatId == chatId && other.messageId == messageId
    override fun hashCode(): Int {
        var h = System.identityHashCode(controller)
        h = 31 * h + chatId.hashCode()
        return 31 * h + messageId.hashCode()
    }
}

internal class DissolveSourceNode(
    private var controller: DissolveController,
    private var chatId: Long,
    private var messageId: Long,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {

    private var layer: GraphicsLayer? = null
    private var graphics: GraphicsContext? = null
    private var coordinates: LayoutCoordinates? = null
    private var hidden = false
    private var capturing = false

    // Registered only while the row actually draws: a lazy list keeps rows it scrolled away
    // attached-but-deactivated in its reuse pool ([onReset]); those must not be found.
    private var registered = false

    fun update(controller: DissolveController, chatId: Long, messageId: Long) {
        if (controller === this.controller && chatId == this.chatId && messageId == this.messageId) return
        unregister()
        this.controller = controller
        this.chatId = chatId
        this.messageId = messageId
        hidden = false
        if (isAttached) invalidateDraw()
    }

    override fun onAttach() {
        graphics = requireGraphicsContext()
    }

    override fun onReset() {
        unregister()
        hidden = false
    }

    override fun onDetach() {
        unregister()
        coordinates = null
        hidden = false
        if (!capturing) releaseLayer()
    }

    private fun register() {
        if (registered) return
        registered = true
        controller.register(this, chatId, messageId)
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        controller.unregister(this, chatId, messageId)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        register()
    }

    override fun ContentDrawScope.draw() {
        if (hidden) return
        register()
        if (!controller.enabled) {
            if (!capturing) releaseLayer()
            drawContent()
            return
        }
        val l = layer ?: graphics?.createGraphicsLayer()?.also { layer = it }
        if (l == null) {
            drawContent()
            return
        }
        l.record { this@draw.drawContent() }
        drawLayer(l)
    }

    /** Snapshots the visible part of the last drawn frame and hands it to the overlay. */
    fun dissolve() {
        if (hidden || capturing) return
        val rowLayer = layer ?: return
        val graphics = graphics ?: return
        val coords = coordinates?.takeIf { it.isAttached } ?: return
        val overlay = controller.overlay?.takeIf { it.isAttached } ?: return
        val density = requireDensity()
        val visible = overlay.localBoundingBoxOf(coords, clipBounds = true)
        if (!controller.admits(visible, density.density)) return
        val origin = overlay.localPositionOf(coords, Offset.Zero)
        val texScale = dissolveCaptureScale(visible.width, visible.height)
        val size = IntSize(
            ceil(visible.width * texScale).toInt().coerceAtLeast(1),
            ceil(visible.height * texScale).toInt().coerceAtLeast(1),
        )
        val direction = requireLayoutDirection()
        val crop = graphics.createGraphicsLayer()
        crop.record(density, direction, size) {
            scale(texScale, texScale, pivot = Offset.Zero) {
                translate(origin.x - visible.left, origin.y - visible.top) { drawLayer(rowLayer) }
            }
        }
        capturing = true
        val ref = messageId
        controller.launchCapture {
            val bitmap = try {
                runCatching { crop.toImageBitmap() }.warnUnlessCancelled(TAG, "snapshot").getOrNull()
            } finally {
                graphics.releaseGraphicsLayer(crop)
                capturing = false
                if (!isAttached) releaseLayer()
            }
            if (bitmap == null) return@launchCapture
            controller.start(bitmap, visible, texScale, density.density, direction == LayoutDirection.Rtl)
            if (isAttached && messageId == ref) hideUntilRemoved()
        }
    }

    // The row normally leaves the list a frame or two after the hint; should it stay (a
    // race with a re-ingest), it comes back instead of leaving a hole.
    private fun hideUntilRemoved() {
        hidden = true
        invalidateDraw()
        coroutineScope.launch {
            delay(UNHIDE_AFTER_MS)
            hidden = false
            invalidateDraw()
        }
    }

    private fun releaseLayer() {
        val l = layer ?: return
        layer = null
        graphics?.releaseGraphicsLayer(l)
    }
}

/** At most this many rows dissolve at once; the rest of a burst just disappears. */
internal const val MAX_CONCURRENT = 4

/** A row needs at least this much on screen, both ways, to dissolve. */
private const val MIN_VISIBLE_DP = 4f

/** Captures above this many pixels are scaled down (memory: 4 bytes per pixel). */
private const val MAX_CAPTURE_PIXELS = 1_500_000f

// One 16-bit-indexed call per pass. At the 1.1 dp floor that covers a ~360 × 50 dp row; a
// typical 360 × 160 dp text row gets ~1.9 dp dust, and taller rows coarsen rather than pay
// for a second call and twice the vertex data every frame.
private const val VERTICES_MAX_CELLS = DISSOLVE_MAX_QUADS
private const val VERTICES_CELL_BUDGET = 40_000
private const val VERTICES_MIN_CELL_DP = 1.1f
private const val LEGACY_MAX_CELLS = 700
private const val LEGACY_MIN_CELL_DP = 5f

private const val UNHIDE_AFTER_MS = 2_000L

private const val TAG = "Dissolve"
