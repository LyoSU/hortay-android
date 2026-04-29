package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PostContent

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
            items = vs.items,
            initialIndex = vs.index,
            onDismiss = { state = null },
        )
    }
}

private data class ViewerState(val items: List<AlbumItem>, val index: Int)
