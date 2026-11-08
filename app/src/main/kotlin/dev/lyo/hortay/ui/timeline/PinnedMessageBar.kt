package dev.lyo.hortay.ui.timeline

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Telegram-style pinned bar under the channel header: an accent rail, a "Pinned message"
 * label (with its position when the channel has several) and a one-line excerpt or media
 * kind, plus a thumbnail when the pinned post has one.
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
            .height(IntrinsicSize.Min)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$openLabel. $title. $body" }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
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
        Spacer(Modifier.width(8.dp))
        Symbol(
            name = "push_pin",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}
