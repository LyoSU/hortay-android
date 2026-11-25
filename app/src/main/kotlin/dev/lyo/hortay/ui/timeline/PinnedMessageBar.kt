package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.PinnedPreview
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.theme.mediaFrame
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * Telegram-style pinned bar under the channel header: a segmented rail, a "Pinned message"
 * label (with its position when the channel has several) and a one-line excerpt or media
 * kind, plus a thumbnail when the pinned post has one.
 *
 * The bar sits on its own `surfaceContainerLow` fill with a faint bottom hairline: drawn
 * straight on the canvas, the first post's media under it looked torn off at the bar's
 * edge. The rail shows one segment per pin (at most [MAX_RAIL_SEGMENTS]; beyond that a
 * segment stands for a run of pins), the shown pin's segment in `primary`, and that
 * highlight slides to the next segment on each step. The title already says "pinned", so
 * the former trailing pin glyph was dropped; the thumbnail stays.
 *
 * Tapping jumps to [pinned] and the host advances to the next older pin, so repeated taps
 * walk the whole set like the official client. [position] is 1-based, newest first.
 */
@Composable
internal fun PinnedMessageBar(
    pinned: PinnedPreview,
    position: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val railIdle = MaterialTheme.colorScheme.outlineVariant
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HAIRLINE_ALPHA)
    val segments = total.coerceIn(1, MAX_RAIL_SEGMENTS)
    val activeSegment = if (total <= MAX_RAIL_SEGMENTS) {
        (position - 1).coerceIn(0, segments - 1)
    } else {
        // More pins than segments: map the position proportionally, newest at the top.
        ((position - 1) * segments / total).coerceIn(0, segments - 1)
    }
    val reducedMotion = rememberReducedMotion()
    val activeAnim = animateFloatAsState(
        targetValue = activeSegment.toFloat(),
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pinned-rail",
    )
    val title = if (total > 1) {
        stringResource(R.string.pinned_bar_title_position, position, total)
    } else {
        stringResource(R.string.pinned_bar_title)
    }
    val openLabel = stringResource(R.string.pinned_bar_open)
    val body = pinned.excerpt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: pinned.mediaKind.label()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = hairline,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$openLabel. $title. $body" }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                // Draw-phase only: the sliding highlight never recomposes the bar.
                .drawBehind {
                    val gap = if (segments > 1) RAIL_GAP.toPx() else 0f
                    val segH = (size.height - gap * (segments - 1)) / segments
                    val radius = CornerRadius(size.width / 2, size.width / 2)
                    for (i in 0 until segments) {
                        drawRoundRect(
                            color = railIdle,
                            topLeft = Offset(0f, i * (segH + gap)),
                            size = Size(size.width, segH),
                            cornerRadius = radius,
                        )
                    }
                    drawRoundRect(
                        color = accent,
                        // Clamped: the spatial spring's overshoot must not poke past the rail's ends.
                        topLeft = Offset(0f, activeAnim.value.coerceIn(0f, segments - 1f) * (segH + gap)),
                        size = Size(size.width, segH),
                        cornerRadius = radius,
                    )
                },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned.excerpt.isBlank()) {
                    pinned.mediaKind.symbolName()?.let { name ->
                        Symbol(
                            name = name,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 14.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        pinned.mediaThumb?.let { thumb ->
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .mediaFrame(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                TdMediaImage(
                    media = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Most rail segments drawn; more pins than this share segments. */
private const val MAX_RAIL_SEGMENTS = 4
private val RAIL_WIDTH = 3.dp
private val RAIL_GAP = 2.dp

/** Bottom hairline under the bar: marks the edge without reading as a divider. */
private const val HAIRLINE_ALPHA = 0.4f
