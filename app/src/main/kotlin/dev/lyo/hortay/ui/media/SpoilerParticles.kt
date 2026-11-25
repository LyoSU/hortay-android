package dev.lyo.hortay.ui.media

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.lyo.hortay.ui.util.rememberReducedMotion
import java.nio.ByteBuffer

/** Which shared particle tile a spoiler samples. */
internal enum class SpoilerTileKind { Text, Media }

/**
 * The one particle engine behind every spoiler cover on screen — text runs, photos, videos, album
 * cells, the channel media grid.
 *
 * Telegram's own design, ported (DrKLO/Telegram `spoilers/SpoilerEffectBitmapFactory.java` for
 * text, Telegram Web A `lib/gpuCanvas` for both kinds): ONE simulation per kind fills a small
 * seamless ALPHA_8 tile, and each spoiler is a single `drawPath` / `drawRect` with a repeating
 * [BitmapShader] of that tile, tinted by a `SRC_IN` colour filter. N spoilers on screen therefore
 * cost N shader-filled draw ops — never N simulations, N timers or N particle loops.
 *
 * Frame budget:
 *  * **Shared clock, on demand.** A single [Choreographer] callback runs only while some draw
 *    asked for it in the last [ACTIVE_WINDOW_NS] ([markAnimating] / [markRevealing] are called
 *    from draw). A spoiler that scrolls away, an app in the background, or a feed without
 *    spoilers stops drawing, so the clock stops by itself — nothing redraws while idle.
 *  * **Off the main thread.** Stepping and rasterising (`Canvas.drawPoints` in
 *    [SPOILER_ALPHA_LEVELS] batches into a private bitmap) runs on one background
 *    [HandlerThread] at [TILE_FPS] like Telegram's factory; the main thread only memcpy's the
 *    finished pixels into the displayed bitmap and bumps [tileGeneration], which invalidates the
 *    DRAW phase of the readers (no recomposition). The copy happens on the UI thread, which never
 *    overlaps the render thread's texture upload during frame sync.
 *  * **No per-frame allocation.** Particle state lives in primitive arrays, point batches grow
 *    only when a level overflows, one reusable [Paint] per tile and per consumer.
 *  * **Capped.** [TEXT_MAX_PARTICLES] + [MEDIA_MAX_PARTICLES] particles in total, whatever the
 *    number of spoilers.
 *
 * Text spoilers get Telegram's dense small particles; media spoilers the larger curl-noise flow
 * of `SpoilerEffect2` (see [SpoilerParticleSim]).
 */
internal object SpoilerParticles {
    private val tiles = arrayOfNulls<Tile>(SpoilerTileKind.entries.size)
    private val lastAnimateNs = LongArray(SpoilerTileKind.entries.size)
    private var lastRevealNs = 0L
    private var scheduled = false
    private var jobInFlight = false
    private var jobMask = 0
    private var lastJobNs = 0L

