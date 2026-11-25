package dev.lyo.hortay.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import dev.lyo.hortay.data.TdMedia
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.max
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.WindowManager
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
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.collections.immutable.ImmutableList
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
    items: ImmutableList<AlbumItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    /** Saving is forbidden by the chat: Save, Share and Copy are hidden, not disabled. */
    protectedContent: Boolean = false,
) {
    if (items.isEmpty()) return
    val startPage = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    val density = LocalDensity.current
    val dismissThresholdPx = remember(density) { with(density) { 140.dp.toPx() } }
    val maxFadePx = remember(density) { with(density) { 320.dp.toPx() } }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Reduced motion (system "Remove animations"): the drag-dismiss snap-back and the
    // double-tap zoom settle skip their spring physics and jump to the end state, so
    // motion-sensitive users don't get bouncy overshoot. The gestures themselves are
    // unchanged — only the post-release animation is neutralised. The open/close transition
    // below is disabled outright, so the viewer appears and vanishes in one frame.
    val reducedMotion = rememberReducedMotion()
    val settleSpec: AnimationSpec<Float> =
        if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultSpatialSpec()
    val transformSpec: AnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()

    val fadeSpec: AnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // ---- Open/close transition ----------------------------------------------------------
    // One transition for every media kind and every way in or out. [transitionProgress]
    // runs 0 (closed) → 1 (fullscreen) on open and back on close; what it drives depends on
    // whether the page's media has a tile on screen (see [ViewerSourceAnchor]):
    //   - tile found → container transform: the pager scales, translates and clips from the
    //     tile's rect to fullscreen and back ([containerTransform]). Photos, video posters
    //     and GIFs alike, looked up by the media of the CURRENT page, so an album closes
    //     into the tile of the page the user swiped to;
    //   - no tile (comments anchor, a page whose album tile is scrolled away, media without
    //     dimensions, a zoomed photo, a page mid-swipe) → the pager fades with a slight
    //     scale instead.
    // The viewer is a separate Dialog window, so the tile can't take part in a
    // SharedTransitionLayout — it is mapped into this window through screen coordinates.
    // The window's own enter/exit animation is switched off below: it kept a fading copy of
    // the landed media over the tile for its whole duration.
    // Lower bound 0: the closing spring ends the moment the media lands on the tile
    // instead of undershooting into a smaller-than-tile wobble before the dialog leaves.
    val transitionProgress = remember {
        Animatable(if (reducedMotion) 1f else 0f).also { it.updateBounds(lowerBound = 0f) }
    }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Geometry of the leg in flight; null = the plain fade.
    var leg by remember { mutableStateOf<TransitionLeg?>(null) }
    // Open leg resolved — until then the pager paints nothing rather than flash fullscreen.
    var ready by remember { mutableStateOf(reducedMotion) }
    var settled by remember { mutableStateOf(reducedMotion) }
    var closing by remember { mutableStateOf(false) }
    var closeStartAlpha by remember { mutableFloatStateOf(1f) }
    var closeStartDragY by remember { mutableFloatStateOf(0f) }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }

    // The tile gives its media to the viewer while the viewer shows it — otherwise the media
    // is on screen twice during the transform — and gets it back the moment the viewer lets
    // go of it or leaves by any path.
    val heldTile = remember { arrayOfNulls<ViewerSourceAnchor>(1) }
    fun holdTile(anchor: ViewerSourceAnchor?) {
        val held = heldTile[0]
        if (held === anchor) return
        held?.hidden = false
        heldTile[0] = anchor
        anchor?.hidden = true
    }
    DisposableEffect(Unit) {
        onDispose { holdTile(null) }
    }

    fun resolveLeg(page: Int, root: LayoutCoordinates): Pair<ViewerSourceAnchor, TransitionLeg>? {
        val media = items.getOrNull(page)?.media ?: return null
        val aspect = media.aspectRatio
        if (aspect <= 0f) return null
        val anchor = ViewerSourceRegistry.find(media) ?: return null
        val rect = anchor.boundsIn(root) ?: return null
        val screen = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        if (!rect.overlaps(screen)) return null
        return anchor to TransitionLeg(rect, anchor.cornerRadiusPx, aspect)
    }

    LaunchedEffect(Unit) {
        if (reducedMotion) return@LaunchedEffect
        val root = snapshotFlow { rootCoords }.filterNotNull().first()
        val resolved = resolveLeg(startPage, root)
        leg = resolved?.second
        ready = true
        if (resolved != null) {
            // The dialog window presents its first frame a beat after it is laid out; hiding
            // the tile before that left an empty hole in the post for a frame.
            repeat(TILE_HANDOFF_FRAMES) { withFrameNanos { } }
            if (closing) return@LaunchedEffect
            holdTile(resolved.first)
        }
        transitionProgress.animateTo(1f, if (resolved != null) transformSpec else fadeSpec)
        settled = true
    }

    // Once open, keep hold of whichever tile shows the current page's media (swiping an
    // album moves the hold), so a close can land on it without a second copy underneath.
    LaunchedEffect(pagerState.currentPage, settled) {
        if (settled && !closing && !reducedMotion) {
            holdTile(ViewerSourceRegistry.find(items.getOrNull(pagerState.currentPage)?.media))
        }
    }

    // Every dismissal path (close button, system back / predictive-back commit through the
    // Dialog's own back callback, drag past the threshold) funnels through here.
    fun requestClose() {
        if (closing) return
        if (reducedMotion) {
            onDismiss()
            return
        }
        val root = rootCoords
        val page = pagerState.currentPage
        val pageSettled = abs(pagerState.currentPageOffsetFraction) < PAGE_SETTLED_FRACTION
        val resolved = if (root != null && pageSettled && zoomedPages[page] != true) {
            resolveLeg(page, root)
        } else {
            null
        }
        leg = resolved?.second
        holdTile(resolved?.first)
        closeStartAlpha = (1f - (abs(offsetY.value) / maxFadePx)).coerceIn(0f, 1f)
        closeStartDragY = offsetY.value
        closing = true
        scope.launch {
            transitionProgress.animateTo(0f, if (resolved != null) transformSpec else fadeSpec)
            // Give the tile back while this window still covers it with the landed copy,
            // and leave only once the feed has drawn it — the two windows don't present in
            // the same frame, so the other order blinks an empty tile.
            holdTile(null)
            repeat(TILE_HANDOFF_FRAMES) { withFrameNanos { } }
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Strip the Dialog's default opaque windowBackground + system FLAG_DIM_BEHIND so
        // the only thing painting between the photo and the activity underneath is the
        // Box scrim below. Without this the light-theme white windowBackground bleeds
        // through whenever the scrim fades, and the swipe-to-dismiss reads as
        // "a white sheet slides in behind the photo" instead of "the viewer fades out".
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setWindowAnimations(0)
        }
        val draggable = rememberDraggableState { delta ->
            scope.launch { offsetY.snapTo(offsetY.value + delta) }
        }

        // Backdrop alpha tracks |offsetY| linearly to 0 — at full drag the viewer
        // is transparent and the feed underneath is visible, matching the Telegram /
        // Instagram "pulling the photo away from the page" idiom. No alpha floor:
        // any non-zero floor would leave a translucent dark sheet glued over the
        // photo while it translates, breaking the "fading away" reading of the gesture.
        // The open/close transition multiplies in its progress, so the backdrop rises with
        // the media growing out of its tile and sinks with it on the way back; a close
        // freezes the drag share at release so the backdrop never brightens as the
        // media travels back up from its dragged position.
        val visibleProgress = transitionProgress.value.coerceIn(0f, 1f)
        val dragAlpha = (1f - (abs(offsetY.value) / maxFadePx)).coerceIn(0f, 1f)
        val backgroundAlpha = (if (closing) closeStartAlpha else dragAlpha) * visibleProgress

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { coords -> if (rootCoords == null) rootCoords = coords }
                .draggable(
                    state = draggable,
                    orientation = Orientation.Vertical,
                    enabled = !closing,
                    onDragStopped = {
                        if (abs(offsetY.value) > dismissThresholdPx) {
                            requestClose()
                        } else {
                            offsetY.animateTo(0f, settleSpec)
                        }
                    },
                ),
        ) {
            ViewerBackdrop(
                media = items.getOrNull(pagerState.currentPage)?.media,
                backdropAlpha = backgroundAlpha,
                reducedMotion = reducedMotion,
            )
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

            // Playback speed is per viewer session AND per item, unlike quality: it belongs
            // to the page it was picked on ([speedPage]) and drops back to 1× once the pager
            // settles on any other page, so swiping away and back starts at normal speed and
            // a neighbour never inherits it. Keyed on settledPage, not currentPage, so a drag
            // that crosses the half-way mark and springs back doesn't reset the pick. Closing
            // the viewer discards the state; the pool resets the player itself.
            var speedPage by remember(items) { mutableIntStateOf(-1) }
            var speed by remember(items) { mutableFloatStateOf(1f) }
            LaunchedEffect(pagerState.settledPage) {
                if (pagerState.settledPage != speedPage) {
                    speedPage = -1
                    speed = 1f
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (settled && !closing) {
                            translationY = offsetY.value
                            return@graphicsLayer
                        }
                        if (!ready) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        val p = transitionProgress.value
                        val current = leg
                        if (current == null) {
                            val shown = p.coerceIn(0f, 1f)
                            val s = lerp(FADE_START_SCALE, 1f, shown)
                            alpha = shown
                            scaleX = s
                            scaleY = s
                            translationY = offsetY.value
                            return@graphicsLayer
                        }
                        val dragY = if (closing) closeStartDragY * p.coerceIn(0f, 1f) else offsetY.value
                        containerTransform(
                            from = current.rect,
                            cornerRadiusPx = current.cornerRadiusPx,
                            contentAspect = current.aspect,
                            progress = p,
                            dragY = dragY,
                        )
                    },
            ) { page ->
                val item = items[page]
                MediaPage(
                    item = item,
                    isActive = page == pagerState.currentPage,
                    pickedQuality = qualityChoices[page],
                    onQualityPick = { quality -> qualityChoices[page] = quality },
                    // Lambda, so a speed pick recomposes only the player's speed effect,
                    // not every composed page.
                    playbackSpeed = { if (page == speedPage) speed else 1f },
                    onZoomedChange = { zoomed -> zoomedPages[page] = zoomed },
                    // Player chrome would ride the transform as a shrinking overlay.
                    showChrome = settled && !closing,
                )
            }

            // N3 — text scrim under the TOP chrome band (close · counter · tools): the
            // controls paint white over the photo/video, and a white subject (snow,
            // paper, a blown-out sky) swallows white glyphs that rest directly on it.
            // TOP ONLY: photos have no bottom chrome, so a bottom band would dim the
            // image for nothing, and video pages already get a deeper 0→55% scrim from
            // VideoPlayerControls — doubling it there over-darkened the seek bar area.
            //
            // WS-O dismiss fade: the scrim multiplies its gradient by the same
            // [backgroundAlpha] that drives the page scrim, so as the user drags the
            // photo away the band fades out in lockstep with the canvas instead of
            // staying glued to the top edge as a dark sheet riding the gesture.
            //
            // Height = status-bar inset + TOP_SCRIM_HEIGHT, so the band reaches past the
            // bottom of the full right-hand tool column (quality chip + save/share/copy).
            // A fixed 120 dp band measured from the screen top was swallowed by the
            // status bar on tall-inset devices and faded to zero before the second tool
            // button, leaving share/copy on bare image. The plateau stop keeps the band
            // dense through the column before it falls off.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = TOP_SCRIM_ALPHA * backgroundAlpha),
                            TOP_SCRIM_PLATEAU to Color.Black.copy(alpha = TOP_SCRIM_ALPHA * 0.6f * backgroundAlpha),
                            1f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .height(TOP_SCRIM_HEIGHT),
            )

            // Expressive close affordance: Cookie9-shaped backdrop instead of a perfect
            // circle. Reads as "deliberate close" rather than a generic system 'X' —
            // signature shape vocabulary shared with the failed-media retry disc, so media
            // chrome speaks one shape. Bigger touch target padding compensates
            // for the polygon's narrower visual weight.
            val closeShape = HortayExpressive.ViewerCloseBackdrop.asComposeShape()
            // Polygon backdrop only — clipping the IconButton to a Cookie polygon
            // would cut the close glyph at the polygon ridges. The default circular
            // ripple stays clean inside the visible disc area.
            ChromeActionButton(
                onClick = { requestClose() },
                glyph = "close",
                contentDescription = stringResource(R.string.action_close),
                shape = closeShape,
                // 6 dp + the button's 2 dp touch-target slop keeps the visible disc 8 dp in.
                // Chrome fades with the container transform (layer-only read, no
                // recomposition) so controls don't hover over a photo still on its tile.
                modifier = Modifier
                    .graphicsLayer { alpha = transitionProgress.value.coerceIn(0f, 1f) }
                    .statusBarsPadding()
                    .padding(6.dp),
            )

            // Top-right tool column for the active page: QualityChip (videos
            // with alternativeVideos) and SpeedChip (videos) over Save / Copy buttons. One vertical
            // stack avoids the playback chrome conflict the bottom-right
            // placement had — [VideoPlayerControls] owns the entire bottom
            // band (scrim, BottomBar slider+mute, navigationBarsPadding) and
            // any chrome anchored to BottomEnd would land on the slider or
            // mute toggle. Top-right is uncontested in both photo and video
            // pages. We keep the chrome chrome-coloured (CHROME_BACKDROP_ALPHA black, 44 dp
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
            val saveDeniedReason = stringResource(R.string.media_share_error_insert_failed)
            val copiedMsg = stringResource(R.string.media_copied)
            val copyFailedMsg = stringResource(R.string.media_copy_failed)
            val shareFailedMsg = stringResource(R.string.media_share_failed)

            fun runSave(item: AlbumItem, path: String) {
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        MediaShareActions.saveToGallery(actionContext, item, path)
                    }
                    val successMsg = if (item is AlbumItem.Photo) savedPhotoMsg else savedVideoMsg
                    val toast = when (res) {
                        is MediaShareActions.Result.Success -> successMsg
                        is MediaShareActions.Result.Failure ->
                            saveFailedMsg.format(
                                actionContext.resources.getString(res.reasonResId, *res.args.toTypedArray()),
                            )
                    }
                    Toast.makeText(actionContext, toast, Toast.LENGTH_SHORT).show()
                }
            }

            // API 26-28 save through the legacy public-directory path, which needs the
            // runtime WRITE_EXTERNAL_STORAGE grant (manifest caps it at maxSdkVersion 28;
            // API 29+ MediaStore inserts need no permission). The tapped item + path are
            // parked while the system dialog is up; denial reuses the save-failed toast.
            var pendingLegacySave by remember { mutableStateOf<Pair<AlbumItem, String>?>(null) }
            val legacyWriteLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val pending = pendingLegacySave ?: return@rememberLauncherForActivityResult
                pendingLegacySave = null
                if (granted) {
                    runSave(pending.first, pending.second)
                } else {
                    Toast.makeText(
                        actionContext,
                        saveFailedMsg.format(saveDeniedReason),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

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
            // Speed applies to real videos only: GIF-style animations loop silently and
            // photos don't play, so neither gets the chip.
            val showSpeed = activeItem is AlbumItem.Video
            val persistableItem = activeItem.takeIf { readyPath != null && !protectedContent }
            val shareCapable = !protectedContent && (readyPath != null || !webShareUrl.isNullOrBlank())

            if (showQuality || showSpeed || persistableItem != null || shareCapable) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer { alpha = transitionProgress.value.coerceIn(0f, 1f) }
                        .statusBarsPadding()
                        // Padding and spacing are 2 / 4 dp tighter than the visible rhythm
                        // (8 dp inset, 10 dp gaps): each 44 dp button reserves a 48 dp touch
                        // target, and that slop makes up the difference.
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    if (showSpeed) {
                        SpeedChip(
                            current = if (speedPage == pagerState.currentPage) speed else 1f,
                            onPick = {
                                speedPage = pagerState.currentPage
                                speed = it
                            },
                        )
                    }
                    val chromeShape = CircleShape
                    if (persistableItem != null && readyPath != null) {
                        val activeItem = persistableItem // smart-cast bridge for lambdas
                        // Save → every Ready media kind. Toast on success / failure.
                        ChromeActionButton(
                            onClick = {
                                val needsLegacyGrant = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    ContextCompat.checkSelfPermission(
                                        actionContext,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ) != PackageManager.PERMISSION_GRANTED
                                if (needsLegacyGrant) {
                                    pendingLegacySave = activeItem to readyPath
                                    legacyWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    runSave(activeItem, readyPath)
                                }
                            },
                            glyph = "download",
                            contentDescription = saveLabel,
                            shape = chromeShape,
                        )
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
                        ChromeActionButton(
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
                                    // Success surfaces as the system share chooser
                                    // sheet appearing — its own visible confirmation,
                                    // so only failures need a toast (a success toast
                                    // would fight the chooser that just opened).
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
                            glyph = "share",
                            contentDescription = shareLabel,
                            shape = chromeShape,
                        )
                    }
                    if (persistableItem != null && readyPath != null && persistableItem is AlbumItem.Photo) {
                        val activeItem = persistableItem
                        // Copy → photo only. Nearly no Android app meaningfully
                        // accepts a video clipboard item, and a multi-MB MP4 URI
                        // on the clipboard is a UX trap (paste into WhatsApp =
                        // silent re-upload). Hidden for video / animation pages.
                        ChromeActionButton(
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
                            glyph = "content_copy",
                            contentDescription = copyLabel,
                            shape = chromeShape,
                        )
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
                    // Tabular figures so the "3 / 5" counter keeps a fixed width as the
                    // user swipes through a 10+ item album (1→10 wouldn't shift the
                    // centred pill, but the digits stay optically aligned).
                    style = MaterialTheme.typography.labelLarge.tabularFigures(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = transitionProgress.value.coerceIn(0f, 1f) }
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = CHROME_BACKDROP_ALPHA), counterShape)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Chrome icon button for the viewer (close · save · share · copy). A 44 dp disc with a
 * black-translucent polygon backdrop and a white glyph — the established viewer chrome
 * vocabulary. Adds an instant pressed-scale acknowledgement on top of the default ripple
 * (doctrine rule 1: every tap answers within ~100 ms) so a save/share that does its work
 * off the main thread still feels responsive the moment the finger lands. The scale rides
 * the same MotionScheme spatial spring as the rest of the app and collapses to an instant
 * snap under "Remove animations".
 */
@Composable
private fun ChromeActionButton(
    onClick: () -> Unit,
    glyph: String,
    contentDescription: String,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val pressSpec: AnimationSpec<Float> =
        if (reducedMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = pressSpec,
        label = "chrome-press",
    )
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(Color.Black.copy(alpha = CHROME_BACKDROP_ALPHA), shape),
    ) {
        Symbol(name = glyph, contentDescription = contentDescription, tint = Color.White)
    }
}

