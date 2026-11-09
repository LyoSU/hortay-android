package dev.lyo.hortay.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.media.WebmFrameCache
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Max decoded frame side (px), aspect-preserving. Bounds per-sticker memory in [WebmFrameCache]. */
private const val MAX_DECODE_SIDE = 320

/** Re-decode attempts after an on-screen eviction, per composition of one sticker. */
private const val MAX_REDECODES = 3

/** Linear backoff step between eviction-driven re-decodes. */
private const val REDECODE_BACKOFF_MS = 250L

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
    // Cap the decoded frame size (aspect-preserving). A full-size sticker box is ~460 px on a dense
    // screen; times a multi-second loop of RGBA frames that's tens of MB for ONE sticker, which
    // thrashes the shared cache. Decoding at <=320 px keeps per-sticker memory bounded; the Canvas
    // upscales to the laid-out box (stickers are forgiving and emoji are smaller than the cap).
    val (decW, decH) = remember(widthPx, heightPx) {
        val longest = maxOf(widthPx, heightPx)
        if (longest <= MAX_DECODE_SIDE) widthPx to heightPx
        else {
            val s = MAX_DECODE_SIDE.toFloat() / longest
            (widthPx * s).roundToInt().coerceAtLeast(1) to (heightPx * s).roundToInt().coerceAtLeast(1)
        }
    }
    // Bumped when the cache evicts our frames while we're still composed: the evicted flow is
    // pruned and nulled, so without a re-observe the sticker would stay blank until it scrolled
    // off and back. Capped + backed off so two oversized on-screen loops that evict each other
    // can't spin a decode loop forever.
    var generation by remember(key, decW, decH, path) { mutableIntStateOf(0) }
    var sawFrame by remember(key, decW, decH, path) { mutableStateOf(false) }
    val decoded by remember(key, decW, decH, path, generation) {
        cache.observe(WebmFrameCache.Key(key, decW, decH), path)
    }.collectAsStateWithLifecycle()

    val latestOnFirstFrame by rememberUpdatedState(onFirstFrame)
    LaunchedEffect(decoded != null) {
        if (decoded != null) {
            sawFrame = true
            latestOnFirstFrame()
        } else if (sawFrame && generation < MAX_REDECODES) {
            delay(REDECODE_BACKOFF_MS * (generation + 1))
            generation++
        }
    }
    // Only an animating sticker keeps the shared clock ticking; a static frame-0 paint needs none.
    DisposableEffect(clock, animate) {
        if (animate) clock.acquire()
        onDispose { if (animate) clock.release() }
    }
    val d = decoded ?: return
    val paint = remember { Paint() }
    // Elapsed-time origin for this loop, captured on the first draw. A plain holder (not state) so
    // the per-frame clock read below happens in the draw phase only and never recomposes.
    val origin = remember(d, animate) { longArrayOf(-1L) }

    Canvas(modifier) {
        val now = if (animate) clock.nowMs else 0L
        if (origin[0] < 0L) origin[0] = now
        val idx = if (animate) d.frameAt(now - origin[0]) else 0
        if (idx !in d.frames.indices) return@Canvas
        val frame = d.frames[idx]
        drawIntoCanvas { c ->
            val sx = size.width / frame.width
            val sy = size.height / frame.height
            c.save()
            c.scale(sx, sy)
            c.drawImage(frame, Offset.Zero, paint)
            c.restore()
        }
    }
}
