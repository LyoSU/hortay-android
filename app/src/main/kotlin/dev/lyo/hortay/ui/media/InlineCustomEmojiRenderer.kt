package dev.lyo.hortay.ui.media

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieComposition
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.TdMedia
import kotlin.math.max

/**
 * Compose renderer for a single inline TGS custom emoji, backed by
 * [CustomEmojiAnimator]'s shared rasterisation cache. Replaces the per-instance
 * Lottie-Compose `LottieAnimation` for inline-emoji size class playback.
 *
 * Pipeline per draw:
 *   1. Acquire (or join) a [CustomEmojiAnimator.Handle] for the (id, fps) pair.
 *   2. Report the consumer's pixel size — the animator allocates / expands a shared
 *      [android.graphics.Bitmap] sized to the max consumer at this id.
 *   3. Inside `Canvas { ... }` read `handle.progress()` to subscribe to draw
 *      invalidations, then blit `handle.bitmap` to the consumer's bounds. Blit is a
 *      cheap GPU texture copy; no Lottie layer-tree walk per consumer.
 *
 * Sibling composables of the same id share one [com.airbnb.lottie.LottieDrawable] +
 * one Bitmap + one master-clock tick. A post with 30 copies of the same TGS emoji
 * pays one layer-walk per frame plus 30 cheap blits, instead of 30 layer walks.
 *
 * Tint: monochrome emojis (`needsRepainting = true`) take their colour from the
 * surrounding text. Because the Bitmap is shared we can't bake a colour filter into
 * it — different consumers may sit over different surfaces. Tint is applied via the
 * `Paint.colorFilter` passed to `drawBitmap` — per-consumer, one bitmap source.
 */
@Composable
fun InlineCustomEmojiRenderer(
    customEmojiId: Long,
    fileId: Int?,
    remoteUrl: String?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tintColor: Color? = null,
    fps: CustomEmojiAnimator.Fps = CustomEmojiAnimator.Fps.Inline,
    priority: DownloadPriority = DownloadPriority.Avatar,
) {
    val animator = LocalCustomEmojiAnimator.current
    val httpClient = LocalWebHttpClient.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isRemote = fileId == null && remoteUrl != null

    // Bind to the underlying TDLib file slot (no-op for remote). Same observe/ensure/
    // cancel contract every other TDLib renderer uses; downstream [CustomEmojiAnimator]
    // never pokes MediaCache itself — it only consumes already-parsed compositions.
    val binding = rememberMediaBinding(fileId = fileId, priority = priority, isRemote = isRemote)
    val readyPath = binding.readyPath

    // Resolve composition (TDLib file path OR remote URL). Cached upstream so a re-mount
    // of the same id is local-fast.
    var composition by remember(fileId, remoteUrl) { mutableStateOf<LottieComposition?>(null) }
    LaunchedEffect(readyPath, remoteUrl, isRemote) {
        if (isRemote) {
            val url = remoteUrl ?: return@LaunchedEffect
            composition = LottieUrlStore.load(url, httpClient)
        } else {
            val path = readyPath ?: return@LaunchedEffect
            composition = LottieCompositionStore.load(path)
        }
    }

    val comp = composition
    if (comp == null) {
        // Pre-composition placeholder: static thumb if we have one, else dead air.
        // The Canvas measure/layout pass doesn't fire until we have a composition, so
        // this branch is genuinely zero-cost beyond the placeholder image itself.
        Box(modifier = modifier) {
            if (thumb != null) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
        }
        return
    }

    // Visibility gate. Without this, a backgrounded overlay (e.g. comments sheet
    // expanded over the feed) would keep the master clock advancing for the now-
    // invisible inline emojis on the feed beneath. The animator's own
    // ProcessLifecycle pause covers full-app background; this finer gate handles
    // host-level (per-screen / per-overlay) visibility.
    val isActive = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // Acquired-state pattern: hold the handle in a state and only mutate refCount
    // when isActive (or comp / id) actually transitions. Without this, the previous
    // [DisposableEffect] re-trigger on isActive flips would double-release the
    // refcount (cleanup of stale effect + body of new effect both fired release).
    var handle by remember(customEmojiId, comp) { mutableStateOf<CustomEmojiAnimator.Handle?>(null) }
    DisposableEffect(customEmojiId, comp, fps, isActive) {
        val acquired = if (isActive) animator.acquire(customEmojiId, comp, fps) else null
        handle = acquired
        onDispose {
            if (acquired != null) {
                animator.release(customEmojiId, fps)
                handle = null
            }
        }
    }

    val tintArgb = tintColor?.toArgb()
    val tintPaint = remember(tintArgb) {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (tintArgb != null) {
                colorFilter = PorterDuffColorFilter(tintArgb, PorterDuff.Mode.SRC_ATOP)
            }
        }
    }
    // Cached destination rect for drawBitmap. Allocating inside the draw scope would
    // generate one RectF per frame per consumer; pulling it out makes the hot path
    // allocation-free.
    val dstRect = remember { RectF() }

    val activeHandle = handle
    if (activeHandle == null) {
        // Lifecycle paused us OR effect hasn't run yet. Show the thumb so the slot
        // isn't dead air. Idle: zero draw cost beyond the static image.
        Box(modifier = modifier) {
            if (thumb != null) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    placeholderColor = null,
                    showProgress = false,
                    priority = priority,
                )
            }
        }
        return
    }

    Canvas(modifier = modifier) {
        // Subscribe this draw scope to progress changes — every master-clock advance
        // invalidates draw (NOT recomposition) and we land back here to blit the
        // refreshed bitmap. Reading the lambda is enough to register the subscription.
        activeHandle.progress()

        val w = size.width
        val h = size.height
        val pxSize = max(w, h).toInt().coerceAtLeast(1)
        // Report size in case this consumer is the biggest one for this id yet —
        // animator will expand the shared bitmap if so. No-op if our size is already
        // covered.
        activeHandle.reportSize(pxSize)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val bmp = activeHandle.bitmap
            if (bmp != null) {
                // Texture-cache friendly blit. The Paint carries the tint colour filter
                // (no-op when null) and `FILTER_BITMAP_FLAG` for smooth downscale —
                // the shared bitmap is sized to the largest consumer, so smaller
                // consumers receive a downscale.
                dstRect.set(0f, 0f, w, h)
                nc.drawBitmap(bmp, null, dstRect, tintPaint)
            } else {
                // Bitmap not yet allocated (first frame between acquire and reportSize
                // dispatch). Fall back to direct drawable.draw for this frame only.
                activeHandle.drawable.setBounds(0, 0, w.toInt(), h.toInt())
                activeHandle.drawable.draw(nc)
            }
        }
    }
}
