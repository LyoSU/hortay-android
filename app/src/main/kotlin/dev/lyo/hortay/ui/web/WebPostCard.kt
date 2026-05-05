package dev.lyo.hortay.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date

/**
 * Web-mode post card. Visual contract: pixel-identical to
 * [dev.lyo.hortay.ui.timeline.PostCard] — same Row(avatar + content) + bottom
 * divider layout, same paddings (16dp horizontal, 14dp vertical), same
 * 40dp avatar, same typography. Differs only in *what* it renders inside
 * those slots: web posts are missing reply/translation/in-channel/personal-
 * author affordances since `t.me/s/` doesn't expose those, but their absence
 * makes web cards visually a strict subset of the TDLib card — never an
 * inconsistent shape.
 *
 * Why we don't reuse [dev.lyo.hortay.ui.timeline.PostCard] directly: it takes a
 * `TimelinePost` and a TDLib-shaped [dev.lyo.hortay.ui.timeline.PostInteractions]
 * sealed interface that doesn't make sense without TDLib chat ids. Mirroring
 * the visual layout keeps both modes consistent without dragging TDLib types
 * into the guest-mode tree.
 *
 * Avatar: reuses [TdAvatar] with `thumb=null` and `fileId=null` (the third-tier
 * fallback path: initial-letter circle), with a Coil [AsyncImage] layered over
 * it when the channel exposes an avatar URL via t.me/s/. Same 40dp circle, same
 * background colour cycle, so a future user signing in won't see their avatars
 * jump around.
 */
@Composable
fun WebPostCard(
    post: WebPost,
    emojiResolver: WebCustomEmojiResolver,
    modifier: Modifier = Modifier,
    channelTitle: String? = null,
    channelAvatarUrl: String? = null,
    onChannelClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onCardClick != null) it.clickable(onClick = onCardClick) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            WebAvatar(
                name = channelTitle ?: post.id.substringBefore('/'),
                avatarUrl = channelAvatarUrl,
                onClick = onChannelClick,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                WebHeaderRow(
                    title = channelTitle ?: post.id.substringBefore('/'),
                    publishedIso = post.publishedAt,
                    onClick = onChannelClick,
                )

                if (post.forwardedFrom != null) {
                    Spacer(Modifier.height(6.dp))
                    WebForwardChip(label = post.forwardedFrom.channelName)
                }

                Spacer(Modifier.height(8.dp))
                WebBody(
                    post = post,
                    emojiResolver = emojiResolver,
                )

                val showActions = post.views != null || post.reactions.isNotEmpty()
                if (showActions) {
                    Spacer(Modifier.height(10.dp))
                    WebActionRow(
                        views = post.views,
                        reactions = post.reactions,
                        emojiResolver = emojiResolver,
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

// ---- Avatar / Header / Forward / Body / Actions -----------------------------

@Composable
private fun WebAvatar(
    name: String,
    avatarUrl: String?,
    onClick: (() -> Unit)?,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val bg = palette[(name.hashCode().rem(palette.size) + palette.size) % palette.size]
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        TdAvatar(
            name = name,
            thumb = null,
            fileId = null,
            size = 40.dp,
            background = bg,
        )
        if (avatarUrl != null) {
            // Coil layer over the initial-letter fallback. Same circle clip, same
            // 40dp size — looks identical to TdAvatar's small-file tier when
            // signed in; the user can't visually tell which mode they're in.
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun WebHeaderRow(
    title: String,
    publishedIso: String,
    onClick: (() -> Unit)?,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface
    val annotated = remember(title, titleColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = titleColor, fontWeight = FontWeight.SemiBold)) {
                append(title)
            }
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatRelative(publishedIso),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WebForwardChip(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Symbol(
            name = "forward",
            tint = MaterialTheme.colorScheme.primary,
            size = 14.dp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WebBody(post: WebPost, emojiResolver: WebCustomEmojiResolver) {
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
}

@Composable
private fun WebActionRow(
    views: String?,
    reactions: List<WebReaction>,
    emojiResolver: WebCustomEmojiResolver,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (views != null) {
            WebStatPill(symbol = "visibility", text = views)
        }
        if (reactions.isNotEmpty()) {
            if (views != null) {
                Spacer(Modifier.width(14.dp))
                WebVerticalSeparator()
                Spacer(Modifier.width(14.dp))
            }
            reactions.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.width(6.dp))
                WebReactionChip(reaction = item, emojiResolver = emojiResolver)
            }
        }
    }
}

@Composable
private fun WebVerticalSeparator() {
    Box(
        modifier = Modifier
            .height(18.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
private fun WebStatPill(
    symbol: String,
    text: String,
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 22.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reaction chip mirrored to TDLib's [dev.lyo.hortay.ui.timeline.ReactionChip] —
 * same RoundedCornerShape(14.dp), same surfaceContainer background, same
 * 12×8 padding, same 6dp gap between glyph and count. Tap doesn't do anything
 * in guest mode; we render as a static chip rather than greying it out so the
 * post visually mirrors how it looks for authenticated users.
 */
@Composable
private fun WebReactionChip(
    reaction: WebReaction,
    emojiResolver: WebCustomEmojiResolver,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            reaction.glyph.isNotBlank() -> Text(
                text = reaction.glyph,
                style = MaterialTheme.typography.titleMedium,
            )
            else -> Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = reaction.count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Media + preview --------------------------------------------------------

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
            Spacer(Modifier.height(4.dp))
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

// ---- Inline emoji rendering -------------------------------------------------

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

// ---- Time formatting --------------------------------------------------------

/**
 * Mirrors `dev.lyo.hortay.ui.timeline.formatRelative` exactly so a TDLib post
 * and a web post published 5 minutes ago both render "5 хв" / "5 min". The
 * function is private in PostCard.kt so we duplicate the logic — it's 8 lines
 * and the synced behaviour is more important than DRY here.
 */
private fun formatRelative(iso: String): String {
    val epochMs = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrElse { return iso }
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> "щойно"
        diffMin < 60 -> "${diffMin} хв"
        diffMin < 60 * 24 -> "${diffMin / 60} год"
        diffMin < 60 * 24 * 7 -> "${diffMin / (60 * 24)} дн"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}
