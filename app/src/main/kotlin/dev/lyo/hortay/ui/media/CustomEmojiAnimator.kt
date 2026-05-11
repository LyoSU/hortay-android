package dev.lyo.hortay.ui.media

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieDrawable
import kotlin.math.floor
import kotlin.math.max
import android.graphics.Canvas as AndroidCanvas

/**
 * Process-singleton frame driver for inline Telegram custom-emoji TGS playback.
 *
 * # Architecture (v2: background rasterisation)
 *
 * v1 (shared drawable + bitmap, UI-thread rendering) measured 432 renders/sec under a
 * 30-active-emoji scroll, each Lottie layer walk ~3-4 ms = ~120-160 ms of UI-thread
 * work per second of wall clock. That's 12-16% of the UI thread's budget bled to
 * rasterisation alone, and ALL of it landed inside a single [Choreographer.FrameCallback]
 * tick — so the UI thread missed vsync repeatedly (measured: 28% jank, 99th-percentile
 * 150 ms frames).
 *
 * v2 splits the work:
 *
 *   • **UI thread** — frame callback advances progress, decides whether the snapped
 *     frame index changed, and if so posts a render task to a background thread.
 *     Per-tick work: hashmap lookups + arithmetic. Microseconds.
 *   • **Background `HandlerThread`** — does `LottieDrawable.draw(canvas)` into a back
 *     buffer, then posts a completion back to main. Single thread (FIFO queue), one
 *     entry at a time. If the same entry needs a re-render before the BG completes,
 *     we coalesce — store the latest snappedProgress in `pendingProgress` and let the
 *     in-flight render finish first.
 *   • **Main-thread completion** — atomically swaps `bitmapFront` ↔ `bitmapBack`,
 *     publishes the new progress state. Consumers' `Canvas {}` draw scopes
 *     subscribed to `progress()` are invalidated → redraw → blit the fresh
 *     `bitmapFront`. The blit is a GPU-cheap texture copy, no layer walk.
 *
 * Net effect on a 30-active-emoji scroll:
 *   • UI-thread rasterisation cost: 0.
 *   • Background-thread cost: bounded by single-thread throughput (~120 ms / sweep =
 *     ~8 full sweeps / sec = each emoji gets a fresh frame every ~125 ms). Animation
 *     visibly slows under heavy load instead of janking the UI — exactly Telegram-
 *     Android's `lottieCacheGenerateQueue` trade-off.
 *
 * # Sharing
 *
 *   1. One [LottieDrawable] per (id, fps target) — shared composition data.
 *   2. Two [Bitmap]s per entry (double-buffer for thread handoff). Front buffer is
 *      read by N consumer Canvas blits; back buffer is exclusively the BG thread's.
 *   3. One [MutableFloatState] progress per entry — read in consumers' draw scope,
 *      so reads are tracked as redraw invalidations (not recompositions).
 *
 * One process-wide [Choreographer.FrameCallback]. Posts only while at least one entry
 * is "attached" (refCount > 0). [ProcessLifecycleOwner] pauses the master clock on
 * STOP. The background HandlerThread runs for the process lifetime.
 *
 * # FPS clamp
 *
 * Inline emoji render at ~24 dp. Driving them at composition-native 60 fps wastes
 * cycles. We quantise the master clock to `Fps.target` (20 Hz for inline). When the
 * BG render queue gets saturated, [pendingProgress] coalescing means we always render
 * the most-recent target frame for an entry — older snapshots are dropped.
 *
 * # Repaint
 *
 * Monochrome emojis (`needsRepainting = true`) take their tint via the consumer's
 * `Paint.colorFilter` on `drawBitmap` — per-consumer tint, one bitmap source.
 *
 * # Thread model
 *
 *   • **UI thread:** [acquire] / [release] / [reportSize] (via Handle) / frame
 *     callback / completion handler / consumer draws.
 *   • **BG thread (`CustomEmojiRenderer`):** [LottieDrawable.progress] writes +
 *     [LottieDrawable.draw] + `Bitmap.eraseColor`. The drawable, back buffer, and
 *     its canvas are this thread's exclusive state between `dispatchRender` (posted
 *     from UI) and `onRenderComplete` (posted back to UI).
 *
 * Memory: per entry ~ `2 × pixelSize² × 4` bytes for ARGB_8888 buffers, plus Lottie
 * drawable's internal graph. At a typical 80 px inline emoji, ~50 KB / entry; the
 * `entries` map is bounded indirectly by upstream LottieCompositionStore eviction
 * (32 entries) + LottieUrlStore (64 entries).
 */
