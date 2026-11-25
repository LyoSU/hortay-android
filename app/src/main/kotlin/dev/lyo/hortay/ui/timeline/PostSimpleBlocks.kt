package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ExpiredKind
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.InvoicePrice
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ServiceEvent
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.StickerView
import dev.lyo.hortay.ui.text.RichText
import dev.lyo.hortay.ui.text.rememberRenderableText
import dev.lyo.hortay.ui.theme.tabularFigures
import kotlinx.coroutines.delay

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
            .clip(ContentBlockShape)
            .background(contentBlockColor)
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
            .clip(ContentBlockShape)
            .background(contentBlockColor)
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

/**
 * Location / venue card. A venue keeps its title and address; a bare geo point gets the
 * "Location" title with its coordinates as the secondary line, formatted by
 * [formatCoordinates] (dot decimal, degree sign, LTR-isolated) — the former locale-formatted
 * "49,23311, 28,46819" read as four numbers. A live location reads "Live location" on a
 * tertiary badge while it is still shared, and "Live location ended" on the plain badge once
 * it stopped — on TDLib's update when the sender stops early, or at
 * [PostContent.Location.liveUntilMs] when the period runs out (TDLib sends no update then).
 *
 * The whole row opens the point in the system maps app through a generic `geo:` URI
 * ([openCoordinatesInMaps]); the venue title rides along as the pin label.
 *
 * Signed in, a static map picture ([LocationMapThumbnail], resolved through
 * [LocalMapThumbnailResolver]) fills a 2:1 slot above the row inside the same container,
 * clipped by its corners; [mapThumbnail] overrides it. The slot is reserved for as long as
 * the card is signed in, whatever the picture's state: it shows a quiet placeholder until the
 * picture is on disk (that can take minutes) and keeps it if TDLib never delivers one, so the
 * card's height never changes under the reader. Guest mode has no resolver and no slot.
 */
@Composable
internal fun LocationBlock(
    content: PostContent.Location,
    mapThumbnail: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val coords = ltrIsolate(formatCoordinates(content.latitude, content.longitude))
    val title = content.title?.takeIf { it.isNotBlank() }
    val address = content.address?.takeIf { it.isNotBlank() }
    val liveActive = rememberLiveActive(content.liveUntilMs) && content.isLive
    val fallbackTitle = stringResource(
        when {
            liveActive -> R.string.content_live_location
            content.isLive -> R.string.content_live_location_ended
            else -> R.string.content_location
        },
    )
    val primary = title ?: address ?: fallbackTitle
    val secondary = when {
        title != null && address != null -> address
        else -> coords
    }
    val resolver = LocalMapThumbnailResolver.current
    val top: (@Composable () -> Unit)? = when {
        mapThumbnail != null -> mapThumbnail
        resolver == null -> null
        else -> remember(resolver, content.latitude, content.longitude, content.isLive) {
            @Composable {
                LocationMapThumbnail(
                    resolver = resolver,
                    latitude = content.latitude,
                    longitude = content.longitude,
                    isLive = content.isLive,
                )
            }
        }
    }
    ContentRow(
        leading = {
            // A live location that is still being shared takes the tertiary role so it
            // reads as "ongoing" without motion; a stopped one looks like any other point.
            if (liveActive) {
                GlyphBadge(
                    symbol = "location_on",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                GlyphBadge("place")
            }
        },
        primary = primary,
        secondary = secondary,
        onClick = { openCoordinatesInMaps(context, content.latitude, content.longitude, title) },
        onClickLabel = stringResource(R.string.rich_map_open),
        secondaryStyle = MaterialTheme.typography.bodySmall.tabularFigures(),
        top = top?.let { slot -> @Composable { MapThumbnailSlot(slot) } },
    )
}

/**
 * Whether a live location is still shared: true until [liveUntilMs], then false. One delayed
 * flip, not a ticking clock — the card recomposes once when the period runs out.
 */
@Composable
private fun rememberLiveActive(liveUntilMs: Long): Boolean {
    var active by remember(liveUntilMs) { mutableStateOf(liveUntilMs > System.currentTimeMillis()) }
    if (active) {
        LaunchedEffect(liveUntilMs) {
            delay(liveUntilMs - System.currentTimeMillis())
            active = false
        }
    }
    return active
}

@Composable
private fun MapThumbnailSlot(thumbnail: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { thumbnail() }
}

/**
 * Shared contact card: person glyph, name, and the phone number LTR-isolated in tabular
 * figures so a "+380…" keeps its plus sign at the start inside RTL text. Informational —
 * Hortay has no add-contact flow, so the row is not clickable.
 */
@Composable
internal fun ContactBlock(content: PostContent.Contact) {
    ContentRow(
        leading = { GlyphBadge("person") },
        primary = content.name.ifBlank { ltrIsolate(content.phone) },
        secondary = if (content.name.isBlank()) null else ltrIsolate(content.phone),
        onClick = null,
        onClickLabel = null,
        secondaryStyle = MaterialTheme.typography.bodySmall.tabularFigures(),
    )
}

/**
 * Card for [PostContent.OpenInSource] — invoice / giveaway / story / game /
 * gift code. Hortay doesn't reimplement these flows in-app, but they're real
 * channel posts (not service noise) so we surface a labelled card whose whole
 * row opens the post in Telegram. Tap routes via [onOpenInSource] which lifts
 * to [PostInteractions.onOpenClick] in PostCard.
 */
@Composable
internal fun OpenInSourceBlock(content: PostContent.OpenInSource, onOpenInSource: () -> Unit) {
    val price = content.price
    if (price != null) {
        InvoiceBlock(content, price, onOpenInSource)
        return
    }
    NonPlayableFileRow(
        symbol = content.iconSymbol,
        primary = content.title,
        secondary = content.subtitle.ifBlank { stringResource(R.string.content_open_in_telegram) },
        onClick = onOpenInSource,
    )
}

/**
 * Invoice card: the product title, its price ("250 ⭐" for Telegram Stars, the locale's
 * currency format otherwise) and up to three lines of the product description. Paying is
 * Telegram's flow, so the whole row opens the post there.
 */
@Composable
private fun InvoiceBlock(content: PostContent.OpenInSource, price: InvoicePrice, onOpenInSource: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val priceLabel = remember(price, locale) { formatInvoicePrice(price, locale) }
    ContentRow(
        leading = { GlyphBadge(content.iconSymbol) },
        primary = content.subtitle.ifBlank { content.title },
        secondary = priceLabel,
        onClick = onOpenInSource,
        onClickLabel = stringResource(R.string.content_open_in_telegram),
        secondaryStyle = MaterialTheme.typography.bodySmall.tabularFigures(),
        tertiary = content.description,
    )
}

/**
 * A message Hortay can't render yet (comment threads show it; the feed filters it out).
 * A quiet row rather than raw text, so it reads as a placeholder, and the tap opens the
 * message in Telegram where it can be seen.
 */
@Composable
internal fun UnsupportedBlock(content: PostContent.Unsupported, onOpenInSource: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContentBlockShape)
            .background(contentBlockColor)
            .clickable(
                onClickLabel = stringResource(R.string.content_open_in_telegram),
                role = Role.Button,
                onClick = onOpenInSource,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(name = "info", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = content.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
