package dev.lyo.hortay.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.media.WebmFrameCache

/** Draws an animated VP9+alpha WebM via the shared decode cache + clock. No SurfaceView, no
 *  ExoPlayer; the current frame is a plain ImageBitmap drawn with native alpha (srcOver).
 *  [animate]=false paints frame 0 (reduced-motion / off-focus). Renders nothing until [path] is
 *  non-null and decode completes — callers keep a static thumb underneath until [onFirstFrame].
 *  [widthPx]/[heightPx] are the laid-out pixel box; frames decode straight to that size, so
 *  non-square stickers keep their aspect ratio and the cache budget reflects the real footprint. */
@Composable
fun WebmAlphaImage(
    key: String,
    path: String?,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    onFirstFrame: () -> Unit = {},
) {
    if (path == null || widthPx <= 0 || heightPx <= 0) return
    val cache = LocalWebmFrameCache.current
    val clock = LocalWebmClock.current
    val decoded by remember(key, widthPx, heightPx, path) {
        cache.observe(WebmFrameCache.Key(key, widthPx, heightPx), path)
    }.collectAsStateWithLifecycle()

    val latestOnFirstFrame by rememberUpdatedState(onFirstFrame)
    LaunchedEffect(decoded != null) { if (decoded != null) latestOnFirstFrame() }
    val base = remember(decoded, animate) { clock.nowMs }
    val d = decoded ?: return
    val idx = if (animate) d.frameAt(clock.nowMs - base) else 0
    if (idx !in d.frames.indices) return
    val frame = d.frames[idx]

    Canvas(modifier) {
        drawIntoCanvas { c ->
            val sx = size.width / frame.width
            val sy = size.height / frame.height
            c.save()
            c.scale(sx, sy)
            c.drawImage(frame, Offset.Zero, Paint())
            c.restore()
        }
    }
}
