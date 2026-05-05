package dev.lyo.hortay.ui.media

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Plays a Telegram TGS (gzipped Lottie) sticker. Pipeline:
 *
 *   1. Ask [MediaCache] to download the .tgs file at the given priority.
 *   2. Underlay the [thumb] (TDLib-served WEBP/PNG) — instant preview while bytes land.
 *   3. Once the file is `Ready`, decompress + parse via [LottieCompositionStore].
 *   4. Hand the composition to Compose's [LottieAnimation] — GPU-accelerated, cheap.
 *
 * Lifecycle / battery: animation pauses while the host lifecycle is below STARTED (app
 * backgrounded, screen off). Off-screen items in a LazyColumn are disposed by the
 * column itself, which tears down this composable and stops drawing entirely; we don't
 * need extra plumbing to handle that case.
 *
 * `repaintColor` honours TDLib's `StickerFullTypeCustomEmoji.needsRepainting` flag —
 * monochrome custom emojis must take the surrounding text colour. Lottie's
 * COLOR_FILTER dynamic property recolours the layers at draw time without re-parsing
 * the composition (so the cache hit is preserved across light/dark theme swaps).
 */
@Composable
fun LottieStickerView(
    fileId: Int?,
    thumb: TdMedia?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    iterate: Boolean = true,
    repaintColor: Color? = null,
    /**
     * Web-mode TGS payload URL. When [fileId] is null and [remoteUrl] is set, we
     * bypass the [dev.lyo.hortay.data.MediaCache] / TDLib pathway entirely and
     * fetch the .tgs (or pre-decompressed JSON) bytes via [LottieUrlStore]. Lets
     * guest-mode custom emoji animate through the same Lottie Composable that
     * TDLib mode uses — same animation engine, same recolour, same lifecycle.
     */
    remoteUrl: String? = null,
) {
    val cache = LocalMediaCache.current
    val httpClient = LocalWebHttpClient.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isRemote = fileId == null && remoteUrl != null
    val mediaState by remember(fileId, isRemote) {
        if (fileId != null && !isRemote) cache.observe(fileId)
        else MutableStateFlow(MediaState.Idle)
    }.collectAsStateWithLifecycle()

    val gate = LocalScrollGate.current
    val gateOpen = gate.value
    LaunchedEffect(fileId, priority, gateOpen, isRemote) {
        if (gateOpen && !isRemote) fileId?.let { cache.ensure(it, priority) }
    }
    // Mirror TdMediaImage's dispose-cancels-download contract — otherwise a TGS that
    // scrolled off-screen keeps holding a TDLib download slot until it finishes,
    // queueing behind currently-visible media. Bytes survive on disk so a re-mount
    // resumes from the saved offset. Web-mode (isRemote) has no cache slot to free.
    DisposableEffect(fileId, isRemote) {
        onDispose { if (!isRemote) fileId?.let(cache::cancelDeferred) }
    }

    var composition by remember(fileId, remoteUrl) { mutableStateOf<LottieComposition?>(null) }
    val readyPath = (mediaState as? MediaState.Ready)?.path
    LaunchedEffect(readyPath, remoteUrl, isRemote) {
        if (isRemote) {
            val url = remoteUrl ?: return@LaunchedEffect
            composition = LottieUrlStore.load(url, httpClient)
        } else {
            val path = readyPath ?: return@LaunchedEffect
            if (path.isEmpty()) return@LaunchedEffect
            composition = LottieCompositionStore.load(path)
        }
    }

    val isPlaying = composition != null &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = if (iterate) LottieConstants.IterateForever else 1,
        isPlaying = isPlaying,
        clipSpec = LottieClipSpec.Progress(0f, 1f),
    )

    val dynamicProperties: LottieDynamicProperties? = repaintColor?.let { color ->
        val argb = color.toArgb()
        rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(
                property = LottieProperty.COLOR_FILTER,
                value = PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_ATOP),
                keyPath = arrayOf("**"),
            ),
        )
    }

    // True once Lottie has actually painted at least one frame past the start. We
    // can't query `LottieAnimation` directly; instead we infer it from `progress`
    // having advanced beyond zero. `animateLottieCompositionAsState` ticks
    // `progress` on the next frame after `composition` becomes non-null, so this
    // condition flips one frame after we have a renderable composition — exactly
    // the boundary where it's safe to drop the static thumbnail underneath.
    //
    // Fix for the "black flash" the user saw on guest-mode TGS emojis: dropping
    // the thumb the instant `composition != null` left a 1-2 frame gap before
    // Lottie's first paint, during which the inline emoji area showed the
    // surrounding surface (dark in night mode → looks like a black square
    // blink). Keeping the thumb a frame longer eliminates the gap; once Lottie
    // is drawing, the static thumb is hidden so transparent regions of the
    // animated sticker don't reveal a frozen silhouette underneath.
    val animationHasStarted = composition != null && progress > 0f

    Box(modifier = modifier) {
        if (thumb != null && !animationHasStarted) {
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
        composition?.let { comp ->
            LottieAnimation(
                composition = comp,
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                dynamicProperties = dynamicProperties,
                // safeMode skips problem frames silently instead of throwing /
                // returning a crashed canvas. A small minority of TGS payloads
                // Telegram serves rely on Lottie features the Android port
                // implements with edge cases; without safeMode the crashed
                // frame paints as a solid colour, which read to the user as
                // "TGS broken / black background".
                safeMode = true,
            )
        }
    }
}
