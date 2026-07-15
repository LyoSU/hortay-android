package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay
import dev.lyo.hortay.ui.media.TdMediaImage

@Composable
internal fun DocumentBlock(
    content: PostContent.Document,
    maxLines: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
) {
    NonPlayableFileRow(
        symbol = "description",
        primary = content.fileName.ifBlank { stringResource(R.string.document_unnamed) },
        secondary = formatFileSize(content.sizeBytes, stringArrayResource(R.array.size_units)),
        onClick = onOpenInSource,
    )
    // Documents never carry the caption-above flag (Telegram only exposes that toggle for
    // photo/video/animation/paid-media), so we always render below.
    MediaCaption(translation ?: content.caption, maxLines, above = false, show = true)
}

@Composable
internal fun AudioBlock(content: PostContent.Audio, onOpenInSource: () -> Unit) {
    NonPlayableFileRow(
        symbol = "audio_file",
        primary = content.title.ifBlank { stringResource(R.string.content_audio_fallback) },
        secondary = listOfNotNull(
            content.performer.takeUnless { it.isBlank() },
            formatDuration(content.durationSec),
        ).joinToString(" · "),
        onClick = onOpenInSource,
    )
}

@Composable
internal fun VoiceNoteBlock(content: PostContent.VoiceNote, onOpenInSource: () -> Unit) {
    NonPlayableFileRow(
        symbol = "mic",
        primary = stringResource(R.string.voice_message),
        secondary = formatDuration(content.durationSec),
        onClick = onOpenInSource,
        shape = MaterialTheme.shapes.large,
    )
}

/**
 * Telegram "round video message" — dispatcher.
 *
 * Two render paths:
 *   • Playback file is on disk *and* the user's autoplay toggle is on →
 *     delegate to [VideoNotePlayerBubble], which owns its own [androidx.media3.exoplayer.ExoPlayer]
 *     for the progress ring, remaining-time chip, pause / play tap and
 *     mute toggle.
 *   • Otherwise → static poster + centred play glyph; tap routes to
 *     Telegram via [onOpenInSource]. Same affordance the rest of the feed
 *     uses for "not yet cache-ready" inline video.
 *
 * The cache-ready gate is the same [isCachedReady] inline short videos use,
 * so we never side-step the user's auto-download policy by stealth-pulling
 * the playback file just because the post entered the viewport.
 */
@Composable
internal fun VideoNoteBlock(content: PostContent.VideoNote, onOpenInSource: () -> Unit) {
    val videoFileId = content.video?.fileId
    val hasPlayback = videoFileId != null && videoFileId != 0
    val inlineAutoplayEnabled = LocalInlineVideoAutoplay.current
    val cacheReady = hasPlayback && inlineAutoplayEnabled &&
        isCachedReady(fileId = videoFileId, remoteUrl = null)

    // [cacheReady] is `true` only when [videoFileId] is non-null and non-zero
    // (via the [hasPlayback] guard); K2 propagates the smart-cast here.
    if (cacheReady) {
        VideoNotePlayerBubble(content = content, fileId = videoFileId)
    } else {
        VideoNoteStaticBubble(
            content = content,
            hasPlayback = hasPlayback,
            onOpenInSource = onOpenInSource,
        )
    }
}

/**
 * Static round-poster fallback when the playback file isn't on disk yet
 * (autoplay disabled in Settings, auto-download skipped this file, or it's
 * mid-prefetch) or unavailable entirely (rare hydration path where TDLib
 * delivered only the thumbnail).
 */
@Composable
private fun VideoNoteStaticBubble(
    content: PostContent.VideoNote,
    hasPlayback: Boolean,
    onOpenInSource: () -> Unit,
) {
    val a11y = stringResource(R.string.content_description_video_note)
    Box(
        modifier = Modifier
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .size(VIDEO_NOTE_DIAMETER)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                onClickLabel = a11y,
                role = Role.Button,
                onClick = onOpenInSource,
            ),
    ) {
        content.thumb?.let {
            TdMediaImage(
                media = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(if (hasPlayback) 56.dp else 48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = if (hasPlayback) "play_arrow" else "video_camera_front",
                tint = Color.White,
                size = if (hasPlayback) 32.dp else 26.dp,
                filled = hasPlayback,
                // N4 — a play triangle is visually heavier on its left edge, so
                // mechanically centring it reads as sitting slightly left. Nudge the
                // triangle ~1.5 dp right inside its disc for true optical centring.
                // The camera glyph (no-playback fallback) is symmetric, so it stays put.
                modifier = if (hasPlayback) Modifier.offset(x = 1.5.dp) else Modifier,
            )
        }
        if (content.durationSec > 0) {
            DurationChip(
                text = formatDuration(content.durationSec),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * Shared layout for non-playable file cards (document, audio, voice note).
 *
 * Hortay doesn't host an in-app download / playback path for these kinds — the
 * file lives on Telegram's CDN and decoding it here would replicate Telegram's
 * own player. Instead, the whole row is a `clickable` affordance that routes
 * the tap to [PostInteractions.onOpenClick], which deep-links into the official
 * Telegram client. The visual is identical to what was there before; only the
 * action wiring is new.
 *
 * [onClick] is nullable: `null` renders a purely informational card with no
 * ripple and no "open in Telegram" chevron — used by the rich-message audio /
 * voice-note blocks, which have no per-block source-post link to route a tap to.
 */
@Composable
internal fun NonPlayableFileRow(
    symbol: String,
    primary: String,
    secondary: String,
    onClick: (() -> Unit)?,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(symbol)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Symbol(
                name = "open_in_new",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

@Composable
internal fun IconBadge(symbol: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 22.dp,
        )
    }
}
