package dev.lyo.hortay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/**
 * Where the finger that opened an [AnchoredContextMenu] goes after the menu is up, in screen
 * coordinates. Fed by the anchor's own pointer observer ([contextMenuAnchor]): Android keeps
 * delivering a gesture to the window it started in, so the long-press that opens the menu
 * never reaches the menu's dialog window — the host window keeps the stream until the
 * finger lifts, and the anchor, which already watches it on the Initial pass, forwards it.
 * The dialog only sees gestures that start after it is shown, so the two never overlap.
 */
internal interface ContextMenuDragSink {
    fun move(screen: Offset)

    /** The finger lifted (a real ACTION_UP) at [screen]. */
    fun release(screen: Offset)

    /** The gesture ended without a lift (ACTION_CANCEL, the anchor leaving). */
    fun cancel()
}

/**
 * Press-drag-release selection for the menu's items: while the opening finger is still
 * down, the item under it is [hovered] (a light tick on every change), and lifting the
 * finger over an item runs that item's click — which goes through `ContextMenuScope.close`
 * like a tap. Lifting anywhere else ends the drag and leaves the menu open for ordinary
 * taps. One drag per menu: after a lift or a cancel the sink ignores everything, so a later
 * tap can never fire twice. Items keep their normal `clickable` for taps, keyboard and
 * accessibility; this only adds a second way in.
 */
@Stable
internal class ContextMenuSelection(
    private val touchSlop: Float,
    private val onHoverChanged: () -> Unit,
) : ContextMenuDragSink {

    /** The dialog's root, the space items are hit-tested in. */
    var root: LayoutCoordinates? = null

    var hovered: Any? by mutableStateOf(null)
        private set

    private class Entry(var coordinates: LayoutCoordinates?, var onClick: () -> Unit)

    private val entries = LinkedHashMap<Any, Entry>()
    private var tracking = true

    /** Where the held finger was when the menu first heard from it. */
    private var origin: Offset? = null

    /**
     * True once the held finger has travelled past [touchSlop] since the menu opened. Until
     * then nothing is hovered and a lift selects nothing: the menu opens around the finger,
     * so whatever item happens to land under a still finger must not fire when it lifts —
     * only a deliberate slide onto an item selects it, as in iOS and Telegram.
     */
    private var armed = false

    fun register(key: Any, onClick: () -> Unit) {
        entries.getOrPut(key) { Entry(null, onClick) }.onClick = onClick
    }

    fun place(key: Any, coordinates: LayoutCoordinates) {
        // Placement can land before the registering effect runs; keep the coordinates.
        entries.getOrPut(key) { Entry(coordinates, {}) }.coordinates = coordinates
    }

    fun unregister(key: Any) {
        entries.remove(key)
        if (hovered === key) hovered = null
    }

    override fun move(screen: Offset) {
        if (!tracking || !arm(screen)) return
        val hit = hitTest(screen)
        if (hit !== hovered) {
            hovered = hit
            if (hit != null) onHoverChanged()
        }
    }

    override fun release(screen: Offset) {
        if (!tracking) return
        tracking = false
        val hit = if (arm(screen)) hitTest(screen) else null
        hovered = null
        if (hit != null) entries[hit]?.onClick?.invoke()
    }

    override fun cancel() {
        tracking = false
        hovered = null
    }

    private fun arm(screen: Offset): Boolean {
        if (armed) return true
        val start = origin ?: screen.also { origin = it }
        armed = (screen - start).getDistance() > touchSlop
        return armed
    }

    private fun hitTest(screen: Offset): Any? {
        val root = root?.takeIf { it.isAttached } ?: return null
        val local = root.screenToLocal(screen)
        return entries.entries.firstOrNull { (_, entry) ->
            val coords = entry.coordinates?.takeIf { it.isAttached } ?: return@firstOrNull false
            root.localBoundingBoxOf(coords).contains(local)
        }?.key
    }
}

internal val LocalContextMenuSelection = staticCompositionLocalOf<ContextMenuSelection?> { null }

/**
 * Registers the calling item with the menu's [ContextMenuSelection] (if any) and returns its
 * key; the item reports its placement with [ContextMenuSelection.place] and highlights
 * while [ContextMenuSelection.hovered] is that key.
 */
@Composable
internal fun rememberMenuSelectionKey(selection: ContextMenuSelection?, onClick: () -> Unit): Any {
    val key = remember { Any() }
    val currentOnClick by rememberUpdatedState(onClick)
    if (selection != null) {
        DisposableEffect(selection, key) {
            selection.register(key) { currentOnClick() }
            onDispose { selection.unregister(key) }
        }
    }
    return key
}
