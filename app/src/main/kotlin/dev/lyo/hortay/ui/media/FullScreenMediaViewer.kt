package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import dev.lyo.hortay.R
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Full-screen, gesture-driven media viewer. Photos pinch-zoom + pan; videos and animations
 * stream through ExoPlayer. Horizontal swipe between items; vertical swipe (on the page
 * background) dismisses with translation + dim — Twitter/Instagram style. Pinch-zoomed photos
 * keep their pan because [Modifier.draggable] surrenders to the photo's transform handler
 * once two fingers are down.
 */
@Composable
fun FullScreenMediaViewer(
    items: List<AlbumItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }

        val density = LocalDensity.current
        val dismissThresholdPx = remember(density) { with(density) { 140.dp.toPx() } }
        val maxFadePx = remember(density) { with(density) { 320.dp.toPx() } }
        val offsetY = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val draggable = rememberDraggableState { delta ->
            scope.launch { offsetY.snapTo(offsetY.value + delta) }
        }

        // Background dims as the user drags away — gives a sense of "you're pulling the sheet off".
        val backgroundAlpha = (1f - (abs(offsetY.value) / maxFadePx)).coerceIn(0.4f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha))
                .draggable(
                    state = draggable,
                    orientation = Orientation.Vertical,
                    onDragStopped = {
                        if (abs(offsetY.value) > dismissThresholdPx) {
                            onDismiss()
                        } else {
                            offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                ),
        ) {
            // Pre-warm the active page's poster + the immediate neighbours' posters at
            // Prefetch priority so a sideways swipe never hits an unstyled blank screen.
            // We only prefetch posters here, not playback files — neighbour videos start
            // downloading on their own when the page becomes active (TdVideoPlayer fires
            // MediaCache.ensure on mount), which preserves bandwidth for the page the
            // user is currently watching.
            val cache = LocalMediaCache.current
            LaunchedEffect(pagerState.currentPage, items) {
                val current = pagerState.currentPage
                items.getOrNull(current)?.posterFileId()?.let {
                    cache.ensure(it, DownloadPriority.Foreground)
                }
                listOf(current - 1, current + 1).forEach { idx ->
                    items.getOrNull(idx)?.posterFileId()?.let {
                        cache.ensure(it, DownloadPriority.Prefetch)
                    }
                }
            }

            // Per-video quality choice survives swipe-between-pages within this viewer
            // session: when the user picks 480p on video A, swipes to B, then back to A,
            // we restore A's pick. Keyed by the page index so the map naturally drops
            // entries that the pager re-uses for a different message.
            val qualityChoices = remember(items) { mutableStateMapOf<Int, VideoQuality>() }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offsetY.value },
            ) { page ->
                val item = items[page]
                MediaPage(
                    item = item,
                    isActive = page == pagerState.currentPage,
                    pickedQuality = qualityChoices[page],
                    onQualityPick = { quality -> qualityChoices[page] = quality },
                )
            }

            // Expressive close affordance: Cookie9-shaped backdrop instead of a perfect
            // circle. Reads as "deliberate close" rather than a generic system 'X' —
            // signature shape vocabulary that ties the viewer chrome to the rest of the
            // app's reaction / nav-tab idiom. Bigger touch target padding compensates
            // for the polygon's narrower visual weight.
            val closeShape = HortayExpressive.ReactionSelected.asComposeShape()
            // Polygon backdrop only — clipping the IconButton to a Cookie polygon
            // would cut the close glyph at the polygon ridges. The default circular
            // ripple stays clean inside the visible disc area.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.45f), closeShape),
            ) {
                Symbol(name = "close", contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }

            // Top-right tool column for the active page: QualityChip (videos
            // with alternativeVideos) over Save / Copy buttons. One vertical
            // stack avoids the playback chrome conflict the bottom-right
            // placement had — [VideoPlayerControls] owns the entire bottom
            // band (scrim, BottomBar slider+mute, navigationBarsPadding) and
            // any chrome anchored to BottomEnd would land on the slider or
            // mute toggle. Top-right is uncontested in both photo and video
            // pages. We keep the chrome chrome-coloured (Black 45 %, 44 dp
            // CircleShape) so the column reads as one "tools for this item"
            // affordance with the same vocabulary as the close button.
            val activeItem = items.getOrNull(pagerState.currentPage)
            val activeQualities = (activeItem as? AlbumItem.Video)?.qualities

            val actionContext = LocalContext.current
            val saveLabel = stringResource(R.string.action_save_to_gallery)
            val copyLabel = stringResource(R.string.action_copy_image)
            val savedPhotoMsg = stringResource(R.string.media_saved_photo)
            val savedVideoMsg = stringResource(R.string.media_saved_video)
            val saveFailedMsg = stringResource(R.string.media_save_failed)
            val copiedMsg = stringResource(R.string.media_copied)
            val copyFailedMsg = stringResource(R.string.media_copy_failed)

            val activeFileId = activeItem?.viewerFileId(qualityChoices[pagerState.currentPage]?.fileId)
            val activeState by produceMediaState(cache, activeFileId)
            val readyPath = (activeState as? MediaState.Ready)?.path
            val showQuality = activeQualities?.hasOptions == true
            val persistableItem = activeItem.takeIf { readyPath != null && activeFileId != null }

            if (showQuality || (persistableItem != null && readyPath != null)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (showQuality && activeQualities != null) {
                        val current = qualityChoices[pagerState.currentPage] ?: activeQualities.defaultPick
                        QualityChip(
                            current = current,
                            qualities = activeQualities,
                            onPick = { qualityChoices[pagerState.currentPage] = it },
                        )
                    }
                    if (persistableItem != null && readyPath != null) {
                        val activeItem = persistableItem // smart-cast bridge for lambdas
                        val chromeShape = CircleShape
                        // Save → every Ready media kind. Toast on success / failure.
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        MediaShareActions.saveToGallery(actionContext, activeItem, readyPath)
                                    }
                                    val successMsg = if (activeItem is AlbumItem.Photo) savedPhotoMsg else savedVideoMsg
                                    val toast = when (res) {
                                        is MediaShareActions.Result.Success -> successMsg
                                        is MediaShareActions.Result.Failure ->
                                            saveFailedMsg.format(
                                                actionContext.getString(res.reasonResId, *res.args.toTypedArray()),
                                            )
                                    }
                                    Toast.makeText(actionContext, toast, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.45f), chromeShape),
                        ) {
                            Symbol(name = "download", contentDescription = saveLabel, tint = Color.White)
                        }
                        // Copy → photo only. Nearly no Android app meaningfully
                        // accepts a video clipboard item, and a multi-MB MP4 URI
                        // on the clipboard is a UX trap (paste into WhatsApp =
                        // silent re-upload). Hidden for video / animation pages.
                        if (activeItem is AlbumItem.Photo) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            MediaShareActions.copyToClipboard(actionContext, activeItem, readyPath)
                                        }
                                        val toast = when (res) {
                                            is MediaShareActions.Result.Success -> copiedMsg
                                            is MediaShareActions.Result.Failure ->
                                                copyFailedMsg.format(
                                                    actionContext.getString(res.reasonResId, *res.args.toTypedArray()),
                                                )
                                        }
                                        Toast.makeText(actionContext, toast, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), chromeShape),
                            ) {
                                Symbol(name = "content_copy", contentDescription = copyLabel, tint = Color.White)
                            }
                        }
                    }
                }
            }

            if (items.size > 1) {
                // Pill-shaped scrim under the counter — same pattern as the close
                // button and QualityChip use elsewhere in this viewer. Without the
                // scrim a white photo behind the counter ("3 / 5" against snow,
                // sky, paper) made the digits illegible because they were also
                // white. The black-translucent plate guarantees readable contrast
                // on every possible photo.
                // True Pill polygon (subtly flattened ellipse) under the counter —
                // matches the NewPostsPill vocabulary so the viewer chrome reads as
                // part of the same expressive system, not stock material.
                val counterShape = HortayExpressive.Pill.asComposeShape()
                // Pill is convex so a clip would be safe here, but the surrounding
                // chrome is consistent: backdrop-only painting, no clip.
                Text(
                    text = "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), counterShape)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaPage(
    item: AlbumItem,
    isActive: Boolean,
    pickedQuality: VideoQuality?,
    onQualityPick: (VideoQuality) -> Unit,
) {
    when (item) {
        is AlbumItem.Photo -> ZoomableImage(item)
        is AlbumItem.Video -> {
            val quality = pickedQuality ?: item.qualities.defaultPick
            TdVideoPlayer(
                fileId = quality.fileId,
                remoteUrl = item.remoteVideoUrl,
                autoPlay = isActive,
                autoLoop = false,
                showControls = true,
                priority = DownloadPriority.Foreground,
                initialAspect = item.posterAspect(),
                modifier = Modifier.fillMaxSize(),
            )
            // Touch the picker callback so an unpicked default still registers — keeps
            // the parent's qualityChoices map authoritative for "what's playing now".
            if (pickedQuality == null) {
                LaunchedEffect(item.playbackFileId, item.qualities) { onQualityPick(quality) }
            }
        }
        is AlbumItem.Animation -> TdVideoPlayer(
            fileId = item.playbackFileId,
            remoteUrl = item.remoteVideoUrl,
            autoPlay = isActive,
            autoLoop = true,
            showControls = false,
            muted = true,
            priority = DownloadPriority.Foreground,
            initialAspect = item.posterAspect(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Pre-seed for [TdVideoPlayer.initialAspect]. Telegram serves a poster sized
 * to the same aspect ratio as the actual video stream (the poster is just a
 * down-sampled first frame), so the inline poster geometry is a faithful
 * predictor of the eventual [VideoSize] ExoPlayer will report. Returning
 * this lets [AspectRatioFrameLayout] letterbox correctly on first layout
 * instead of filling the parent and snapping to the right aspect only after
 * the decoder emits its first frame — closes the *"відкриваєш відео — на
 * долю секунди розтягується, потім стає нормальне"* glitch.
 */
private fun AlbumItem.posterAspect(): Float {
    val w = media.width
    val h = media.height
    return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 0f
}

@Composable
private fun ZoomableImage(item: AlbumItem.Photo) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val sameTier = item.fullscreen.fileId == item.media.fileId

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Custom transform: only consume the gesture when there's an active pinch (≥2
            // fingers) or the photo is already zoomed in (pan within image). One-finger drag
            // at scale==1 is left unconsumed so the outer `Modifier.draggable` (the swipe-to-
            // dismiss handler) can pick it up. Without this branch, every swipe was eaten by
            // the transform handler and only video pages dismissed.
            .pointerInput(item.fullscreen.fileId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val activePointers = event.changes.count { it.pressed }
                        val isPinchOrPan = activePointers >= 2 || scale > 1f
                        if (!isPinchOrPan) continue
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val zoom = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            )

        // Progressive enhancement: paint the inline variant first (typically
        // already Ready in MediaCache from feed rendering — no spinner), then
        // overlay the higher-resolution fullscreen variant once it lands.
        // Coil's CROSSFADE_MS on the Ready-path AsyncImage in TdMediaImage
        // fades the fullscreen image in over the inline one; without this
        // stack the user would see the soft minithumb-blur on every viewer
        // open until `w` finishes downloading.
        //
        // When the inline and fullscreen tiers resolve to the same fileId
        // (small uploads where TDLib's pyramid only ships one variant at or
        // above the inline target) we skip the bottom layer — drawing the
        // same fileId twice would just double the Coil request churn for
        // identical pixels.
        if (!sameTier) {
            TdMediaImage(
                media = item.media,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // No progress chrome on the bottom layer — the fullscreen
                // layer above owns the spinner / failed / cancel affordance
                // and rendering them twice would clash visually.
                showProgress = false,
                placeholderColor = null,
                priority = DownloadPriority.Foreground,
                modifier = zoom,
            )
        }
        TdMediaImage(
            media = item.fullscreen,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Transparent placeholder so the inline layer underneath bleeds
            // through during the fullscreen variant's download window.
            placeholderColor = if (sameTier) MaterialTheme.colorScheme.surfaceContainerHigh else null,
            priority = DownloadPriority.Foreground,
            modifier = zoom,
        )
    }
}

// What to prefetch via MediaCache for an item. For photos we pre-warm the
// *fullscreen* variant — that's what ZoomableImage actually paints, and the
// inline variant (if different) is overwhelmingly likely to already be Ready
// from feed rendering. For videos / animations the prefetch target is the
// poster image; the playback file itself is only fetched once the user lands
// on that page (TdVideoPlayer triggers ensure on mount), so neighbour videos
// don't compete with the one currently being watched.
private fun AlbumItem.posterFileId(): Int? = when (this) {
    is AlbumItem.Photo -> fullscreen.fileId
    is AlbumItem.Video -> media.fileId
    is AlbumItem.Animation -> media.fileId
}

/**
 * Observes [MediaCache] for the active page's file id and re-keys when the
 * page (or the picked video quality) changes. Returns [MediaState.Idle] when
 * [fileId] is null — web-mode posts and unplayable videos take that branch
 * so the Save / Copy chrome stays hidden. The flow is collected eagerly so
 * the buttons appear the instant the download lands without an extra recomposition.
 */
@Composable
private fun produceMediaState(cache: MediaCache, fileId: Int?): State<MediaState> {
    val flow = remember(fileId) { fileId?.let(cache::observe) }
    return flow?.collectAsState() ?: remember { mutableStateOf<MediaState>(MediaState.Idle) }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
