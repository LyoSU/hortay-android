package dev.lyo.hortay.ui.rich

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichPlainText
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Opens the self-contained fullscreen table viewer ([RichTableViewerHost]). A compact
 * feed-preview table ([RichTable] in [RichMessageMode.FeedPreview]) asks the ambient controller
 * to escalate to the pan / zoom / copy surface. The default is `null`: without a host in the
 * tree the compact preview falls back to opening the full post, so the affordance is never a
 * dead end even before the host is mounted.
 */
internal fun interface RichTableViewer {
    fun open(table: RichBlock.Table)
}

internal val LocalTableViewer = staticCompositionLocalOf<RichTableViewer?> { null }

/**
 * Provides a [RichTableViewer] to [content] and hosts its overlay. Mounted once around a rich
 * message body (see [RichMessageBody]); the actual surface is a [Dialog], so it escapes to the
 * window regardless of where in the feed the host sits. A single open-table state means only one
 * viewer is ever composed at a time.
 */
@Composable
internal fun RichTableViewerHost(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf<RichBlock.Table?>(null) }
    val controller = remember { RichTableViewer { open = it } }
    CompositionLocalProvider(LocalTableViewer provides controller) {
        content()
    }
    open?.let { table ->
        RichTableViewerDialog(table = table, onDismiss = { open = null })
    }
}

/** Zoom clamp for the viewer — 0.5× (overview) to 3× (fine print). */
private const val VIEWER_MIN_ZOOM = 0.5f
private const val VIEWER_MAX_ZOOM = 3f

/** Effectively-unbounded column cap so the viewer shows columns at their natural width and the
 *  user pans / zooms, instead of the inline table's 60%-viewport wrap. */
private val VIEWER_MAX_COLUMN_WIDTH = 4000.dp

/**
 * Fullscreen, pan- and pinch-zoomable rendering of a single [RichBlock.Table]. Mirrors the media
 * viewer's presentation idiom — a borderless [Dialog] whose back press / scrim dismiss both route
 * through [onDismiss] (the media viewer relies on `Dialog` back too, so there is no separate
 * [androidx.activity.compose.PredictiveBackHandler] to mirror). The leading header rows stick to
 * the top of the table area as the body pans up under them; a sticky first column is intentionally
 * NOT shipped — it would need a second independently-translated overlay whose zoom origin fights
 * the sticky header's, so the header (the more valuable anchor while reading down a long table)
 * wins. The top bar copies the whole table as TSV to the clipboard.
 */
@Composable
private fun RichTableViewerDialog(table: RichBlock.Table, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val placements = remember(table) { buildPlacements(table) }
        val density = LocalDensity.current

        var scale by remember(table) { mutableStateOf(1f) }
        var offset by remember(table) { mutableStateOf(Offset.Zero) }
        var containerSize by remember { mutableStateOf(IntSize.Zero) }
        var contentSize by remember { mutableStateOf(IntSize.Zero) }
        var headerHeightPx by remember(table) { mutableIntStateOf(0) }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(VIEWER_MIN_ZOOM, VIEWER_MAX_ZOOM)
            offset = clampTableOffset(offset + panChange, scale, contentSize, containerSize)
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                RichTableViewerBar(table = table, onClose = onDismiss)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .onSizeChanged { containerSize = it }
                        .transformable(transformState),
                ) {
                    RichTableGridLayout(
                        placements = placements,
                        isStriped = table.isStriped,
                        maxColumnWidth = VIEWER_MAX_COLUMN_WIDTH,
                        onHeaderHeight = { if (it != headerHeightPx) headerHeightPx = it },
                        modifier = Modifier
                            .semantics {
                                collectionInfo = CollectionInfo(
                                    rowCount = placements.rows,
                                    columnCount = placements.columns,
                                )
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .onSizeChanged { contentSize = it },
                    )

                    // Sticky header band: a second render of the grid pinned to the table area's
                    // top, following the body's horizontal pan + zoom but never its vertical pan,
                    // clipped to the (scaled) header height. Shown only once the body has panned up
                    // past its own header so the real header is off-screen. A solid backing keeps
                    // the panning body from bleeding through, and the duplicate is hidden from
                    // TalkBack so the grid isn't announced twice.
                    if (headerHeightPx > 0 && offset.y < 0f) {
                        val bandHeight = with(density) { (headerHeightPx * scale).toDp() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(bandHeight)
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.surface)
                                .clearAndSetSemantics {},
                        ) {
                            RichTableGridLayout(
                                placements = placements,
                                isStriped = table.isStriped,
                                maxColumnWidth = VIEWER_MAX_COLUMN_WIDTH,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = 0f
                                    transformOrigin = TransformOrigin(0f, 0f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichTableViewerBar(table: RichBlock.Table, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.post_copied_toast)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Symbol(name = "close", contentDescription = stringResource(R.string.action_close))
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(tableToTsv(table)))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        ) {
            Symbol(name = "content_copy", contentDescription = stringResource(R.string.rich_table_copy))
        }
    }
}

/** Top-left-anchored pan clamp: the content's top-left never leaves 0, and the far / bottom edge
 *  can't be dragged inside the viewport when the (scaled) content is larger than it. Content
 *  smaller than the viewport pins at the top-left. */
private fun clampTableOffset(raw: Offset, scale: Float, content: IntSize, container: IntSize): Offset {
    val scaledW = content.width * scale
    val scaledH = content.height * scale
    val minX = minOf(0f, container.width - scaledW)
    val minY = minOf(0f, container.height - scaledH)
    return Offset(raw.x.coerceIn(minX, 0f), raw.y.coerceIn(minY, 0f))
}

/**
 * Serializes a [RichBlock.Table] to tab-separated values for the clipboard: rows joined by `\n`,
 * physical columns by `\t`. The placement grid ([buildPlacements]) is expanded to a rectangle so
 * every row emits exactly `columns` fields and the TSV stays column-aligned. Span rule: a
 * spanning cell's text lands only in its anchor column; every column a colspan / rowspan COVERS
 * emits a BLANK field (not a repeat), which keeps a spreadsheet paste faithful to the visual grid.
 * Newlines and tabs inside a cell are flattened to spaces so they can't break the row / column
 * structure. Returns an empty string for a table with no cells.
 */
internal fun tableToTsv(table: RichBlock.Table): String {
    val placements = buildPlacements(table)
    val rows = placements.rows
    val cols = placements.columns
    if (rows <= 0 || cols <= 0) return ""
    val grid = Array(rows) { arrayOfNulls<String>(cols) }
    placements.cells.forEach { span ->
        if (span.row < rows && span.col < cols) {
            grid[span.row][span.col] = span.cell.text
                ?.let { RichPlainText.of(it) }
                ?.replace('\n', ' ')
                ?.replace('\t', ' ')
                ?.trim()
                .orEmpty()
        }
    }
    return (0 until rows).joinToString("\n") { r ->
        (0 until cols).joinToString("\t") { c -> grid[r][c].orEmpty() }
    }
}