/**
 * Container transform for the pager layer at [progress] (0 = on the tapped tile, 1 =
 * fullscreen; values past 1 are the spatial spring's overshoot). Content inside the layer
 * is the page as laid out fullscreen — the photo `ContentScale.Fit` into the container —
 * so the transform scales that fitted frame down to COVER the tile (the feed crops tiles)
 * and clips it to the tile's rounded rect, then interpolates both toward identity. The
 * clip is expressed in the layer's local (pre-transform) space, so it rides the scale and
 * the drag translation with the photo.
 */
private fun GraphicsLayerScope.containerTransform(
    from: Rect,
    cornerRadiusPx: Float,
    contentAspect: Float,
    progress: Float,
    dragY: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) {
        alpha = 0f
        return
    }
    val fitW: Float
    val fitH: Float
    if (w / h > contentAspect) {
        fitH = h
        fitW = h * contentAspect
    } else {
        fitW = w
        fitH = w / contentAspect
    }
    val toLeft = (w - fitW) / 2f
    val toTop = (h - fitH) / 2f
    val visible = Rect(
        left = lerp(from.left, toLeft, progress),
        top = lerp(from.top, toTop, progress),
        right = lerp(from.right, toLeft + fitW, progress),
        bottom = lerp(from.bottom, toTop + fitH, progress),
    )
    val coverScale = max(from.width / fitW, from.height / fitH)
    val s = lerp(coverScale, 1f, progress).coerceAtLeast(MIN_TRANSFORM_SCALE)
    // Fitted photo is centred in the container; pin its centre to the visible rect's centre.
    val tx = visible.center.x - (w / 2f) * s
    val ty = visible.center.y - (h / 2f) * s
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = s
    scaleY = s
    translationX = tx
    translationY = ty + dragY
    val radius = lerp(cornerRadiusPx, 0f, progress).coerceAtLeast(0f)
    shape = ViewerClipShape(
        rect = Rect(
            left = (visible.left - tx) / s,
            top = (visible.top - ty) / s,
            right = (visible.right - tx) / s,
            bottom = (visible.bottom - ty) / s,
        ),
        radius = radius / s,
    )
    clip = true
}

