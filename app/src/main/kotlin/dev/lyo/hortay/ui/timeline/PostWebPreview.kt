package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.WebPreview
import dev.lyo.hortay.data.WebPreviewKind
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.theme.mediaFrame

/**
 * Web link preview card — Twitter / Telegram-X style.
 *
 * Three render modes, picked from the [preview] payload:
 *
 *   1. Compact + image — leading 72.dp thumbnail, metadata column on the right.
 *      Used for plain article links and any preview with `showLargeMedia=false`.
 *   2. Compact + no image — a 48.dp icon tile keyed off [WebPreviewKind] takes
 *      the thumbnail slot, so chat / sticker / gift / story / etc. previews
 *      still read as more than "untitled link" with an empty box.
 *   3. Large media — image rendered full-width above or below the metadata
 *      (`showMediaAboveDescription` flips the order). Aspect ratio comes from
 *      the image payload, clamped to a readable range so extreme verticals
 *      don't take over the feed.
 *
 * Tap anywhere on the card opens [WebPreview.url] in the system handler. No
 * separate media-open path — link previews are link affordances, not media
 * affordances, even when they ship a video thumbnail. The user's expectation
 * is "tap → leave the app to the source", same as Telegram-Android's own
 * link-preview behaviour.
 */
@Composable
internal fun WebPreviewCard(preview: WebPreview) {
    val uriHandler = LocalUriHandler.current
    val onClick = {
        if (preview.url.isNotBlank()) runCatching { uriHandler.openUri(preview.url) }
        Unit
    }
    val showLarge = preview.image != null && preview.showLargeMedia
    if (showLarge) {
        LargeWebPreview(preview, onClick = onClick)
    } else {
        CompactWebPreview(preview, onClick = onClick)
    }
}

@Composable
private fun CompactWebPreview(preview: WebPreview, onClick: () -> Unit) {
    WebPreviewQuoteFrame(enabled = preview.url.isNotBlank(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            WebPreviewLeading(preview)
            Spacer(Modifier.width(12.dp))
            WebPreviewMetadata(preview, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LargeWebPreview(preview: WebPreview, onClick: () -> Unit) {
    WebPreviewQuoteFrame(enabled = preview.url.isNotBlank(), onClick = onClick) {
        Column {
            val media = @Composable { LargeWebPreviewMedia(preview) }
            val meta = @Composable { WebPreviewMetadata(preview, modifier = Modifier.fillMaxWidth()) }
            if (preview.showMediaAboveDescription) {
                media()
                Spacer(Modifier.height(10.dp))
                meta()
            } else {
                meta()
                Spacer(Modifier.height(10.dp))
                media()
            }
        }
    }
}

/**
 * Web-preview cards share the block-quote silhouette: a left [accent] bar over a
 * `primary @ 10%` tint with [MaterialTheme.shapes.extraSmall] corners — the same look as
 * [dev.lyo.hortay.ui.text.RichText]'s quote BlockBox, so a pulled-in link reads as the same
 * kind of reference as a quote. The bar is painted with `drawBehind` (full node height,
 * clipped to the rounded silhouette) rather than a `fillMaxHeight` child under
 * [IntrinsicSize.Min]: the large layout nests a `fillMaxWidth().aspectRatio()` image, and
 * intrinsic-height measurement of that combination is fragile — drawing the bar sidesteps it
 * and always matches the real content height in both the compact and large layouts.
 */
@Composable
private fun WebPreviewQuoteFrame(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.10f))
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 13.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun LargeWebPreviewMedia(preview: WebPreview) {
    val image = preview.image
    if (image == null) {
        // Defensive: showLargeMedia=true but no image — fall back to icon
        // tile sized like the large slot so layout doesn't collapse.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            WebPreviewIcon(preview.kind, size = 48.dp, onContainer = true)
        }
        return
    }
    val ratio = webPreviewLargeAspect(image.width, image.height)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.small)
            // D1 — large web-preview image is rectangular photographic content nested
            // inside the quote-frame card, so it carries the hairline frame on its
            // nested-tile shape (shapes.small).
            .mediaFrame(MaterialTheme.shapes.small),
    ) {
        TdMediaImage(
            media = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        // Kind-specific badge (play badge for video / animation) so the user
        // knows what tap will open.
        WebPreviewKindBadge(preview.kind)
    }
}

@Composable
private fun WebPreviewLeading(preview: WebPreview) {
    val image = preview.image
    if (image != null) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.small)
                // D1 — compact-preview leading thumbnail (rectangular photographic).
                .mediaFrame(MaterialTheme.shapes.small),
        ) {
            TdMediaImage(
                media = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            WebPreviewIcon(preview.kind, size = 22.dp, onContainer = true)
        }
    }
}

@Composable
private fun WebPreviewMetadata(preview: WebPreview, modifier: Modifier = Modifier) {
    val label = preview.siteName.ifBlank { preview.displayUrl }.ifBlank { preview.url }
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.title.isNotBlank()) {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Author rendered as a tertiary label only when it carries new info
        // (i.e., distinct from siteName) — Telegram occasionally ships
        // `author == siteName` for blog posts and rendering both would
        // visually duplicate the host line.
        if (preview.author.isNotBlank() && !preview.author.equals(preview.siteName, ignoreCase = true)) {
            Text(
                text = preview.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WebPreviewIcon(kind: WebPreviewKind, size: Dp = 22.dp, onContainer: Boolean = false) {
    Symbol(
        name = webPreviewSymbol(kind),
        tint = if (onContainer) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        size = size,
    )
}

@Composable
private fun BoxScope.WebPreviewKindBadge(kind: WebPreviewKind) {
    when (kind) {
        WebPreviewKind.Video, WebPreviewKind.Animation -> Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(name = "play_circle", tint = Color.White, size = 36.dp)
        }
        else -> Unit
    }
}

/**
 * Map a [WebPreviewKind] to a [Symbol] name that already exists in the icon
 * registry. Falls back to `info` for unsupported kinds — `info` is mapped in
 * [Symbol], so we avoid the silent `sym_help` fallback that would otherwise
 * mark every unknown preview with a question mark.
 */
private fun webPreviewSymbol(kind: WebPreviewKind): String = when (kind) {
    WebPreviewKind.Article -> "open_in_new"
    WebPreviewKind.Photo -> "image"
    WebPreviewKind.Video -> "play_circle"
    WebPreviewKind.Animation -> "gif_box"
    WebPreviewKind.Audio -> "audio_file"
    WebPreviewKind.Document -> "description"
    WebPreviewKind.Album -> "image"
    WebPreviewKind.App -> "open_in_new"
    WebPreviewKind.Chat -> "forum"
    WebPreviewKind.User -> "person"
    WebPreviewKind.Sticker, WebPreviewKind.StickerSet -> "image"
    WebPreviewKind.Story -> "visibility"
    WebPreviewKind.WebApp -> "open_in_new"
    WebPreviewKind.Gift -> "card_giftcard"
    WebPreviewKind.Invoice -> "description"
    WebPreviewKind.Theme -> "image"
    WebPreviewKind.External -> "open_in_new"
    WebPreviewKind.Unsupported -> "info"
}

/**
 * Clamp the large-media aspect ratio. Below `4/3` and above `21/9` the card
 * starts to dominate the feed (extreme verticals push the next post out of
 * sight, extreme horizontals leave the metadata orphaned in a thin strip).
 * Default `16/10` when TDLib didn't ship dimensions.
 */
private fun webPreviewLargeAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 10f
    val raw = width.toFloat() / height.toFloat()
    return raw.coerceIn(4f / 3f, 21f / 9f)
}
