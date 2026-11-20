package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TdMedia
import kotlinx.collections.immutable.toImmutableList

/**
 * Single full-screen media viewer for the whole app. Mounted once at the top of the UI tree
 * (see [MediaViewerHost]); any screen — timeline, comments, future channel detail — opens
 * media via [LocalMediaViewer.current.open]. Centralising this avoids each screen owning a
 * private `viewerState` and a duplicated [FullScreenMediaViewer] composable, and means the
 * viewer survives screen-level recomposition or navigation back/forward.
 */
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerController> {
    error("MediaViewerHost is missing — wrap your UI tree in MediaViewerHost { … }")
}

class MediaViewerController internal constructor(
    private val opener: (List<AlbumItem>, Int) -> Unit,
) {
    fun open(items: List<AlbumItem>, index: Int = 0) {
        if (items.isEmpty()) return
        opener(items, index)
    }

    /** Convenience: project [PostContent] and open if it has any viewable media. */
    fun openFor(content: PostContent, index: Int = 0) {
        content.toAlbumItems()?.let { open(it, index) }
    }
}

@Composable
fun MediaViewerHost(content: @Composable () -> Unit) {
    var state by remember { mutableStateOf<ViewerState?>(null) }
    val controller = remember {
        MediaViewerController { items, idx -> state = ViewerState(items, idx) }
    }
    CompositionLocalProvider(LocalMediaViewer provides controller) {
        content()
    }
    state?.let { vs ->
        FullScreenMediaViewer(
            // ViewerState carries a plain List (the controller's open() API stays List-typed
            // for its many callers); convert once per viewer open so the composable takes a
            // Compose-stable ImmutableList param.
            items = vs.items.toImmutableList(),
            initialIndex = vs.index,
            onDismiss = { state = null },
        )
    }
}

private data class ViewerState(
    val items: List<AlbumItem>,
    val index: Int,
)

/**
 * An on-screen media tile (photo, video poster, GIF) the fullscreen viewer can grow out of
 * and shrink back into. Every tile registers itself in [ViewerSourceRegistry] for as long as
 * it is composed, keyed by its [TdMedia]; the viewer looks the tile up by the media of the
 * page it is on — once on open and again on close — so no call site hands anything to the
 * viewer, and an album closes into the tile of the page the user swiped to, not the one
 * they tapped.
 *
 * The viewer is a separate Dialog window, so it cannot share a `SharedTransitionLayout`
 * with the feed — it maps the tile's layout into its own space through SCREEN coordinates
 * (valid across the activity and dialog windows) at the moment it needs them, so a tile
 * that moved while the viewer was up still receives the media at its current position.
 */
class ViewerSourceAnchor internal constructor(
    private val shape: Shape,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) {
    internal var coordinates: LayoutCoordinates? = null

    /**
     * True while the viewer is showing this tile's media; read in the draw phase only.
     * Without it the media is on screen twice during the transform — the copy flying to or
     * from the tile and the tile itself underneath.
     */
    internal var hidden by mutableStateOf(false)

    /** The tile's corner radius in px, from the shape its caller clips it to. */
    internal val cornerRadiusPx: Float
        get() {
            val coords = coordinates?.takeIf { it.isAttached } ?: return 0f
            val outline = shape.createOutline(coords.size.toSize(), layoutDirection, density)
            return (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        }

    /** The tile's bounds in [target]'s space, or null when it is detached or degenerate. */
    internal fun boundsIn(target: LayoutCoordinates): Rect? {
        val coords = coordinates ?: return null
        if (!coords.isAttached || !target.isAttached) return null
        val size = coords.size
        val topLeft = target.screenToLocal(coords.localToScreen(Offset.Zero))
        val bottomRight = target.screenToLocal(
            coords.localToScreen(Offset(size.width.toFloat(), size.height.toFloat())),
        )
        val rect = Rect(topLeft, bottomRight)
        return rect.takeIf { it.isFinite && it.width > 1f && it.height > 1f }
    }
}

/**
 * Registers a tile showing [media], clipped to [shape] by its caller, as a viewer
 * transition source for as long as it stays composed; pair with [Modifier.viewerSource].
 */
@Composable
fun rememberViewerSourceAnchor(media: TdMedia, shape: Shape): ViewerSourceAnchor {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val anchor = remember(shape, density, layoutDirection) {
        ViewerSourceAnchor(shape, density, layoutDirection)
    }
    DisposableEffect(media, anchor) {
        ViewerSourceRegistry.register(media, anchor)
        onDispose {
            ViewerSourceRegistry.unregister(media, anchor)
            anchor.hidden = false
        }
    }
    return anchor
}

/** Keeps [anchor] pointed at this node's layout and lets the viewer hide the tile. */
fun Modifier.viewerSource(anchor: ViewerSourceAnchor): Modifier =
    onPlaced { anchor.coordinates = it }.graphicsLayer { alpha = if (anchor.hidden) 0f else 1f }

/**
 * Composed media tiles by media. Main-thread only (composition effects on one side, the
 * viewer's effects and click handlers on the other). The same media can be composed twice
 * (a screen under the one on top, a repost); [find] takes the most recently registered
 * tile that is still attached, which is the one on the screen the user is looking at.
 */
internal object ViewerSourceRegistry {
    private val anchors = HashMap<TdMedia, ArrayList<ViewerSourceAnchor>>()

    fun register(media: TdMedia, anchor: ViewerSourceAnchor) {
        anchors.getOrPut(media) { ArrayList(1) }.add(anchor)
    }

    fun unregister(media: TdMedia, anchor: ViewerSourceAnchor) {
        val list = anchors[media] ?: return
        list.remove(anchor)
        if (list.isEmpty()) anchors.remove(media)
    }

    fun find(media: TdMedia?): ViewerSourceAnchor? =
        anchors[media ?: return null]?.lastOrNull { it.coordinates?.isAttached == true }
}
