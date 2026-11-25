package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.theme.mediaFrame

/**
 * Document card. The leading [FileTypeBadge] carries the file's extension ("PDF", "XLSX")
 * tinted by its family, so the type reads before the name. The name ellipsizes in the
 * MIDDLE — "budget_final_v3…approved.xlsx" keeps both the distinguishing tail and the
 * extension, where an end ellipsis cut exactly the part that tells two versions apart.
 *
 * With a TDLib file the whole row is one stateful action — download, cancel, open — see
 * [DownloadableDocumentRow]. Without one (no resolvable file) or when the chat forbids
 * saving its content ([contentProtected]: opening the file in another app would export
 * it), the row opens the post in Telegram ([onOpenInSource]), as it always did.
 */
@Composable
internal fun DocumentBlock(
    content: PostContent.Document,
    maxLines: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
    contentProtected: Boolean = false,
) {
    val fileId = content.fileId
    if (fileId == null || contentProtected) {
        DocumentInfoRow(content, onOpenInSource)
    } else {
        DownloadableDocumentRow(content, fileId, onOpenInSource)
    }
    // Documents never carry the caption-above flag (Telegram only exposes that toggle for
    // photo/video/animation/paid-media), so we always render below.
    MediaCaption(translation ?: content.caption, maxLines, above = false, show = true)
}

@Composable
internal fun AudioBlock(content: PostContent.Audio, onOpenInSource: () -> Unit) {
    InlineAudioPlayerRow(
        fileId = content.fileId,
        durationSec = content.durationSec,
        title = content.title.ifBlank { stringResource(R.string.content_audio_fallback) },
        performer = content.performer.takeUnless { it.isBlank() },
        waveform = null,
        isVoice = false,
        onOpenInSource = onOpenInSource,
        cover = content.cover,
    )
}

@Composable
internal fun VoiceNoteBlock(content: PostContent.VoiceNote, onOpenInSource: () -> Unit) {
    InlineAudioPlayerRow(
        fileId = content.fileId,
        durationSec = content.durationSec,
        title = stringResource(R.string.voice_message),
        performer = null,
        waveform = content.waveform,
        isVoice = true,
        onOpenInSource = onOpenInSource,
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
    // Tombstone (deleted post): never probe/resync the cache or spin up playback — same
    // `!passive` gate PostMediaBlocks applies to inline video. The static poster still renders.
    val passive = LocalMediaPassive.current
    val cacheReady = hasPlayback && inlineAutoplayEnabled && !passive &&
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
            .size(VIDEO_NOTE_DIAMETER)
            .clip(CircleShape)
            // Hairline frame, not a shadow — the bubble sits in the post body and doesn't
            // float over content (ARCHITECTURE.md → "Container budget").
            .mediaFrame(CircleShape)
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
        if (hasPlayback) {
            PlayDisc(
                glyph = PlayGlyph.Play,
                overMedia = true,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // No playback file (thumbnail-only hydration): the camera glyph on the same
            // tonal disc says "video message" without promising inline playback.
            GlyphDisc(modifier = Modifier.align(Alignment.Center))
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
 * Shared layout for non-playable cards (audio / voice without a resolvable file, locked paid
 * media, invoice / giveaway / game "open in Telegram" cards, rich-message fallbacks).
 *
 * Hortay doesn't host an in-app download / playback path for these kinds, so the whole row
 * is the affordance and routes the tap to [PostInteractions.onOpenClick], which deep-links
 * into the official Telegram client. A former trailing "open_in_new" square read as the
 * row's primary action and competed with the badge; it was dropped in favour of the
 * row-wide ripple plus an "Open in Telegram" click label for TalkBack.
 *
 * [onClick] is nullable: `null` renders a purely informational card with no ripple — used
 * by the rich-message audio / voice-note blocks, which have no per-block source-post link to
 * route a tap to. [shape] is kept for source compatibility; every caller uses the default
 * [ContentBlockShape].
 */
@Composable
internal fun NonPlayableFileRow(
    symbol: String,
    primary: String,
    secondary: String,
    onClick: (() -> Unit)?,
    shape: Shape = ContentBlockShape,
) {
    ContentRow(
        leading = { GlyphBadge(symbol) },
        primary = primary,
        secondary = secondary,
        onClick = onClick,
        onClickLabel = if (onClick != null) stringResource(R.string.content_open_in_telegram) else null,
        shape = shape,
    )
}

/** Tonal glyph badge; kept as the public name the rich-message blocks import. */
@Composable
internal fun IconBadge(symbol: String) {
    GlyphBadge(symbol)
}

@Composable
private fun GlyphDisc(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(PlayDiscSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = "video_camera_front",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 24.dp,
        )
    }
}