/** One open or close leg of the container transform: the tile it runs from or to. */
private class TransitionLeg(val rect: Rect, val cornerRadiusPx: Float, val aspect: Float)

/** Rounded clip at an arbitrary rect inside the layer — the container transform's window. */
private class ViewerClipShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * Viewer canvas behind the letterboxed media: instead of flat black, the active item's
 * inline minithumbnail (a ~40 px JPEG TDLib ships with the message, already in memory —
 * no network, no full-file decode) stretched edge to edge, heavily blurred and dimmed so
 * the bars pick up the photo's colours. API 31+ blurs through RenderEffect; below that
 * Modifier.blur is a no-op, so the canvas takes the minithumb's average colour instead.
 * Items without a minithumb (guest-mode URLs, some small uploads) keep plain black.
 *
 * The dim layer keeps the brightest possible backdrop at ~18 % of the source, under the
 * top chrome scrim and the controls' own backplates. The whole canvas — black base
 * included — fades through one [backdropAlpha] layer, the drag-to-dismiss / container
 * transform alpha, so it never double-composites while fading.
 */
@Composable
private fun ViewerBackdrop(media: TdMedia?, backdropAlpha: Float, reducedMotion: Boolean) {
    val bytes = media?.minithumbBytes
    val backdrop by produceState<BackdropSource?>(initialValue = null, bytes) {
        value = if (bytes == null) null else withContext(Dispatchers.Default) { decodeBackdrop(bytes) }
    }
    val fadeSpec: FiniteAnimationSpec<Float> =
        if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = backdropAlpha }
            .background(Color.Black),
    ) {
        Crossfade(
            targetState = backdrop,
            animationSpec = fadeSpec,
            modifier = Modifier.fillMaxSize(),
            label = "viewer-backdrop",
        ) { src ->
            when {
                src == null -> Unit
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Image(
                    bitmap = src.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(BACKDROP_BLUR_RADIUS, BlurredEdgeTreatment.Rectangle),
                )
                else -> Box(modifier = Modifier.fillMaxSize().background(src.averageColor))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = BACKDROP_DIM_ALPHA)),
        )
    }
}

