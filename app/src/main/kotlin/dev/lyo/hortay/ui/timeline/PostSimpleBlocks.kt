package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ExpiredKind
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ServiceEvent
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.StickerView
import dev.lyo.hortay.ui.text.RichText
import dev.lyo.hortay.ui.text.rememberRenderableText

@Composable
internal fun TextBlock(content: PostContent.Text, maxLines: Int, translation: FormattedText?) {
    val rendered = translation ?: content.formatted
    if (rendered.text.isNotEmpty()) {
        RichText(
            formatted = rendered,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = maxLines,
            renderer = { rt, style, lines -> ExpandableText(rt, style, lines) },
        )
    }
    content.webPreview?.let {
        Spacer(Modifier.height(12.dp))
        WebPreviewCard(it)
    }
}

@Composable
internal fun ChecklistBlock(content: PostContent.Checklist, maxLines: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (content.title.text.isNotBlank()) {
            val titleRt = rememberRenderableText(content.title)
            Text(
                text = titleRt.text,
                inlineContent = titleRt.inlineContent,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
        }
        content.tasks.forEach { task ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Symbol(
                    name = if (task.isDone) "check_box" else "check_box_outline_blank",
                    tint = if (task.isDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 20.dp,
                )
                Spacer(Modifier.width(10.dp))
                val taskRt = rememberRenderableText(task.text)
                Text(
                    text = taskRt.text,
                    inlineContent = taskRt.inlineContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun AnimatedEmojiBlock(content: PostContent.AnimatedEmoji) {
    // Single-emoji posts render as a small sticker pinned to the leading edge of the
    // post body — same vocabulary as [StickerBlock] (no fillMaxWidth wrapper, sits on
    // the Column's natural start axis). An earlier iteration wrapped this in a
    // `Box(fillMaxWidth, contentAlignment = Center)` to mimic the "huge centred emoji"
    // pattern from chat clients, but in a Twitter-style feed where the post body is
    // already a full-width card the centred emoji read as floating in dead space.
    // Left-alignment matches the rest of the body (text, captions, media all start at
    // the same x), and the sticker sizing keeps the visual hierarchy
    // single-emoji < full-sticker (ANIMATED_EMOJI_MAX_SIDE < STICKER_MAX_SIDE).
    //
    // When TDLib has resolved an animated sticker variant (premium animated set /
    // lottie / webm) we play it through StickerView; the unicode emoji stays as a
    // fallback for the brief window where TDLib is still resolving the sticker, and
    // as the permanent path when no animated variant exists for that codepoint. The
    // fallback uses a tighter type style so it doesn't dwarf the eventual sticker.
    val sticker = content.sticker
    if (sticker != null && sticker.fileId != null) {
        StickerView(
            media = sticker,
            thumb = content.thumb,
            format = content.format,
            contentDescription = content.emoji,
            modifier = stickerBoxModifier(
                width = sticker.width,
                height = sticker.height,
                maxSide = ANIMATED_EMOJI_MAX_SIDE,
            ),
        )
    } else {
        Text(
            text = content.emoji,
            style = MaterialTheme.typography.displayMedium,
        )
    }
}

@Composable
internal fun StickerBlock(content: PostContent.Sticker) {
    val boxModifier = stickerBoxModifier(
        width = content.media.width,
        height = content.media.height,
        maxSide = STICKER_MAX_SIDE,
    )
    Box(modifier = boxModifier) {
        // [media] is the playback file (.webp/.tgs/.webm) and [thumb] is TDLib's static
        // WEBP/PNG preview. StickerView shows the thumb instantly, then crossfades into
        // the rendered animation once the sticker file lands.
        StickerView(
            media = content.media,
            thumb = content.thumb,
            format = content.format,
            contentDescription = content.emoji,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun DiceBlock(content: PostContent.Dice) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = content.emoji,
            style = MaterialTheme.typography.displayLarge,
        )
    }
}

@Composable
internal fun ExpiredMediaBlock(content: PostContent.ExpiredMedia) {
    val (symbol, label) = when (content.kind) {
        ExpiredKind.Photo -> "hide_image" to stringResource(R.string.expired_photo)
        ExpiredKind.Video -> "videocam_off" to stringResource(R.string.expired_video)
        ExpiredKind.VideoNote -> "videocam_off" to stringResource(R.string.expired_video_note)
        ExpiredKind.VoiceNote -> "mic_off" to stringResource(R.string.expired_voice)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = symbol, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ServiceBlock(content: PostContent.Service) {
    val (symbol, label) = when (val e = content.event) {
        is ServiceEvent.PinnedMessage -> "push_pin" to stringResource(R.string.service_pinned_message)
        is ServiceEvent.ChannelBoosted -> "rocket_launch" to
            pluralStringResource(R.plurals.service_boost, e.boostCount, e.boostCount)
        ServiceEvent.GiveawayStarted -> "card_giftcard" to stringResource(R.string.service_giveaway_started)
        ServiceEvent.ScreenshotTaken -> "photo_camera" to stringResource(R.string.service_screenshot)
        is ServiceEvent.VideoChatStarted -> "video_call" to stringResource(R.string.service_video_chat_started)
        ServiceEvent.VideoChatEnded -> "call_end" to stringResource(R.string.service_video_chat_ended)
        is ServiceEvent.GroupCall -> "call" to
            stringResource(if (e.isVideo) R.string.service_video_call else R.string.service_voice_call)
        ServiceEvent.Other -> "info" to stringResource(R.string.service_other)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LocationBlock(content: PostContent.Location) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge("place")
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                content.title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = content.address ?: "%.5f, %.5f".format(content.latitude, content.longitude),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ContactBlock(content: PostContent.Contact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge("call_received")
        Spacer(Modifier.width(12.dp))
        Column {
            Text(content.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(content.phone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Card for [PostContent.OpenInSource] — invoice / giveaway / story / game /
 * gift code. Hortay doesn't reimplement these flows in-app, but they're real
 * channel posts (not service noise) so we surface a labelled affordance with
 * an "Open in Telegram" chevron. Tap routes via [onOpenInSource] which lifts
 * to [PostInteractions.onOpenClick] in PostCard.
 */
@Composable
internal fun OpenInSourceBlock(content: PostContent.OpenInSource, onOpenInSource: () -> Unit) {
    NonPlayableFileRow(
        symbol = content.iconSymbol,
        primary = content.title,
        secondary = content.subtitle.ifBlank { stringResource(R.string.content_open_in_telegram) },
        onClick = onOpenInSource,
    )
}

@Composable
internal fun UnsupportedBlock(content: PostContent.Unsupported) {
    Text(
        text = content.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
