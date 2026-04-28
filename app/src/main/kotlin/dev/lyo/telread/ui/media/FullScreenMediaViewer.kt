package dev.lyo.telread.ui.media

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lyo.telread.data.AlbumItem
import kotlinx.coroutines.launch
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
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offsetY.value },
            ) { page ->
                MediaPage(item = items[page], isActive = page == pagerState.currentPage)
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "close", tint = Color.White)
            }

            if (items.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaPage(item: AlbumItem, isActive: Boolean) {
    when (item) {
        is AlbumItem.Photo -> ZoomableImage(item)
        is AlbumItem.Video -> TdVideoPlayer(
            fileId = item.playbackFileId,
            autoPlay = isActive,
            autoLoop = false,
            showControls = true,
            modifier = Modifier.fillMaxSize(),
        )
        is AlbumItem.Animation -> TdVideoPlayer(
            fileId = item.playbackFileId,
            autoPlay = isActive,
            autoLoop = true,
            showControls = false,
            muted = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ZoomableImage(item: AlbumItem.Photo) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Custom transform: only consume the gesture when there's an active pinch (≥2
            // fingers) or the photo is already zoomed in (pan within image). One-finger drag
            // at scale==1 is left unconsumed so the outer `Modifier.draggable` (the swipe-to-
            // dismiss handler) can pick it up. Without this branch, every swipe was eaten by
            // the transform handler and only video pages dismissed.
            .pointerInput(item.media.fileId) {
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
        TdMediaImage(
            media = item.media,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