private class BackdropSource(val bitmap: ImageBitmap, val averageColor: Color)

private fun decodeBackdrop(bytes: ByteArray): BackdropSource? = runCatching {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
    val pixel = Bitmap.createScaledBitmap(bmp, 1, 1, true)
    val average = Color(pixel.getPixel(0, 0))
    if (pixel !== bmp) pixel.recycle()
    BackdropSource(bmp.asImageBitmap(), average)
}.getOrNull()

@Composable
private fun MediaPage(
    item: AlbumItem,
    isActive: Boolean,
    pickedQuality: VideoQuality?,
    onQualityPick: (VideoQuality) -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    showChrome: Boolean,
    playbackSpeed: () -> Float,
) {
    when (item) {
        is AlbumItem.Photo -> ZoomableImage(item, onZoomedChange)
        is AlbumItem.Video -> {
            val quality = pickedQuality ?: item.qualities.defaultPick
            // Fullscreen video plays WITH sound once its page is active; silence an inline
            // voice note / audio track first so the two never talk over each other.
            val audioSession = LocalAudioPlaybackSession.current
            LaunchedEffect(isActive) { if (isActive) audioSession.yieldToVideo() }
            VideoSurfaceWithPoster(item) { onFirstFrame ->
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
                    showControls = showChrome,
                    priority = DownloadPriority.Foreground,
                    initialAspect = item.posterAspect(),
                    onFirstFrameRendered = onFirstFrame,
                    playbackSpeed = playbackSpeed(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Touch the picker callback so an unpicked default still registers — keeps
            // the parent's qualityChoices map authoritative for "what's playing now".
            if (pickedQuality == null) {
                LaunchedEffect(item.playbackFileId, item.qualities) { onQualityPick(quality) }
            }
        }
        is AlbumItem.Animation -> VideoSurfaceWithPoster(item) { onFirstFrame ->
            TdVideoPlayer(
                fileId = item.playbackFileId,
                remoteUrl = item.remoteVideoUrl,
                autoPlay = isActive,
                autoLoop = true,
                showControls = false,
                muted = true,
                priority = DownloadPriority.Foreground,
                initialAspect = item.posterAspect(),
                onFirstFrameRendered = onFirstFrame,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * L6 — video first-frame flash. [TdVideoPlayer] renders into a `TextureView` set
 * `isOpaque = false`, so it blends transparently right up to the first decoded frame —
 * but the fullscreen viewer composites nothing behind it, leaving the black scrim
 * showing through during the prepare/decode window (the visible "flash" before the
 * first frame). Compositing the item's poster ([AlbumItem.media], the same down-sampled
 * first frame the inline feed paints — typically already Ready in [MediaCache]) under the
 * transparent surface means the page paints the poster on the very first frame.
 *
 * Once ExoPlayer reports its first decoded frame (the [TdVideoPlayer.onFirstFrameRendered]
 * hook, handed to [content] as a plain callback), the poster crossfades out on
 * `fastEffectsSpec` and leaves the composition — the page doesn't pay full-screen poster
 * overdraw for the rest of the playback. A quality switch re-fires the signal; by then
 * the poster is long gone, which is the correct no-op.
 */
@Composable
private fun VideoSurfaceWithPoster(
    item: AlbumItem,
    content: @Composable (onFirstFrame: () -> Unit) -> Unit,
) {
    var firstFrameRendered by remember(item) { mutableStateOf(false) }
    val posterAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "viewer-poster-fade",
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (posterAlpha > 0f) {
            TdMediaImage(
                media = item.media,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // No progress chrome / placeholder fill: the video player owns the
                // download + buffering affordances, and a grey letterbox fill here
                // would ride the drag-dismiss translation as a "white sheet glued to
                // the video" — the same artefact the photo path's null placeholder
                // avoids.
                showProgress = false,
                placeholderColor = null,
                priority = DownloadPriority.Foreground,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = posterAlpha },
            )
        }
        content { firstFrameRendered = true }
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
private fun ZoomableImage(item: AlbumItem.Photo, onZoomedChange: (Boolean) -> Unit) {
    // Animatable trio: pinch updates flow through `snapTo` for instant feel, and
    // the double-tap handler uses `animateTo` so the transition reads as a
    // deliberate Telegram-style zoom-in instead of a single-frame jump. Keying
    // by [item.fullscreen.fileId] resets state when the user pages to a new
    // photo — no carry-over zoom across pager pages.
    val scale = remember(item.fullscreen.fileId) { Animatable(1f) }
    val offsetX = remember(item.fullscreen.fileId) { Animatable(0f) }
    val offsetY = remember(item.fullscreen.fileId) { Animatable(0f) }
    // Zoomed-or-not feeds the viewer's close decision: a zoomed photo can't shrink back
    // onto its tile (the tile shows the whole frame), so close falls back to the plain exit.
    val currentOnZoomedChange by rememberUpdatedState(onZoomedChange)
    LaunchedEffect(scale) {
        snapshotFlow { scale.value > 1f }.collect { currentOnZoomedChange(it) }
    }
    val scope = rememberCoroutineScope()
    // Reduced motion: double-tap zoom jumps to its target instead of springing. Pinch
    // already uses snapTo, so only the double-tap animateTo path needs neutralising.
    val reducedMotion = rememberReducedMotion()
    val scaleSpec: AnimationSpec<Float> =
        if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy)
    val offsetSpec: AnimationSpec<Float> = if (reducedMotion) snap() else spring()
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
                            launch { scale.animateTo(1f, scaleSpec) }
                            launch { offsetX.animateTo(0f, offsetSpec) }
                            launch { offsetY.animateTo(0f, offsetSpec) }
                        } else {
                            val target = DOUBLE_TAP_SCALE
                            val cx = (containerSize.width / 2f - tap.x) * (target - 1f)
                            val cy = (containerSize.height / 2f - tap.y) * (target - 1f)
                            launch {
                                offsetX.animateTo(
                                    cx.coerceIn(-maxOffsetX(target), maxOffsetX(target)),
                                    offsetSpec,
                                )
                            }
                            launch {
                                offsetY.animateTo(
                                    cy.coerceIn(-maxOffsetY(target), maxOffsetY(target)),
                                    offsetSpec,
                                )
                            }
                            scale.animateTo(target, scaleSpec)
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
            // No placeholder fill in the viewer. The default `surfaceContainerHigh`
            // is a light grey that paints the entire `ContentScale.Fit` letterbox
            // (and the Box itself, which sits inside the translating HorizontalPager).
            // On swipe-to-dismiss that grey sheet rides along with the photo and reads
            // as "a white background is glued to the photo". The minithumb under the
            // Coil crossfade already covers the fullscreen-download window, and
            // anything beyond its bounds shows the swipe-fading black scrim — exactly
            // the Twitter / Instagram "fading away" feel.
            placeholderColor = null,
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

// N3 top scrim opacity ("Color.Black 0 → ~35%") — the band that carries the
// close/counter/tools chrome. There is deliberately NO viewer-level bottom band:
// photos have no bottom chrome to protect, and VideoPlayerControls paints its own
// deeper 0→55 % scrim for the seek bar.
private const val TOP_SCRIM_ALPHA = 0.35f

// Band height BELOW the status-bar inset: 6 dp column inset + four 48 dp touch targets
// (the tallest column: a video's quality and speed chips over save / share; a photo
// stacks only save / share / copy) + gaps ≈ 216 dp, plus a fall-off tail so
// the last button still sits on the dense part of the gradient.
private val TOP_SCRIM_HEIGHT = 260.dp

// Fraction of the band that holds near-full density before fading to transparent.
private const val TOP_SCRIM_PLATEAU = 0.75f

// Backdrop disc / pill alpha for every viewer control (close, save, share, copy,
// counter, quality and speed chips). Over a pure-white image 55 % black gives a #737373 plate — ~4.7:1 against
// the white glyph, above WCAG's 3:1 for icons with margin for the scrim fading out
// during the dismiss drag. The former 45 % (#8C8C8C, ~3.4:1) sat right at the floor.
internal const val CHROME_BACKDROP_ALPHA = 0.55f

// Backdrop: blur applied to the minithumb stretched fullscreen, and the black veil over it.
// 82 % black caps the backdrop at ~18 % of the source brightness: at 65 % a white photo's
// bars came out a muddy mid-grey on device.
private val BACKDROP_BLUR_RADIUS = 48.dp

// Frames the viewer's copy of the media and the tile overlap when one hands over to the
// other (the tile is hidden after an open, shown again before the window leaves).
private const val TILE_HANDOFF_FRAMES = 2

// Scale the pager starts from (and ends at) when it fades instead of transforming.
private const val FADE_START_SCALE = 0.94f
private const val BACKDROP_DIM_ALPHA = 0.82f

// Pager offset under which the start page counts as settled for the shrink-back close.
private const val PAGE_SETTLED_FRACTION = 0.01f

// Floor for the container-transform scale (guards a divide-by-zero on a degenerate tile).
private const val MIN_TRANSFORM_SCALE = 0.01f

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