    private val generation = mutableIntStateOf(0)
    private val frameTime = mutableLongStateOf(0L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy {
        val thread = HandlerThread(
            "SpoilerParticles",
            Process.THREAD_PRIORITY_DEFAULT + 3 * Process.THREAD_PRIORITY_LESS_FAVORABLE,
        )
        thread.start()
        Handler(thread.looper)
    }
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    private val jobRunnable = Runnable { runJob() }
    private val publishRunnable = Runnable { publish() }

    /** Monotonic milliseconds on the same base as [frameTimeMs] (`System.nanoTime`). */
    fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** Draw-phase read: changes whenever a tile's pixels change, so readers redraw with it. */
    fun tileGeneration(): Int = generation.intValue

    /** Draw-phase read: the vsync time, ticking every frame while a reveal is running. */
    fun frameTimeMs(): Long = frameTime.longValue

    /** Draw phase: a visible, covered spoiler of [kind] wants its particles to move. */
    fun markAnimating(kind: SpoilerTileKind) {
        lastAnimateNs[kind.ordinal] = System.nanoTime()
        ensureScheduled()
    }

    /** Draw phase: a reveal ripple is on screen and needs a per-frame [frameTimeMs]. */
    fun markRevealing() {
        lastRevealNs = System.nanoTime()
        ensureScheduled()
    }

    /** The displayed tile of [kind]; created and rasterised once on first use (main thread). */
    fun tile(kind: SpoilerTileKind): Bitmap = tileOf(kind).front

    private fun tileOf(kind: SpoilerTileKind): Tile =
        tiles[kind.ordinal] ?: createTile(kind).also { tiles[kind.ordinal] = it }

    private fun ensureScheduled() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(frameNs: Long) {
        scheduled = false
        val now = System.nanoTime()
        val revealing = now - lastRevealNs < ACTIVE_WINDOW_NS
        if (revealing) frameTime.longValue = frameNs / 1_000_000
        var wanted = 0
        for (k in lastAnimateNs.indices) {
            if (lastAnimateNs[k] != 0L && now - lastAnimateNs[k] < ACTIVE_WINDOW_NS) wanted = wanted or (1 shl k)
        }
        if (wanted != 0 && !jobInFlight && frameNs - lastJobNs >= TILE_INTERVAL_NS) startJob(wanted, frameNs)
        if (wanted != 0 || revealing) ensureScheduled()
    }

    private fun startJob(mask: Int, frameNs: Long) {
        for (k in tiles.indices) {
            if (mask and (1 shl k) == 0) continue
            val tile = tileOf(SpoilerTileKind.entries[k])
            tile.pendingDt = if (tile.lastStepNs == 0L) {
                DEFAULT_DT_S
            } else {
                ((frameNs - tile.lastStepNs) / 1e9f).coerceIn(0f, MAX_DT_S)
            }
            tile.lastStepNs = frameNs
        }
        jobMask = mask
        jobInFlight = true
        lastJobNs = frameNs
        worker.post(jobRunnable)
    }

    private fun runJob() {
        val mask = jobMask
        for (k in tiles.indices) {
            if (mask and (1 shl k) != 0) tiles[k]?.stepAndRasterise()
        }
        mainHandler.post(publishRunnable)
    }

    private fun publish() {
        val mask = jobMask
        for (k in tiles.indices) {
            if (mask and (1 shl k) != 0) tiles[k]?.publish()
        }
        jobInFlight = false
        generation.intValue++
    }

    private fun createTile(kind: SpoilerTileKind): Tile {
        val metrics = Resources.getSystem().displayMetrics
        val density = metrics.density
        val seed = System.nanoTime().toInt()
        return when (kind) {
            SpoilerTileKind.Text -> {
                // `SpoilerEffectBitmapFactory`: half the short screen side, 80..150 dp.
                val half = minOf(metrics.widthPixels, metrics.heightPixels) * 0.5f
                val sizePx = half.coerceIn(TEXT_TILE_MIN_DP * density, TEXT_TILE_MAX_DP * density).toInt()
                val sizeDp = sizePx / density
                val count = (sizeDp * sizeDp * TEXT_PARTICLES_PER_DP2).toInt().coerceIn(1, TEXT_MAX_PARTICLES)
                Tile(
                    sim = TextParticleSim(
                        tileSizePx = sizePx.toFloat(),
                        count = count,
                        speedMinPx = TEXT_SPEED_MIN_DP * density,
                        speedMaxPx = TEXT_SPEED_MAX_DP * density,
                        seed = seed,
                    ),
                    sizePx = sizePx,
                    strokePx = TEXT_PARTICLE_DP * density,
                )
            }
            SpoilerTileKind.Media -> {
                val sizePx = minOf(MEDIA_TILE_DP * density, MEDIA_TILE_MAX_PX).toInt()
                val sizeDp = sizePx / density
                val count = (sizeDp * sizeDp * MEDIA_PARTICLES_PER_DP2).toInt().coerceIn(1, MEDIA_MAX_PARTICLES)
                Tile(
                    sim = MediaParticleSim(
                        tileSizePx = sizePx.toFloat(),
                        count = count,
                        refSizePx = MEDIA_REF_DP * density,
                        seed = seed,
                    ),
                    sizePx = sizePx,
                    strokePx = MEDIA_PARTICLE_DP * density,
                )
            }
        }
    }

    /**
     * One simulated tile. [sim], [scratch] and the point batches are touched only by the worker
     * after construction; [front] only on the main thread. [buffer] is handed from the worker to
     * the main thread through the handler queue, and the next job starts only after [publish].
     */
    private class Tile(private val sim: SpoilerParticleSim, sizePx: Int, strokePx: Float) {
        val front: Bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ALPHA_8)
        private val scratch = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ALPHA_8)
        private val canvas = Canvas(scratch)
        private val buffer = ByteBuffer.allocate(scratch.byteCount)
        private val batches = Array(SPOILER_ALPHA_LEVELS) { FloatArray(INITIAL_BATCH_FLOATS) }
        private val counts = IntArray(SPOILER_ALPHA_LEVELS)
        private val halfStroke = strokePx * 0.5f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = strokePx
            color = AndroidColor.BLACK
        }
        var pendingDt = 0f
        var lastStepNs = 0L

        init {
            rasterise()
            publish()
        }

        fun stepAndRasterise() {
            sim.step(pendingDt)
            rasterise()
        }

        private fun rasterise() {
            scratch.eraseColor(AndroidColor.TRANSPARENT)
            sim.fill(batches, counts, halfStroke)
            for (level in 0 until SPOILER_ALPHA_LEVELS) {
                val n = counts[level]
                if (n == 0) continue
                paint.alpha = (levelAlpha(level) * 255f + 0.5f).toInt()
                canvas.drawPoints(batches[level], 0, n, paint)
            }
            buffer.rewind()
            scratch.copyPixelsToBuffer(buffer)
        }

        fun publish() {
            buffer.rewind()
            front.copyPixelsFromBuffer(buffer)
        }
    }

    private const val TILE_FPS = 30
    private const val TILE_INTERVAL_NS = 1_000_000_000L / TILE_FPS - 2_000_000L
    // Longer than a tile interval plus a slow job, so a spoiler redrawn once per new tile keeps
    // the clock alive; short enough that it stops a few frames after the last spoiler leaves.
    private const val ACTIVE_WINDOW_NS = 120_000_000L
    private const val DEFAULT_DT_S = 1f / TILE_FPS
    private const val MAX_DT_S = 0.1f
    private const val INITIAL_BATCH_FLOATS = 512

    // Text — `SpoilerEffect` / `SpoilerEffectBitmapFactory`.
    private const val TEXT_TILE_MIN_DP = 80f
    private const val TEXT_TILE_MAX_DP = 150f
    private const val TEXT_PARTICLES_PER_DP2 = 0.14f
    private const val TEXT_MAX_PARTICLES = 3600
    private const val TEXT_PARTICLE_DP = 1.3f
    // `4 + rand * 6` px per 500 ms at xxhdpi.
    private const val TEXT_SPEED_MIN_DP = 2.7f
    private const val TEXT_SPEED_MAX_DP = 6.7f

    // Media — `SpoilerEffect2` / Web A `TILE_CONFIG`.
    private const val MEDIA_TILE_DP = 256f
    private const val MEDIA_TILE_MAX_PX = 640f
    private const val MEDIA_PARTICLES_PER_DP2 = 0.03f
    private const val MEDIA_MAX_PARTICLES = 2400
    private const val MEDIA_PARTICLE_DP = 1.6f
    private const val MEDIA_REF_DP = 360f
}

