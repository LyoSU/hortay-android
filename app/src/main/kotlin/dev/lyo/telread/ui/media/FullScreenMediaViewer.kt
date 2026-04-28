package dev.lyo.telread.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lyo.telread.data.AlbumItem

/**
 * Full-screen, gesture-driven media viewer. Photos pinch-zoom + pan; videos and animations
 * stream through ExoPlayer. Horizontal swipe between items; status bar dimmed.
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

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
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
            .pointerInput(item.media.fileId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
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
