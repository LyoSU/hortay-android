package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichCaption
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.mediaFrame

/**
 * "Media unavailable" fallback for a media-bearing block whose file handle is `null` (TDLib
 * delivered the block without a resolvable file). The real renderings live in
 * [dev.lyo.hortay.ui.rich.RichPhoto] / [RichVideo] / [RichCollage] / … which fall back here
 * when their projected [AlbumItem] list is empty. Reads as an empty media slot — a transparent
 * box carrying the shared [mediaFrame] hairline (same idiom as real media), NOT a filled card —
 * with a kind-appropriate icon and the block's caption text if present.
 */
@Composable
internal fun RichMediaPlaceholder(
    icon: String,
    caption: RichCaption?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .mediaFrame(shape)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(
            name = icon,
            size = 22.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = stringResource(R.string.rich_media_content_description),
        )
        val captionText = caption?.text
        if (captionText != null) {
            RichInlineText(
                inline = captionText,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