/**
 * Per-spoiler paint over a shared tile: its own [BitmapShader] (offset by [seed] so neighbouring
 * spoilers don't show the same pattern) and a cached `SRC_IN` tint. Draw-thread only.
 */
internal class SpoilerTilePaint(val kind: SpoilerTileKind, seed: Int) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tile: Bitmap? = null
    private var tint = 0
    private val offsetX = ((seed * 0x9E3779B1L.toInt()) ushr 16) / 65536f
    private val offsetY = ((seed * 0x85EBCA6BL.toInt()) ushr 16) / 65536f

    fun prepare(color: Int, alpha: Float): Paint {
        val current = SpoilerParticles.tile(kind)
        if (current !== tile) {
            tile = current
            paint.shader = BitmapShader(current, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setTranslate(offsetX * current.width, offsetY * current.height) })
            }
        }
        if (color != tint || paint.colorFilter == null) {
            tint = color
            paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        paint.alpha = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return paint
    }
}

/**
 * Whether covered spoilers may animate: never under reduced motion (static cover), and only while
 * the screen is RESUMED. Read in the draw phase, so a lifecycle change redraws without recomposing.
 */
@Stable
internal class SpoilerMotion(private val lifecycle: State<Lifecycle.State>, val reduced: Boolean) {
    val animate: Boolean get() = !reduced && lifecycle.value.isAtLeast(Lifecycle.State.RESUMED)
}

@Composable
internal fun rememberSpoilerMotion(): SpoilerMotion {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val reduced = rememberReducedMotion()
    return remember(lifecycle, reduced) { SpoilerMotion(lifecycle, reduced) }
}

/**
 * Draw phase: fills [path] (or the whole draw area) with the particle tile of [paint], tinted
 * [color] at [alpha]. Subscribes this draw to tile updates and keeps the shared clock running
 * while [motion] allows.
 */
internal fun DrawScope.drawSpoilerParticles(
    paint: SpoilerTilePaint,
    motion: SpoilerMotion,
    color: Color,
    alpha: Float = 1f,
    path: Path? = null,
) {
    SpoilerParticles.tileGeneration()
    if (motion.animate) SpoilerParticles.markAnimating(paint.kind)
    if (alpha <= 0f) return
    val p = paint.prepare(color.toArgb(), alpha)
    val canvas = drawContext.canvas.nativeCanvas
    if (path != null) canvas.drawPath(path, p) else canvas.drawRect(0f, 0f, size.width, size.height, p)
}
