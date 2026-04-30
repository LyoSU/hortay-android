package dev.lyo.hortay.ui.media

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
) {
    val cache = LocalMediaCache.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mediaState by remember(fileId) {
        if (fileId != null) cache.observe(fileId)
        else MutableStateFlow(MediaState.Idle)
    }.collectAsStateWithLifecycle()

    LaunchedEffect(fileId, priority) { fileId?.let { cache.ensure(it, priority) } }

    var composition by remember(fileId) { mutableStateOf<LottieComposition?>(null) }
    // Key on the Ready path only — Downloading bursts emit dozens of states per second
    // and would otherwise re-launch this coroutine that many times. The store cache
    // also makes the second .load() call essentially free, but skipping the relaunch
    // is the cheaper guarantee.
    val readyPath = (mediaState as? MediaState.Ready)?.path
    LaunchedEffect(readyPath) {
        val path = readyPath ?: return@LaunchedEffect
        if (path.isEmpty()) return@LaunchedEffect
        composition = LottieCompositionStore.load(path)
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

    Box(modifier = modifier) {
        // Underlay the static thumbnail until the composition is ready. We hide it once
        // Lottie has frames so transparent areas of the sticker don't reveal the static
        // thumb behind them (which would look like a doubled silhouette).
        if (thumb != null && composition == null) {
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
            )
        }
    }
}
