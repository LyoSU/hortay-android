package dev.lyo.hortay.ui.media

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    private val opener: (List<AlbumItem>, Int, ViewerTransitionSource?) -> Unit,
) {
    fun open(items: List<AlbumItem>, index: Int = 0) {
        if (items.isEmpty()) return
        // Claim the tile the user just tapped (if any) as the viewer's grow-from origin.
        // Always consumed — a stale origin must never leak into a later, unrelated open.
        val source = ViewerSourceRegistry.consume(items.getOrNull(index.coerceIn(0, items.lastIndex)))
        opener(items, index, source)
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
        MediaViewerController { items, idx, source -> state = ViewerState(items, idx, source) }
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
            source = vs.source,
        )
    }
}

private data class ViewerState(
    val items: List<AlbumItem>,
    val index: Int,
    val source: ViewerTransitionSource?,
)

/**
 * Where the viewer's open/close container transform starts and ends: the on-screen tile the
 * user tapped. The viewer is a separate Dialog window, so it cannot share a
 * `SharedTransitionLayout` with the feed — instead it maps the tile's layout into its own
 * space through SCREEN coordinates (valid across the activity and dialog windows) at the
 * moment it needs them: once on open, and again on close so a tile that moved while the
 * viewer was up (album repair, a late layout pass) still receives the photo at its current
 * position. A tile that left the composition yields null and the viewer falls back to its
 * plain, non-animated close.
 */
class ViewerTransitionSource internal constructor(
    private val coordinates: LayoutCoordinates,
    internal val cornerRadiusPx: Float,
    private val anchor: ViewerSourceAnchor,
) {
    /**
     * Hides the tile in the feed while the viewer shows its photo. Without this the photo is
     * on screen twice during the transform — the copy flying to or from the tile and the
     * tile itself underneath — which reads as a doubled image when it lands.
     */
    internal fun setTileHidden(hidden: Boolean) {
        anchor.hidden = hidden
    }

    internal fun boundsIn(target: LayoutCoordinates): Rect? {
        if (!coordinates.isAttached || !target.isAttached) return null
        val size = coordinates.size
        val topLeft = target.screenToLocal(coordinates.localToScreen(Offset.Zero))
        val bottomRight = target.screenToLocal(
            coordinates.localToScreen(Offset(size.width.toFloat(), size.height.toFloat())),
        )
        val rect = Rect(topLeft, bottomRight)
        return rect.takeIf { it.isFinite && it.width > 1f && it.height > 1f }
    }
}

/**
 * Tap-time handle on a feed media tile. The tile records its [LayoutCoordinates] on
 * placement (a field store — no state, no recomposition) and [mark]s itself as the origin
 * right before it fires its click, so bounds are resolved lazily at tap time and nothing new
 * flows through the feed's LazyColumn item parameters.
 */
class ViewerSourceAnchor internal constructor(
    private val shape: Shape,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) {
    internal var coordinates: LayoutCoordinates? = null

    /** True while the viewer is showing this tile's photo; read in the draw phase only. */
    internal var hidden by mutableStateOf(false)

    /** Record this tile as the grow-from origin for an imminent viewer open of [media]. */
    fun mark(media: TdMedia) {
        val coords = coordinates?.takeIf { it.isAttached } ?: return
        val outline = shape.createOutline(coords.size.toSize(), layoutDirection, density)
        val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        ViewerSourceRegistry.record(media, coords, radius, this)
    }
}

/** Anchor for a media tile clipped to [shape]; pair with [Modifier.viewerSource]. */
@Composable
fun rememberViewerSourceAnchor(shape: Shape): ViewerSourceAnchor {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(shape, density, layoutDirection) {
        ViewerSourceAnchor(shape, density, layoutDirection)
    }
}

/** Keeps [anchor] pointed at this node's layout so a tap can resolve its on-screen bounds. */
fun Modifier.viewerSource(anchor: ViewerSourceAnchor): Modifier =
    onPlaced { anchor.coordinates = it }.graphicsLayer { alpha = if (anchor.hidden) 0f else 1f }

/**
 * One-slot hand-off between a tapped tile and [MediaViewerController.open]. Main-thread only
 * (both sides run inside a click dispatch). The slot is cleared on every open, and an entry is
 * honoured only for a photo whose media matches the opened item and only within
 * [SOURCE_MAX_AGE_MS] of the tap — anything else opens exactly as before (no transform).
 */
internal object ViewerSourceRegistry {
    private class Pending(
        val media: TdMedia,
        val coordinates: LayoutCoordinates,
        val cornerRadiusPx: Float,
        val anchor: ViewerSourceAnchor,
        val atMs: Long,
    )

    private var pending: Pending? = null

    fun record(media: TdMedia, coordinates: LayoutCoordinates, cornerRadiusPx: Float, anchor: ViewerSourceAnchor) {
        pending = Pending(media, coordinates, cornerRadiusPx, anchor, SystemClock.uptimeMillis())
    }

    fun consume(item: AlbumItem?): ViewerTransitionSource? {
        val p = pending ?: return null
        pending = null
        if (item !is AlbumItem.Photo || item.media != p.media) return null
        if (SystemClock.uptimeMillis() - p.atMs > SOURCE_MAX_AGE_MS) return null
        if (!p.coordinates.isAttached) return null
        return ViewerTransitionSource(p.coordinates, p.cornerRadiusPx, p.anchor)
    }

    private const val SOURCE_MAX_AGE_MS = 1_000L
}
