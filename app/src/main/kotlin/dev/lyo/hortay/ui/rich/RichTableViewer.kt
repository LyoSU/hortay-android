package dev.lyo.hortay.ui.rich

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.rich.RichBlock

/**
 * Opens the self-contained fullscreen table viewer (see `RichTableViewerHost`). A compact
 * feed-preview table ([RichTable] in [RichMessageMode.FeedPreview]) asks the ambient controller
 * to escalate to the pan / zoom / copy surface. The default is `null`: without a host in the
 * tree the compact preview falls back to opening the full post, so the affordance is never a
 * dead end even before the host is mounted.
 */
internal fun interface RichTableViewer {
    fun open(table: RichBlock.Table)
}

internal val LocalTableViewer = staticCompositionLocalOf<RichTableViewer?> { null }