class CustomEmojiAnimator(
    private val processLifecycle: Lifecycle,
    private val clock: () -> Long = { System.nanoTime() },
    private val choreographer: () -> Choreographer = Choreographer::getInstance,
) {

    enum class Fps(val target: Int) {
        /**
         * Inline emoji embedded in body text (~24 dp). 12 Hz quantisation —
         * Telegram-Android's `CACHE_TYPE_EMOJI_STATUS` baseline; below the eye's
         * resolve threshold at this size, and the lower the FPS the less rendering
         * work the BG thread does and the less state-mutation/recomposition pressure
         * the UI thread takes on render-completion. Measured: dropping from 20 → 12
         * cuts BG renders/sec roughly in half on a 30-active-emoji scroll.
         */
        Inline(target = 12),
        /** Custom-emoji in reaction chip (~18 dp). Slightly higher because reaction chips
         *  draw more attention; still well below native composition fps. */
        Reaction(target = 18),
    }

    @Immutable
    class Handle internal constructor(
        private val animator: CustomEmojiAnimator,
        private val key: EntryKey,
        val drawable: LottieDrawable,
        /**
         * Lambda that reads the shared progress state. Consumers should call this
         * inside a `Canvas { ... }` / `Modifier.drawBehind { ... }` lambda so the
         * read subscribes to draw-only invalidations rather than triggering a full
         * recomposition.
         */
        val progress: () -> Float,
    ) {
        /**
         * Current front-buffer bitmap, or null until the first BG render completes.
         * Read inside Compose's draw scope; no synchronisation needed because the
         * field is only written on the main thread (during render completion).
         */
        val bitmap: Bitmap?
            get() = animator.entries[key]?.bitmapFront

        /**
         * Inform the animator that a consumer wants to draw this emoji at [pxSize]
         * pixels (square — emojis are always square). Call from a `Canvas {}` draw
         * scope after measuring `size`. The animator expands its cached double-
         * buffer if needed and schedules an immediate render so the consumer's
         * next draw isn't blank.
         */
        fun reportSize(pxSize: Int) {
            if (pxSize <= 0) return
            animator.reportSize(key, pxSize)
        }
    }

    internal data class EntryKey(val id: Long, val fpsTarget: Int)

    internal class Entry(
        val drawable: LottieDrawable,
        val durationNanos: Long,
        val targetFrameStepNanos: Long,
        val progress: MutableFloatState,
        var refCount: Int = 0,
        var startedAtNanos: Long = 0L,
        var lastSnappedFrameIndex: Int = -1,
        // Double-buffered bitmaps. `bitmapFront` is what consumers blit; `bitmapBack`
        // is what the BG thread writes into. On render completion (main thread) we
        // swap them so the BG thread's freshly painted buffer becomes the next
        // front. Two buffers means UI consumers can blit while BG paints a new frame
        // in parallel, with no shared mutable bitmap state.
        @Volatile var bitmapFront: Bitmap? = null,
        var bitmapBack: Bitmap? = null,
        var bitmapBackCanvas: AndroidCanvas? = null,
        var bitmapSizePx: Int = 0,
        // Coalescing state for BG render queue. While `renderInFlight = true` we
        // don't enqueue a second task for the same entry; instead the latest
        // snapped progress to render is stashed in `pendingProgress`, which the
        // completion handler picks up after the in-flight render finishes.
        var renderInFlight: Boolean = false,
        var pendingProgress: Float = Float.NaN,
    )

    /**
     * Entry cache. We need access-ordered iteration on release+overflow to evict the
     * least-recently-used idle entry — [LinkedHashMap] with `accessOrder = true` gives
     * that with the same O(1) get/put as HashMap. Cap is sized to fit a busy emoji-
     * heavy session without thrashing (each entry holds two ARGB_8888 bitmaps +
     * LottieDrawable internal graph, ~150 KB; 64 cap ≈ 10 MB ceiling).
     */
    private val entries = object : LinkedHashMap<EntryKey, Entry>(/* cap */ 16, 0.75f, /* accessOrder */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EntryKey, Entry>?): Boolean {
            if (eldest == null || size <= MAX_ENTRIES) return false
            val entry = eldest.value
            // Never evict an entry whose composables are still mounted — Compose's
            // bitmap references would dangle and the next blit would draw a recycled
            // bitmap (Android logs a "Canvas: trying to use a recycled bitmap"
            // crash). Walk the access order past in-use entries by returning false
            // here; the next put will retry with the new eldest.
            if (entry.refCount > 0) return false
            // Idle entry — recycle its bitmaps and drop. Safe: refCount == 0 means
            // no consumer is reading bitmapFront.
            entry.bitmapFront?.recycle()
            entry.bitmapBack?.recycle()
            return true
        }
    }
    private val activeKeys = HashSet<EntryKey>()
    private var frameCallbackPosted = false
    private var paused = false

    private val renderThread = HandlerThread("CustomEmojiRenderer", Thread.NORM_PRIORITY - 1).apply {
        // Slightly below normal — we don't want rasterisation to preempt the UI
        // thread if the scheduler ever colocates them on the same core. The whole
        // point of this thread is to be background-class work.
        start()
    }
    private val renderHandler = Handler(renderThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Debug instrumentation — counters reset every second, emits one log line so we
    // can confirm what the animator is actually doing under jank. Cheap (a few int
    // increments per tick); leave wired until performance is settled.
    private var dbgFrameCallbackCount = 0
    private var dbgAdvanceCount = 0
    private var dbgRenderScheduled = 0
    private var dbgRenderCompleted = 0
    private var dbgRenderDropped = 0
    private var dbgLastStatsAtNanos = 0L

    private companion object {
        const val MAX_ENTRIES = 64
    }

    init {
        processLifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                paused = false
                val now = clock()
                for (key in activeKeys) {
                    val entry = entries[key] ?: continue
                    entry.startedAtNanos = now
                    entry.lastSnappedFrameIndex = -1
                }
                postFrameIfNeeded()
            }
            override fun onStop(owner: LifecycleOwner) {
                paused = true
            }
        })
    }

    fun acquire(customEmojiId: Long, composition: LottieComposition, fps: Fps): Handle {
        val key = EntryKey(customEmojiId, fps.target)
        val entry = entries[key] ?: createEntry(composition, fps).also {
            entries[key] = it
        }
        entry.refCount++
        if (entry.refCount == 1) {
            activeKeys.add(key)
            entry.startedAtNanos = clock()
            entry.lastSnappedFrameIndex = -1
            postFrameIfNeeded()
        }
        return Handle(
            animator = this,
            key = key,
            drawable = entry.drawable,
            progress = entry.progress::floatValue,
        )
    }

    fun release(customEmojiId: Long, fps: Fps) {
        val key = EntryKey(customEmojiId, fps.target)
        val entry = entries[key] ?: return
        entry.refCount--
        if (entry.refCount <= 0) {
            entry.refCount = 0
            activeKeys.remove(key)
        }
    }

    fun clear() {
        // Quiesce the BG queue and reclaim bitmaps. We don't tear down the
        // HandlerThread itself — the animator outlives logout/login and the
        // thread can keep idling.
        renderHandler.removeCallbacksAndMessages(null)
        for (entry in entries.values) {
            entry.bitmapFront?.recycle()
            entry.bitmapBack?.recycle()
        }
        entries.clear()
        activeKeys.clear()
    }

    internal fun reportSize(key: EntryKey, pxSize: Int) {
        val entry = entries[key] ?: return
        if (pxSize <= entry.bitmapSizePx) return
        entry.bitmapFront?.recycle()
        entry.bitmapBack?.recycle()
        // ARGB_8888 — TGS stickers have anti-aliased edges + partial alpha that
        // RGB_565 would mangle. Memory hit per entry ~ 2 × pxSize² × 4 bytes; at
        // a typical 80 px that's ~50 KB.
        val front = Bitmap.createBitmap(pxSize, pxSize, Bitmap.Config.ARGB_8888)
        val back = Bitmap.createBitmap(pxSize, pxSize, Bitmap.Config.ARGB_8888)
        entry.bitmapFront = front
        entry.bitmapBack = back
        entry.bitmapBackCanvas = AndroidCanvas(back)
        entry.bitmapSizePx = pxSize
        entry.lastSnappedFrameIndex = -1
        // Kick a render so the front buffer isn't a blank ARGB grid by the time
        // the consumer's next draw runs. We pass the current snapped progress —
        // even if it's stale, the next master clock tick will refresh it.
        val initialProgress = entry.drawable.progress
        scheduleRender(key, initialProgress)
    }

    private fun createEntry(composition: LottieComposition, fps: Fps): Entry {
        val durationMs = max(1f, composition.duration).toDouble()
        val durationNanos = (durationMs * 1_000_000.0).toLong()
        val targetFrameStepNanos = 1_000_000_000L / fps.target.coerceAtLeast(1)
        val drawable = LottieDrawable().apply {
            setComposition(composition)
            pauseAnimation()
            repeatCount = 0
            // Merge paths is a common TGS feature; Lottie disables it by default
            // and warns on every animation that uses it. Enabling here removes the
            // warning and lets Lottie use its proper path-merge logic instead of
            // an expensive fallback that allocates per-frame.
            enableMergePathsForKitKatAndAbove(true)
        }
        return Entry(
            drawable = drawable,
            durationNanos = durationNanos,
            targetFrameStepNanos = targetFrameStepNanos,
            progress = mutableFloatStateOf(0f),
        )
    }

    private fun postFrameIfNeeded() {
        if (frameCallbackPosted || paused || activeKeys.isEmpty()) return
        frameCallbackPosted = true
        choreographer().postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        dbgFrameCallbackCount++
        if (paused) return@FrameCallback
        if (activeKeys.isEmpty()) return@FrameCallback
        val snapshot = activeKeys.toTypedArray()
        for (key in snapshot) {
            val entry = entries[key] ?: continue
            advanceEntry(key, entry, frameTimeNanos)
        }
        maybeEmitStats(frameTimeNanos)
        postFrameIfNeeded()
    }

    private fun advanceEntry(key: EntryKey, entry: Entry, frameTimeNanos: Long) {
        dbgAdvanceCount++
        val elapsed = frameTimeNanos - entry.startedAtNanos
        if (elapsed < 0) return
        val durationNanos = entry.durationNanos
        if (durationNanos <= 0) return
        val phase = elapsed % durationNanos
        val rawProgress = phase.toDouble() / durationNanos.toDouble()
        val totalSnappedFrames = max(1L, durationNanos / entry.targetFrameStepNanos)
        val snappedIndex = floor(rawProgress * totalSnappedFrames).toInt()
        if (snappedIndex == entry.lastSnappedFrameIndex) return
        entry.lastSnappedFrameIndex = snappedIndex
        val snappedProgress = snappedIndex / totalSnappedFrames.toFloat()
        scheduleRender(key, snappedProgress)
    }

    /**
     * Schedule a BG render at [snappedProgress]. If a render for the same entry is
     * already in flight, store the latest progress and let the inbound one complete
     * first — the completion handler picks up the pending progress and chains the
     * next render. This bounds the BG queue depth to "at most one per entry" and
     * means we always render the freshest target frame.
     */
    private fun scheduleRender(key: EntryKey, snappedProgress: Float) {
        val entry = entries[key] ?: return
        if (entry.bitmapBack == null) {
            // No back buffer yet — consumer hasn't reported size. Skip; the
            // reportSize call itself will schedule an initial render.
            return
        }
        if (entry.renderInFlight) {
            entry.pendingProgress = snappedProgress
            dbgRenderDropped++
            return
        }
        entry.renderInFlight = true
        dbgRenderScheduled++
        renderHandler.post {
            renderOnBg(key, snappedProgress)
        }
    }

    /** Runs on the BG render thread. Mutates drawable + back buffer only. */
    private fun renderOnBg(key: EntryKey, snappedProgress: Float) {
        val entry = entries[key] ?: return
        val back = entry.bitmapBack ?: return
        val canvas = entry.bitmapBackCanvas ?: return
        entry.drawable.progress = snappedProgress
        back.eraseColor(0)
        entry.drawable.setBounds(0, 0, back.width, back.height)
        entry.drawable.draw(canvas)
        mainHandler.post { onRenderComplete(key, snappedProgress) }
    }

    /** Runs on the main thread. Swap buffers and publish progress state. */
    private fun onRenderComplete(key: EntryKey, renderedProgress: Float) {
        val entry = entries[key] ?: return
        // Atomic swap on main thread. Compose consumers reading `bitmapFront` see
        // the just-painted buffer; the BG thread will pick up the (now stale) old
        // front as its next back. Since we don't draw to back outside scheduleRender
        // → renderOnBg, the stale front becomes a clean canvas for the next render.
        val prevFront = entry.bitmapFront
        entry.bitmapFront = entry.bitmapBack
        entry.bitmapBack = prevFront
        // Re-attach the canvas to whichever buffer is now back. android.graphics.Canvas
        // is bound to a Bitmap at construction; we need a fresh Canvas pointing at the
        // new back buffer. Pooling these would save the allocation; for now they're
        // ~80 bytes each and one per swap is fine.
        entry.bitmapBackCanvas = prevFront?.let { AndroidCanvas(it) }

        entry.progress.floatValue = renderedProgress
        dbgRenderCompleted++
        entry.renderInFlight = false

        // Chained pending render — most-recent snappedProgress that arrived while we
        // were busy.
        val pending = entry.pendingProgress
        if (!pending.isNaN()) {
            entry.pendingProgress = Float.NaN
            scheduleRender(key, pending)
        }
    }

    private fun maybeEmitStats(nowNanos: Long) {
        if (dbgLastStatsAtNanos == 0L) {
            dbgLastStatsAtNanos = nowNanos
            return
        }
        val elapsed = nowNanos - dbgLastStatsAtNanos
        if (elapsed < 1_000_000_000L) return
        Log.i(
            "CustomEmojiAnimator",
            "frames=$dbgFrameCallbackCount advances=$dbgAdvanceCount " +
                "scheduled=$dbgRenderScheduled completed=$dbgRenderCompleted " +
                "coalesced=$dbgRenderDropped active=${activeKeys.size} entries=${entries.size}",
        )
        dbgFrameCallbackCount = 0
        dbgAdvanceCount = 0
        dbgRenderScheduled = 0
        dbgRenderCompleted = 0
        dbgRenderDropped = 0
        dbgLastStatsAtNanos = nowNanos
    }
}
