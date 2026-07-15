package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichCaption
import dev.lyo.hortay.ui.icons.Symbol

/**
 * "Media unavailable" fallback for a media-bearing block whose file handle is `null` (TDLib
 * delivered the block without a resolvable file). The real renderings live in
 * [dev.lyo.hortay.ui.rich.RichPhoto] / [RichVideo] / [RichCollage] / … which fall back here
 * when their projected [AlbumItem] list is empty. Renders a subtle rounded box with a
 * kind-appropriate icon and the block's caption text if present.
 */
@Composable
internal fun RichMediaPlaceholder(
    icon: String,
    caption: RichCaption?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(
                    name = icon,
                    size = 22.dp,
                    contentDescription = stringResource(R.string.rich_media_content_description),
                )
            }
            val captionText = caption?.text
            if (captionText != null) {
                RichInlineText(
                    inline = captionText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
