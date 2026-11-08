package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * A position indicator rendered OVER media, inside a translucent scrim pill so it stays legible on
 * any photo — shared by the rich-message slideshow (`RichSlideshow`) and the feed's swipe-scrolling
 * [AlbumRow]. Up to [MEDIA_PILL_DOT_LIMIT] items show a dot row (the [current] one widens to an
 * accent lozenge); past that a compact "n / total" counter takes over so a long strip doesn't grow
 * an unreadable stripe of dots.
 *
 * Overlay it bottom-centre on the media; the caller drives [current] from whichever item is most
 * visible (a pager's current page, or a LazyRow's centred index).
 */
@Composable
internal fun MediaPositionPill(current: Int, count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (count > MEDIA_PILL_DOT_LIMIT) {
            Text(
                text = stringResource(R.string.rich_slideshow_counter, current + 1, count),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        } else {
            MediaPositionDots(
                count = count,
                selected = current,
                activeColor = Color.White,
                idleColor = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

/**
 * Position dots UNDER the feed's [AlbumRow], on the card surface rather than over the photos.
 * A dot pill overlaid at the strip's centre sat on the seam between two peeking photos and
 * its dark scrim blended into them; below the strip the dots read as the album's own
 * pager, the way Instagram and Threads show them. Past [MEDIA_PILL_DOT_LIMIT] items the row
 * becomes an "n / total" counter.
 */
@Composable
internal fun AlbumPositionDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (count > MEDIA_PILL_DOT_LIMIT) {
            Text(
                text = stringResource(R.string.rich_slideshow_counter, current + 1, count),
                style = MaterialTheme.typography.labelMedium.tabularFigures(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MediaPositionDots(
                count = count,
                selected = current,
                activeColor = MaterialTheme.colorScheme.primary,
                idleColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Beyond this item count the dot row is replaced by a compact "n / total" counter. */
internal const val MEDIA_PILL_DOT_LIMIT = 6

private val DOT_SIZE = 6.dp
private val DOT_SELECTED_WIDTH = 16.dp

@Composable
private fun MediaPositionDots(count: Int, selected: Int, activeColor: Color, idleColor: Color) {
    val reducedMotion = rememberReducedMotion()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(
                targetValue = if (active) DOT_SELECTED_WIDTH else DOT_SIZE,
                // Reduced motion snaps the active-dot lozenge instead of animating its width.
                animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "media-position-dot",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = DOT_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (active) activeColor else idleColor,
                    ),
            )
        }
    }
}
