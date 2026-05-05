package dev.lyo.hortay.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import dev.lyo.hortay.data.web.ResolvedEmoji
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebMedia
import dev.lyo.hortay.data.web.WebPost
import dev.lyo.hortay.data.web.WebPreview
import dev.lyo.hortay.data.web.WebReaction
import dev.lyo.hortay.data.web.WebTextContent
import dev.lyo.hortay.data.web.WebTextRenderer

/**
 * Visual card for a single [WebPost]. Shared between the production
 * [WebTimelineScreen] and the debug fetch screen so both surface identical
 * formatting / media handling. Decoupled from any specific data source — the
 * caller passes the post and an optional channel header to render above it.
 *
 * Why a separate card from TDLib-mode `PostCard`: web-mode posts have a
 * fundamentally different shape (no formatted-text entities, no album ids, no
 * sender beyond the channel) and stretching one Composable to handle both would
 * either bloat its parameters or fork on every render — neither lends itself
 * well to Compose stability inference.
 */
@Composable
fun WebPostCard(
    post: WebPost,
    emojiResolver: WebCustomEmojiResolver,
    modifier: Modifier = Modifier,
    channelTitle: String? = null,
    channelAvatarUrl: String? = null,
    onCardClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .let { if (onCardClick != null) it.clickable { onCardClick() } else it }
                .padding(12.dp),
        ) {
            if (channelTitle != null) {
                ChannelRow(channelTitle, channelAvatarUrl, post.publishedAt)
                Spacer(Modifier.height(6.dp))
            } else {
                Text(
                    text = post.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (post.forwardedFrom != null) {
                Text(
                    text = "↪ ${post.forwardedFrom.channelName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            val content = remember(post.textHtml) {
                WebTextRenderer.render(post.textHtml, linkStyles = linkStyles)
            }
            if (content.isNotEmpty) {
                WebPostText(
                    content = content,
                    emojiResolver = emojiResolver,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            post.media.forEach { media ->
                Spacer(Modifier.height(6.dp))
                WebMediaPreview(media)
            }
            if (post.webPreview != null) {
                Spacer(Modifier.height(6.dp))
                WebPreviewCard(post.webPreview)
            }
            FooterRow(post, emojiResolver)
        }
    }
}

@Composable
private fun ChannelRow(title: String, avatarUrl: String?, publishedAt: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        Column(modifier = Modifier.padding(start = 0.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = publishedAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun WebMediaPreview(media: WebMedia, modifier: Modifier = Modifier) {
    when (media.kind) {
        WebMedia.Kind.Photo, WebMedia.Kind.Sticker -> AsyncImage(
            model = media.url,
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .let { mod -> if (media.aspectRatio != null) mod.aspectRatio(media.aspectRatio) else mod }
                .clip(RoundedCornerShape(12.dp)),
        )

        WebMedia.Kind.Video,
        WebMedia.Kind.RoundVideo,
        WebMedia.Kind.Gif -> Box(
            modifier = modifier
                .fillMaxWidth()
                .let { mod -> if (media.aspectRatio != null) mod.aspectRatio(media.aspectRatio) else mod.height(180.dp) }
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (media.thumbnailUrl != null) {
                AsyncImage(
                    model = media.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Dark overlay for play affordance contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                val label = when {
                    media.kind == WebMedia.Kind.RoundVideo -> "● ${media.durationSec ?: ""}s"
                    media.kind == WebMedia.Kind.Gif -> "GIF"
                    media.durationSec != null -> "▶ ${media.durationSec}s"
                    else -> "▶"
                }
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        WebMedia.Kind.Voice -> Text(
            text = "🎤 Voice ${media.durationSec ?: 0}s",
            style = MaterialTheme.typography.bodyMedium,
        )
        WebMedia.Kind.Document -> Text(
            text = "📎 Document",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun WebPreviewCard(preview: WebPreview, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            preview.siteName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            preview.title?.let {
                Text(text = it, style = MaterialTheme.typography.titleSmall)
            }
            preview.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            preview.imageUrl?.let {
                Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

@Composable
fun WebPostText(
    content: WebTextContent,
    emojiResolver: WebCustomEmojiResolver,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val inlineContent: Map<String, InlineTextContent> = content.emojiIds.associate { id ->
        WebTextRenderer.INLINE_EMOJI_KEY_PREFIX + id to inlineEmojiContent(
            emojiId = id,
            fallbackGlyph = content.emojiFallbacks[id].orEmpty(),
            emojiResolver = emojiResolver,
        )
    }
    Text(
        text = content.text,
        style = style,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}

@Composable
private fun inlineEmojiContent(
    emojiId: String,
    fallbackGlyph: String,
    emojiResolver: WebCustomEmojiResolver,
): InlineTextContent = InlineTextContent(
    placeholder = Placeholder(
        width = 1.2.em,
        height = 1.2.em,
        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    ),
    children = {
        val resolved by produceState<ResolvedEmoji?>(null, emojiId) {
            value = emojiResolver.resolve(emojiId)
        }
        val url = resolved?.thumbUrl ?: resolved?.url
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallbackGlyph.isNotBlank()) {
            Text(
                text = fallbackGlyph,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    },
)

@Composable
private fun FooterRow(post: WebPost, emojiResolver: WebCustomEmojiResolver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        post.reactions.forEach { reaction ->
            ReactionChip(reaction, emojiResolver)
        }
        Spacer(Modifier.weight(1f))
        if (post.views != null) {
            Text(
                text = "👁 ${post.views}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionChip(reaction: WebReaction, emojiResolver: WebCustomEmojiResolver) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            reaction.emojiId != null && reaction.glyph.isBlank() -> {
                val resolved by produceState<ResolvedEmoji?>(null, reaction.emojiId) {
                    value = emojiResolver.resolve(reaction.emojiId)
                }
                val thumbUrl = resolved?.thumbUrl ?: resolved?.url
                if (thumbUrl != null) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            reaction.glyph.isNotBlank() -> Text(
                text = reaction.glyph,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = reaction.count,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

