package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.ReactionEmojiIcon
import dev.lyo.hortay.ui.media.LocalReactionEmojiIcons
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.rememberMediaBinding
import dev.lyo.hortay.ui.media.rememberRevealAlpha

/**
 * A standard (unicode) reaction emoji, drawn with Telegram's own static reaction artwork
 * when [LocalReactionEmojiIcons] provides a resolver, else with the device's emoji font.
 *
 * No layout jump, ever: the box is always measured by the text emoji, which stays
 * composed in every state (just faded out once the artwork is on disk), and the artwork
 * draws inside that same box with [Modifier.matchParentSize]. So the glyph's size is
 * identical while loading, after the swap, on a failed lookup, in guest mode and under
 * either emoji-style setting. The text also stays the screen-reader label.
 *
 * Fallback to the text emoji while the artwork resolves or downloads, when TDLib has no
 * artwork for it (the resolver caches the miss), and whenever the local is null (guest
 * mode, [dev.lyo.hortay.data.EmojiStyle.System]) — in that case no TDLib call is made.
 */
@Composable
internal fun ReactionEmojiGlyph(
    emoji: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalReactionEmojiIcons.current
    val icon: ReactionEmojiIcon? = if (resolver != null) {
        // Lazy, per composed glyph — the resolver dedupes, so N chips of one emoji cost
        // one TDLib call per session at most.
        LaunchedEffect(resolver, emoji) { resolver.request(emoji) }
        val store = resolver.icons.collectAsStateWithLifecycle()
        val resolved = remember(resolver, emoji) { derivedStateOf { store.value[emoji] } }
        resolved.value
    } else {
        null
    }
    val fileReady = if (icon != null) {
        rememberMediaBinding(fileId = icon.media.fileId, priority = DownloadPriority.Avatar).isReady
    } else {
        false
    }
    // Crossfade text → artwork; snaps under reduced motion, and starts at 1 when the
    // artwork is already on disk at first composition (no fade on scroll-back).
    val artworkAlpha = rememberRevealAlpha(revealed = fileReady)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = emoji.withEmojiPresentation(),
            style = style,
            modifier = Modifier.graphicsLayer { alpha = 1f - artworkAlpha },
        )
        if (icon != null) {
            TdMediaImage(
                media = icon.media,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = artworkAlpha },
                contentScale = ContentScale.Fit,
                placeholderColor = null,
                showProgress = false,
                priority = DownloadPriority.Avatar,
            )
        }
    }
}
