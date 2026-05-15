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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
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
            val shareLabel = stringResource(R.string.action_share_media)
            val savedPhotoMsg = stringResource(R.string.media_saved_photo)
            val savedVideoMsg = stringResource(R.string.media_saved_video)
            val saveFailedMsg = stringResource(R.string.media_save_failed)
            val copiedMsg = stringResource(R.string.media_copied)
            val copyFailedMsg = stringResource(R.string.media_copy_failed)
            val shareFailedMsg = stringResource(R.string.media_share_failed)

            val activeFileId = activeItem?.viewerFileId(qualityChoices[pagerState.currentPage]?.fileId)
            val activeState by produceMediaState(cache, activeFileId)
            val tdlibReadyPath = (activeState as? MediaState.Ready)?.path
            // Guest (web) mode: TDLib hasn't materialised the photo into
            // filesDir/tdlib-files — but Coil already cached the bytes for
            // rendering the inline poster / fullscreen variant. Resolve that
            // path so Save / Copy / Share don't silently disappear for
            // photos that the user is actively looking at.
            val webCachePath by produceWebCachePath(activeItem)
            val readyPath = tdlibReadyPath ?: webCachePath
            // Last-resort Share target for guest-mode videos: Coil is
            // image-only so [webCachePath] is null for video / animation,
            // but ExoPlayer streams them straight from a CDN URL. Hand that
            // URL to ACTION_SEND text/plain — recipients open the link or
            // resolve it via their own HTTP stack.
            val webShareUrl: String? = activeItem?.webShareFallbackUrl()
            val showQuality = activeQualities?.hasOptions == true
            val persistableItem = activeItem.takeIf { readyPath != null }
            val shareCapable = readyPath != null || !webShareUrl.isNullOrBlank()

            if (showQuality || persistableItem != null || shareCapable) {
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
                    val chromeShape = CircleShape
                    if (persistableItem != null && readyPath != null) {
                        val activeItem = persistableItem // smart-cast bridge for lambdas
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
                                                actionContext.resources.getString(res.reasonResId, *res.args.toTypedArray()),
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
                    }
                    if (shareCapable) {
                        // Share → fires ACTION_SEND through the system chooser
                        // so the user can route the file into any app (Telegram,
                        // WhatsApp, Photos, Files, …) without a Save-then-pick
                        // round-trip. When we have local bytes (TDLib file or
                        // Coil-cached web photo) we mint a FileProvider URI;
                        // otherwise (guest-mode video / animation streaming from
                        // a CDN URL with no local copy) we fall back to sharing
                        // the URL as text/plain so the recipient still gets
                        // something meaningful.
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        if (readyPath != null && activeItem != null) {
                                            MediaShareActions.shareMedia(actionContext, activeItem, readyPath)
                                        } else if (!webShareUrl.isNullOrBlank()) {
                                            MediaShareActions.shareUrl(actionContext, webShareUrl)
                                        } else {
                                            MediaShareActions.Result.Failure(R.string.media_share_error_source_missing)
                                        }
                                    }
                                    if (res is MediaShareActions.Result.Failure) {
                                        Toast.makeText(
                                            actionContext,
                                            shareFailedMsg.format(
                                                actionContext.resources.getString(res.reasonResId, *res.args.toTypedArray()),
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.45f), chromeShape),
                        ) {
                            Symbol(name = "ios_share", contentDescription = shareLabel, tint = Color.White)
                        }
                    }
                    if (persistableItem != null && readyPath != null && persistableItem is AlbumItem.Photo) {
                        val activeItem = persistableItem
                        // Copy → photo only. Nearly no Android app meaningfully
                        // accepts a video clipboard item, and a multi-MB MP4 URI
                        // on the clipboard is a UX trap (paste into WhatsApp =
                        // silent re-upload). Hidden for video / animation pages.
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
                                                actionContext.resources.getString(res.reasonResId, *res.args.toTypedArray()),
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
                // Short clips (< 1 minute) loop in fullscreen too — same threshold
                // as the inline-feed autoplay, so a clip that loops silently in
                // the feed keeps looping (with sound) when escalated to fullscreen
                // instead of stopping at the end and forcing a "tap play to
                // replay" round-trip. Long videos keep finite playback so the
                // scrubber lands at the end and the user can leave the viewer
                // without the clip restarting in the background.
                autoLoop = item.durationSec in 1..LOOP_FULLSCREEN_MAX_SEC,
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
    // Animatable trio: pinch updates flow through `snapTo` for instant feel, and
    // the double-tap handler uses `animateTo` so the transition reads as a
    // deliberate Telegram-style zoom-in instead of a single-frame jump. Keying
    // by [item.fullscreen.fileId] resets state when the user pages to a new
    // photo — no carry-over zoom across pager pages.
    val scale = remember(item.fullscreen.fileId) { Animatable(1f) }
    val offsetX = remember(item.fullscreen.fileId) { Animatable(0f) }
    val offsetY = remember(item.fullscreen.fileId) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Viewport size for offset clamping. Without bounds, pinch-pan let the user
    // fling the image completely off-screen (visible black rectangle with no
    // way back); Telegram clamps so the image edges can never cross the
    // opposite viewport edge. Captured via [onSizeChanged] on the root Box.
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun maxOffsetX(targetScale: Float): Float =
        if (targetScale <= 1f || containerSize.width == 0) 0f
        else (containerSize.width * (targetScale - 1f)) / 2f
    fun maxOffsetY(targetScale: Float): Float =
        if (targetScale <= 1f || containerSize.height == 0) 0f
        else (containerSize.height * (targetScale - 1f)) / 2f

    val sameTier = item.fullscreen.fileId == item.media.fileId

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            // Double-tap to toggle between 1× and [DOUBLE_TAP_SCALE]. Animates
            // through the same MotionScheme spring family the rest of the app
            // uses so the zoom reads as part of the Material Expressive motion
            // vocabulary (not a hand-rolled bounce). Tap-around behaviour:
            // zooming in pulls the tap point toward viewport centre, so the
            // user sees more of the area they pointed at — canonical Telegram /
            // Instagram pattern. Single-finger taps (no double) pass through
            // unconsumed so the swipe-to-dismiss handler keeps working.
            .pointerInput(item.fullscreen.fileId) {
                detectTapGestures(onDoubleTap = { tap ->
                    val zoomed = scale.value > 1f
                    scope.launch {
                        if (zoomed) {
                            launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy)) }
                            launch { offsetX.animateTo(0f, spring()) }
                            launch { offsetY.animateTo(0f, spring()) }
                        } else {
                            val target = DOUBLE_TAP_SCALE
                            val cx = (containerSize.width / 2f - tap.x) * (target - 1f)
                            val cy = (containerSize.height / 2f - tap.y) * (target - 1f)
                            launch {
                                offsetX.animateTo(
                                    cx.coerceIn(-maxOffsetX(target), maxOffsetX(target)),
                                    spring(),
                                )
                            }
                            launch {
                                offsetY.animateTo(
                                    cy.coerceIn(-maxOffsetY(target), maxOffsetY(target)),
                                    spring(),
                                )
                            }
                            scale.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                        }
                    }
                })
            }
            // Pinch / pan: consume only when there's a real pinch (≥2 fingers)
            // or the user has dragged one finger past the touch slop while
            // zoomed. The slop gate is what lets double-tap-to-zoom-back
            // actually fire — without it, the inevitable sub-pixel finger
            // wobble during a tap on a zoomed photo would land here, get
            // consumed as a "pan", and trip `detectTapGestures`'
            // `waitForUpOrCancellation` into the cancellation path (Compose
            // treats any consumed motion during a tap as a drag), killing
            // the second tap before it could complete the double-tap.
            // One-finger drag at scale == 1 stays unconsumed so the outer
            // `Modifier.draggable` (swipe-to-dismiss) keeps working.
            .pointerInput(item.fullscreen.fileId) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val downPos = first.position
                    var multiFinger = false
                    var passedSlop = false
                    do {
                        val event = awaitPointerEvent()
                        val activePointers = event.changes.count { it.pressed }
                        if (activePointers >= 2) multiFinger = true
                        // Slop gate: only count a single-finger gesture as
                        // pan once the cursor leaves the slop circle. Pinch
                        // engages immediately (two fingers = unambiguous).
                        if (!multiFinger && scale.value > 1f && !passedSlop) {
                            val cur = event.changes.firstOrNull { it.pressed }?.position
                            if (cur != null && (cur - downPos).getDistance() > slop) {
                                passedSlop = true
                            }
                        }
                        val engaged = multiFinger || (scale.value > 1f && passedSlop)
                        if (!engaged) continue
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!multiFinger && zoom == 1f && pan.x == 0f && pan.y == 0f) continue
                        val newScale = (scale.value * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        scope.launch { scale.snapTo(newScale) }
                        if (newScale > 1f) {
                            val maxX = maxOffsetX(newScale)
                            val maxY = maxOffsetY(newScale)
                            scope.launch { offsetX.snapTo((offsetX.value + pan.x).coerceIn(-maxX, maxX)) }
                            scope.launch { offsetY.snapTo((offsetY.value + pan.y).coerceIn(-maxY, maxY)) }
                        } else {
                            scope.launch { offsetX.snapTo(0f) }
                            scope.launch { offsetY.snapTo(0f) }
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
                scaleX = scale.value,
                scaleY = scale.value,
                translationX = offsetX.value,
                translationY = offsetY.value,
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

/**
 * Guest (web) mode bridge: resolve the active item's remote URL against
 * Coil's disk cache → local file path. Coil already loaded the bytes for
 * rendering the inline poster + fullscreen variant, so we can hand the
 * cached file straight to the Save / Copy / Share pipeline without
 * re-downloading. Returns null for video / animation kinds (Coil is
 * image-only) and for cold cache (Coil never loaded the URL, or evicted
 * it under disk pressure).
 *
 * Runs the cache lookup on IO so the open-snapshot read doesn't stall
 * the frame; viewer chrome appears one frame after the active page
 * resolves but well before the user can interact with it.
 */
@Composable
private fun produceWebCachePath(item: AlbumItem?): State<String?> {
    val context = LocalContext.current
    val lookupUrl = item?.webCacheLookupUrl()
    return produceState<String?>(initialValue = null, lookupUrl) {
        value = if (lookupUrl.isNullOrBlank()) null
        else withContext(Dispatchers.IO) {
            MediaShareActions.coilCachePath(context, lookupUrl)
        }
    }
}

/** Coil-disk-cache key for the active item, or null when no image URL applies. */
private fun AlbumItem.webCacheLookupUrl(): String? = when (this) {
    is AlbumItem.Photo -> fullscreen.remoteUrl ?: media.remoteUrl
    is AlbumItem.Video -> null
    is AlbumItem.Animation -> null
}

/**
 * URL handed to a text/plain Share when no local file backs the item. For
 * web-mode video / animation that's the CDN playback URL; for guest-mode
 * photos that's the same image URL Coil would resolve (but the Coil cache
 * pipeline is preferred — this fallback is only used when [readyPath] is
 * null in the chrome block above).
 */
private fun AlbumItem.webShareFallbackUrl(): String? = when (this) {
    is AlbumItem.Photo -> fullscreen.remoteUrl ?: media.remoteUrl
    is AlbumItem.Video -> remoteVideoUrl ?: media.remoteUrl
    is AlbumItem.Animation -> remoteVideoUrl ?: media.remoteUrl
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
// Telegram / Instagram double-tap-to-zoom target. Strong enough that the
// user reads the gesture as a deliberate zoom-in, conservative enough that
// faces / typical photo subjects don't immediately bleed past the viewport
// edges (re-engaging pan would still work, but the initial snap should
// feel natural).
private const val DOUBLE_TAP_SCALE = 2.5f
// Same cutoff as INLINE_AUTOPLAY_MAX_SEC in PostBody — clips ≤ 60 s loop
// silently inline, and now keep looping when the user escalates to fullscreen.
// Longer videos run finite-playback so the scrubber lands at the end.
private const val LOOP_FULLSCREEN_MAX_SEC = 60
