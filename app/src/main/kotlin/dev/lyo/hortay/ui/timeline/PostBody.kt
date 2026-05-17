package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.ExpiredKind
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ServiceEvent
import dev.lyo.hortay.data.hasSpoiler
import dev.lyo.hortay.data.isSecret
import dev.lyo.hortay.data.isUnplayableVideo
import androidx.compose.foundation.clickable
import dev.lyo.hortay.data.WebPreview
import dev.lyo.hortay.data.WebPreviewKind
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.SpoilerKind
import dev.lyo.hortay.ui.media.SpoilerOverlay
import dev.lyo.hortay.ui.media.StickerView
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.TdVideoPlayer
import dev.lyo.hortay.data.MediaState
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.ui.text.RichText
import dev.lyo.hortay.ui.text.linkLongPress
import dev.lyo.hortay.ui.text.rememberAnnotatedString

/**
 * Render the body of a post. [onMediaClick] fires with the resolved media list and the
 * index the user tapped, so callers can open a full-screen viewer with the correct page.
 */
@Composable
fun PostBody(
    content: PostContent,
    modifier: Modifier = Modifier,
    onMediaClick: (List<AlbumItem>, Int) -> Unit = { _, _ -> },
    /** When true, text is rendered without `maxLines` clamps — used in detail screens. */
    expanded: Boolean = false,
    /**
     * Translated body. When non-null, text/caption blocks render this instead of the
     * original `content.formatted` / `content.caption`. Other variants (sticker, poll,
     * location…) ignore the translation — they have nothing to translate.
     */
    translation: FormattedText? = null,
    /**
     * Tap on a non-playable card (document, audio, voice / video note) — Hortay
     * doesn't host its own download / playback UI for those file kinds, so a tap
     * routes the user to the original Telegram post via [PostInteractions.onOpenClick].
     * Default is a no-op so callers that don't have a post context (preview surfaces,
     * tests) can still mount [PostBody] without wiring.
     */
    onOpenInSource: () -> Unit = {},
    /**
     * Poll voting handler. When non-null, the [PollBlock] wires its option rows / Vote button
     * to call this with the user's selection (0-based [PollOption.index] array). Empty array
     * means "retract vote" — regular polls only. Defaults to null so callers that don't wire
     * voting (preview surfaces, tests, guest-mode where polls are read-only) get a passive
     * results-only render.
     */
    pollVoting: PollVoting? = null,
) {
    val textLimit = if (expanded) Int.MAX_VALUE else 18
    val captionLimit = if (expanded) Int.MAX_VALUE else 12
    // Text selection is gated on [expanded] — only the "full post" surfaces
    // (comments-thread anchor in CommentsScreen, future detail screens)
    // enable it. In the feed PostCard owns its own long-press via
    // `combinedClickable { onLongClick = { sheetOpen = true } }` (post action
    // sheet); wrapping the feed body in a SelectionContainer would race the
    // long-press detector and a feed press would alternately raise the action
    // sheet or the selection handles. Detail surfaces have no long-press card
    // gesture so selection runs uncontested there.
    val body: @Composable () -> Unit = {
        Column {
            when (content) {
                is PostContent.Text -> TextBlock(content, textLimit, translation)
                is PostContent.PhotoAlbum -> AlbumBlock(content, onMediaClick, captionLimit, translation)
                is PostContent.Video -> VideoBlock(content, onMediaClick, captionLimit, translation)
                is PostContent.Animation -> AnimationBlock(content, onMediaClick, captionLimit, translation)
                is PostContent.Document -> DocumentBlock(content, captionLimit, translation, onOpenInSource)
                is PostContent.Audio -> AudioBlock(content, onOpenInSource)
                is PostContent.VoiceNote -> VoiceNoteBlock(content, onOpenInSource)
                is PostContent.VideoNote -> VideoNoteBlock(content, onOpenInSource)
                is PostContent.Sticker -> StickerBlock(content)
                is PostContent.Poll -> PollBlock(content, pollVoting, onOpenInSource)
                is PostContent.Location -> LocationBlock(content)
                is PostContent.Contact -> ContactBlock(content)
                is PostContent.Dice -> DiceBlock(content)
                is PostContent.AnimatedEmoji -> AnimatedEmojiBlock(content)
                is PostContent.Checklist -> ChecklistBlock(content, captionLimit)
                is PostContent.ExpiredMedia -> ExpiredMediaBlock(content)
                is PostContent.Service -> ServiceBlock(content)
                is PostContent.PaidMedia -> PaidMediaBlock(content, onMediaClick, captionLimit, translation, onOpenInSource)
                is PostContent.OpenInSource -> OpenInSourceBlock(content, onOpenInSource)
                is PostContent.Unsupported -> UnsupportedBlock(content)
            }
        }
    }
    if (expanded) {
        SelectionContainer(modifier = modifier) { body() }
    } else {
        Box(modifier = modifier) { body() }
    }
}

@Composable
private fun AnimatedEmojiBlock(content: PostContent.AnimatedEmoji) {
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

private val STICKER_MAX_SIDE = 168.dp
private val ANIMATED_EMOJI_MAX_SIDE = 96.dp
private val SPOILER_BLUR_RADIUS = 28.dp
private val UNPLAYABLE_VIDEO_BLUR_RADIUS = 8.dp

// Threshold for treating a video as "glance-able" — same heuristic Telegram uses for
// inline silent autoplay. Videos at or below this duration play muted-and-looping in
// the feed; longer ones keep the static poster + play-badge until the user opens
// fullscreen, where audio and controls are available.
private const val INLINE_AUTOPLAY_MAX_SEC = 60

/**
 * Constrain a sticker box to the natural aspect ratio reported by TDLib. The longer side
 * is pinned to [maxSide]; the shorter scales down proportionally. This matters for
 * non-square stickers — Telegram allows up to 512×N or N×512, and a hardcoded square
 * box would either crop the content or letterbox it with wide transparent strips
 * (TGS/WebM frames are transparent so the strip is invisible but the layout still
 * eats the space and pushes neighbours).
 *
 * Falls back to a square at [maxSide] when dimensions aren't reported (e.g. a sticker
 * descriptor without resolved width/height during a cold start).
 */
private fun stickerBoxModifier(width: Int, height: Int, maxSide: Dp): Modifier {
    if (width <= 0 || height <= 0) return Modifier.size(maxSide)
    val ratio = width.toFloat() / height.toFloat()
    return if (ratio >= 1f) {
        Modifier.width(maxSide).height(maxSide / ratio)
    } else {
        Modifier.width(maxSide * ratio).height(maxSide)
    }
}

@Composable
private fun ChecklistBlock(content: PostContent.Checklist, maxLines: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (content.title.text.isNotBlank()) {
            val titleRt = dev.lyo.hortay.ui.text.rememberRenderableText(content.title)
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
                val taskRt = dev.lyo.hortay.ui.text.rememberRenderableText(task.text)
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
private fun ExpiredMediaBlock(content: PostContent.ExpiredMedia) {
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
private fun ServiceBlock(content: PostContent.Service) {
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
private fun TextBlock(content: PostContent.Text, maxLines: Int, translation: FormattedText?) {
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
private fun AlbumBlock(content: PostContent.PhotoAlbum, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    val items = content.items
    if (items.isEmpty()) return
    val caption = translation ?: content.caption

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    if (items.size == 1) {
        SingleMedia(items.first(), onClick = { onMediaClick(items, 0) })
    } else {
        AlbumRow(items, onItemClick = { idx -> onMediaClick(items, idx) })
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun SingleMedia(item: AlbumItem, onClick: () -> Unit) {
    val ratio = mediaAspectRatio(item.media.width, item.media.height)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium),
    ) {
        MediaWithSpoiler(item = item, onClick = onClick)
    }
}

/**
 * Renders a single album item — photo, video poster, or animation thumbnail — with
 * an opt-in spoiler / sensitive-content cover. The spoiler shimmer intercepts taps
 * and reveals the underlying media; once revealed, taps fall through to [onClick]
 * (fullscreen open). Used by both [SingleMedia] and the inside of [AlbumPager].
 *
 * For short videos (≤ [INLINE_AUTOPLAY_MAX_SEC]) we render a silent looping
 * [TdVideoPlayer] on top of the poster instead of a static play badge — Telegram's
 * own UX for "glance-able" clips. The poster stays underneath so the slot has a
 * frame to show while the playback file streams in. [isActive] gates this for
 * pager pages: only the page the user is on streams; neighbours show the still
 * (so an album of 5 clips doesn't spawn five ExoPlayers).
 */
@Composable
private fun MediaWithSpoiler(item: AlbumItem, onClick: () -> Unit, isActive: Boolean = true) {
    var revealed by remember(item.media.fileId) {
        mutableStateOf(!item.hasSpoiler && !item.isSecret)
    }
    val unplayable = item.isUnplayableVideo
    val inlineAutoplayEnabled = LocalInlineVideoAutoplay.current
    // Cache-presence gate. The user's contract for inline autoplay is "play only
    // what auto-download already pulled to disk" — anything else would override
    // the auto-download policy by stealthily downloading the playback file just
    // because the post entered the viewport. `Ready` is the only MediaState that
    // guarantees an on-disk path; `Downloading` / `Idle` / `Failed` mean either
    // the auto-download policy didn't pick this file up, or it's mid-flight (in
    // which case waiting for the explicit tap matches user expectation —
    // autoplay starting mid-download would just show the loading overlay over
    // a poster, which is worse than the poster alone).
    //
    // Guest (web) mode has fileId=0 + remoteUrl; there is no MediaCache slot
    // to consult, so the cached-gate degenerates to "always allow when remote".
    // ExoPlayer streams from the URL directly; the [inlineAutoplayEnabled]
    // toggle is the only off-switch in that mode.
    val asVideo = item as? AlbumItem.Video
    // Cheap gates first; the cache-presence probe ([isCachedReady]) goes
    // LAST because it spins up a per-mount [StateFlow] collector and a
    // [LaunchedEffect] resync. Probing every video slot eagerly — including
    // ones where autoplay is anyway impossible (global toggle off,
    // unplayable, oversize, hidden under spoiler) — used to fire those
    // effects on every PostBody mount in the feed, which surfaced as
    // micro-jank on video-heavy stretches. Short-circuit `&&` here means
    // only candidate-eligible videos pay the probe cost.
    val autoplayEligible = revealed
        && isActive
        && asVideo != null
        && !unplayable
        && asVideo.durationSec in 1..INLINE_AUTOPLAY_MAX_SEC
        && inlineAutoplayEnabled
    // K2 smart-casts asVideo to AlbumItem.Video — autoplayEligible's chain
    // includes `asVideo != null` and short-circuit && propagates the cast.
    val autoplayVideo = autoplayEligible && isCachedReady(
        fileId = asVideo.playbackFileId,
        remoteUrl = asVideo.remoteVideoUrl,
    )
    // Hide the play badge while the poster is downloading. The poster's own
    // [MediaLoadingOverlay] spinner sits in the same centred slot, so showing
    // both gave the user "two circles" stacked on top of each other. Once the
    // badge is gone the spinner reads cleanly as "loading, hold on".
    //
    // The outer Box still owns the click handler (see `.clickable` below), so
    // the area remains tappable during the download: a tap mounts
    // [TdVideoPlayer] which immediately ensures the playback file and
    // crossfades over the poster. We only probe TDLib state here — guest-mode
    // posters fetch through Coil and have no MediaCache slot to observe, so
    // we keep the badge visible for them (Coil's HTTP fetch is fast and we
    // don't have a state signal to drive the hide).
    val posterLoading = !autoplayVideo &&
        revealed &&
        isPosterDownloading(item.media.fileId)
    // Blur regime:
    //   • spoiler / sensitive: heavy blur until the user reveals — same as TDLib mode.
    //   • unplayable video: light blur as a visual "this is a preview, you'll need to
    //     open Telegram to actually watch it" cue. Keeps the poster recognisable while
    //     making clear the in-app player can't drive it.
    //   • otherwise: no blur.
    val blur = when {
        !revealed -> SPOILER_BLUR_RADIUS
        unplayable -> UNPLAYABLE_VIDEO_BLUR_RADIUS
        else -> 0.dp
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = revealed, onClick = onClick),
    ) {
        // When the autoplayer mounts on top, suppress the poster's own progress spinner —
        // TdVideoPlayer renders its own MediaLoadingOverlay for the playback file, and a
        // poster-side spinner would stack visibly on top of it ("два кружки в центрі").
        // Spoiler blur: while !revealed, the underlying photo is heavily blurred via
        // RenderEffect (no-op on pre-S, where the shimmer dim alone obscures the image).
        TdMediaImage(
            media = item.media,
            contentDescription = null,
            showProgress = !autoplayVideo,
            modifier = Modifier
                .fillMaxSize()
                .let { if (blur > 0.dp) it.blur(blur) else it },
        )
        if (autoplayVideo) {
            TdVideoPlayer(
                fileId = asVideo.playbackFileId,
                remoteUrl = asVideo.remoteVideoUrl,
                autoPlay = true,
                autoLoop = true,
                showControls = false,
                muted = true,
                modifier = Modifier.fillMaxSize(),
            )
            DurationChip(
                text = formatDuration(asVideo.durationSec),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        } else {
            MediaOverlay(item, hidePlayBadge = posterLoading)
        }
        if (!revealed) {
            SpoilerOverlay(
                kind = if (item.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = item.media.fileId ?: 0,
                onReveal = { revealed = true },
            )
        }
    }
}

@Composable
private fun BoxScope.MediaOverlay(item: AlbumItem, hidePlayBadge: Boolean = false) {
    when (item) {
        is AlbumItem.Video -> {
            PlayBadge(item.durationSec, hideCircle = hidePlayBadge)
            // Unplayable videos route the tap to Telegram — telegraph that
            // explicitly so the user understands where the tap is going
            // before they make it. A silent app-switch is jarring without
            // this cue.
            if (item.isUnplayableVideo) {
                OpenInTelegramHint(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }
        is AlbumItem.Animation -> DurationChip(
            text = "GIF",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        is AlbumItem.Photo -> Unit
    }
}

@Composable
private fun OpenInTelegramHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.media_open_in_telegram),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Symbol(
            name = "open_in_new",
            tint = Color.White,
            size = 14.dp,
        )
    }
}

/**
 * Threads/Instagram-style album row: each photo keeps its own aspect ratio at a fixed row
 * height, items scroll horizontally with snap fling and the next item peeks past the right
 * edge. We deliberately avoid HorizontalPager here — Pager forces every page to the same
 * width, which destroys the "portrait + landscape side-by-side" layout users expect for
 * mixed Telegram albums.
 *
 * Active-page tracking: only the most-centered item gets `isActive = true`, so silent
 * autoplay videos (≤ INLINE_AUTOPLAY_MAX_SEC) start exactly one ExoPlayer per album. This
 * preserves the same "one-player invariant" AlbumPager had via state.currentPage.
 */
@Composable
private fun AlbumRow(items: List<AlbumItem>, onItemClick: (Int) -> Unit) {
    val state = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(state)
    val activeIndex by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - viewportCenter) }
                ?.index ?: 0
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth
        val rowHeight = rowWidth * 0.75f
        val maxItemWidth = rowWidth * 0.92f
        val minItemWidth = rowWidth * 0.42f

        LazyRow(
            state = state,
            flingBehavior = flingBehavior,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
        ) {
            itemsIndexed(items) { index, item ->
                val ratio = mediaAspectRatio(item.media.width, item.media.height)
                val itemWidth = (rowHeight * ratio).coerceIn(minItemWidth, maxItemWidth)
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    MediaWithSpoiler(
                        item = item,
                        onClick = { onItemClick(index) },
                        isActive = index == activeIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoBlock(content: PostContent.Video, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    val item = AlbumItem.Video(
        media = content.media,
        durationSec = content.durationSec,
        playbackFileId = content.playbackFileId,
        qualities = content.qualities,
        hasSpoiler = content.hasSpoiler,
        isSecret = content.isSecret,
    )
    val items = listOf(item)
    val caption = translation ?: content.caption
    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    SingleMedia(item, onClick = { onMediaClick(items, 0) })
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun AnimationBlock(content: PostContent.Animation, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
    // Inline auto-loop playback: Telegram animations are silent MP4s, so we drive them via
    // ExoPlayer (Coil cannot decode MP4). Tap escalates to full-screen.
    val ratio = mediaAspectRatio(content.media.width, content.media.height)
    val items = listOf(
        AlbumItem.Animation(
            media = content.media,
            playbackFileId = content.playbackFileId,
            hasSpoiler = content.hasSpoiler,
            isSecret = content.isSecret,
        ),
    )
    val caption = translation ?: content.caption

    var revealed by remember(content.playbackFileId) {
        mutableStateOf(!content.hasSpoiler && !content.isSecret)
    }

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = revealed) { onMediaClick(items, 0) },
    ) {
        // Same suppression as MediaWithSpoiler: when the GIF autoplayer is mounted, its own
        // MediaLoadingOverlay covers the loading state — the poster's spinner would just
        // stack on top. When the spoiler is up, blur the still poster too.
        TdMediaImage(
            media = content.media,
            contentDescription = null,
            showProgress = !revealed,
            modifier = Modifier
                .fillMaxSize()
                .let { if (revealed) it else it.blur(SPOILER_BLUR_RADIUS) },
        )
        // Only mount the video player once the spoiler is revealed — otherwise we'd start
        // an ExoPlayer + TDLib download for content the user explicitly hasn't asked to see
        // yet, which is exactly the leak the spoiler/secret flags are meant to prevent.
        if (revealed) {
            TdVideoPlayer(
                fileId = content.playbackFileId,
                remoteUrl = content.remoteVideoUrl,
                autoPlay = true,
                autoLoop = true,
                showControls = false,
                muted = true,
                modifier = Modifier.fillMaxSize(),
            )
            DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        } else {
            SpoilerOverlay(
                kind = if (content.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = content.playbackFileId,
                onReveal = { revealed = true },
            )
        }
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
private fun DocumentBlock(
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
private fun AudioBlock(content: PostContent.Audio, onOpenInSource: () -> Unit) {
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
private fun VoiceNoteBlock(content: PostContent.VoiceNote, onOpenInSource: () -> Unit) {
    NonPlayableFileRow(
        symbol = "mic",
        primary = stringResource(R.string.voice_message),
        secondary = formatDuration(content.durationSec),
        onClick = onOpenInSource,
        shape = MaterialTheme.shapes.large,
    )
}

/**
 * Telegram "round video message" rendered as a self-contained circular bubble.
 *
 * Behaviour mirrors the official client:
 *   • Poster paints under the player; player crossfades in once its file is local
 *     (the cache-presence gate is the same [isCachedReady] inline short videos use,
 *     so we never side-step the user's auto-download policy by stealth-pulling the
 *     playback file just because the post entered the viewport).
 *   • Silent autoplay on viewport entry, looped — `muted = true` is the canonical
 *     default for round bubbles in a feed.
 *   • Tap toggles the audio. Re-keying the `TdVideoPlayer` on `muted` re-acquires
 *     the right pool slot (muted players are built without an audio renderer
 *     entirely; toggling can't be done by volume because the renderer isn't there
 *     to ramp). The pool keeps both regimes warm so a toggle is the same cost as a
 *     normal scroll-into-view acquisition. Position resets to 0; round videos loop
 *     and average ~10-20 s, so the user rarely notices.
 *   • Duration chip sits at the bottom-trailing corner, mute / audio glyph at the
 *     top-trailing corner. Both float over the circle with a soft scrim so they
 *     stay legible against any poster.
 *
 * Falls back to a static poster + tap-routes-to-Telegram when the playback file
 * is unavailable (rare hydration path where TDLib delivered just the thumbnail).
 */
@Composable
private fun VideoNoteBlock(content: PostContent.VideoNote, onOpenInSource: () -> Unit) {
    val videoFileId = content.video?.fileId
    val hasPlayback = videoFileId != null && videoFileId != 0
    val inlineAutoplayEnabled = LocalInlineVideoAutoplay.current
    val cacheReady = hasPlayback && inlineAutoplayEnabled &&
        isCachedReady(fileId = videoFileId, remoteUrl = null)
    // Muted at first paint — Telegram's idiom for round videos in a feed.
    // [videoFileId] keys the reset so scrolling away and back drops the audio
    // state, matching Telegram-Android's "swipe away resets" behaviour.
    var muted by remember(videoFileId) { mutableStateOf(true) }

    val bubbleLabel = stringResource(R.string.content_description_video_note)
    Box(
        modifier = Modifier
            // Soft elevation shadow so the bubble reads as a discrete object
            // on top of the post background — same affordance the official
            // client uses to separate round messages from the surrounding chat.
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .size(VIDEO_NOTE_DIAMETER)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                onClickLabel = bubbleLabel,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    when {
                        cacheReady -> muted = !muted
                        // Playback not yet cache-ready (auto-download skipped,
                        // mid-flight, or autoplay disabled in Settings) — fall
                        // through to Telegram so the user can watch it there.
                        else -> onOpenInSource()
                    }
                },
            ),
    ) {
        // Poster — always renders. Stays visible behind the player's transparent
        // TextureView during prepare/buffer, so the bubble never goes black.
        content.thumb?.let {
            TdMediaImage(
                media = it,
                contentDescription = null,
                // Suppress the poster's own spinner once the player is on top
                // of it — the player has its own loading affordance for the
                // playback file, stacking spinners reads as "two circles".
                showProgress = !cacheReady,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (cacheReady) {
            // [cacheReady] implies [hasPlayback] (a null fileId can never be Ready
            // in MediaCache) — K2 smart-casts videoFileId to non-null inside this
            // branch via the [hasPlayback] guard above.
            key(muted) {
                TdVideoPlayer(
                    fileId = videoFileId,
                    autoPlay = true,
                    autoLoop = true,
                    showControls = false,
                    muted = muted,
                    // Square source by Telegram protocol — seed the aspect so
                    // the texture letterboxes correctly on the first layout
                    // pass, before [onVideoSizeChanged] fires.
                    initialAspect = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Audio state chip — top-end. Subtle scrim disc so the glyph stays
            // legible against any poster brightness.
            VideoNoteAudioChip(
                muted = muted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        } else if (hasPlayback) {
            // Playback file exists but isn't cache-ready (autoplay off in
            // Settings, auto-download skipped, or mid-prefetch). Show the same
            // centred glyph the rest of the app uses for "tap to play".
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "play_arrow",
                    tint = Color.White,
                    size = 32.dp,
                    filled = true,
                )
            }
        } else {
            // No playback file at all (rare hydration path). The bubble is a
            // static poster + duration chip. Tap still routes to Telegram via
            // the clickable above.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "video_camera_front",
                    tint = Color.White,
                    size = 26.dp,
                )
            }
        }
        // Duration chip — bottom-trailing. Hidden for zero-duration sentinels
        // (same convention as [PlayBadge]).
        if (content.durationSec > 0) {
            DurationChip(
                text = formatDuration(content.durationSec),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun VideoNoteAudioChip(muted: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = if (muted) "volume_off" else "volume_up",
            tint = Color.White,
            size = 16.dp,
        )
    }
}

private val VIDEO_NOTE_DIAMETER = 220.dp

/**
 * Shared layout for non-playable file cards (document, audio, voice note).
 *
 * Hortay doesn't host an in-app download / playback path for these kinds — the
 * file lives on Telegram's CDN and decoding it here would replicate Telegram's
 * own player. Instead, the whole row is a `clickable` affordance that routes
 * the tap to [PostInteractions.onOpenClick], which deep-links into the official
 * Telegram client. The visual is identical to what was there before; only the
 * action wiring is new.
 */
@Composable
private fun NonPlayableFileRow(
    symbol: String,
    primary: String,
    secondary: String,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
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
        Spacer(Modifier.width(8.dp))
        Symbol(
            name = "open_in_new",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}

@Composable
private fun StickerBlock(content: PostContent.Sticker) {
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
private fun LocationBlock(content: PostContent.Location) {
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
private fun ContactBlock(content: PostContent.Contact) {
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

@Composable
private fun DiceBlock(content: PostContent.Dice) {
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

/**
 * Card for [PostContent.PaidMedia].
 *
 * Two layouts share this block:
 *
 *   - **Locked** (no items): render a single "⭐ N stars · Open in Telegram"
 *     card via [NonPlayableFileRow]. Tap leaves the app to the source post,
 *     where the user can complete the unlock flow in the official client.
 *   - **Unlocked** (items present): render the items exactly like a
 *     [PhotoAlbum] but stamp a small "⭐ N" chip on the top edge so the user
 *     knows the post is paid (otherwise it reads identically to a free album).
 *     The caption follows [captionAbove] in either case.
 */
@Composable
private fun PaidMediaBlock(
    content: PostContent.PaidMedia,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    maxLines: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
) {
    val caption = translation ?: content.caption
    val starsLabel = stringResource(R.string.content_paid_stars, content.starCount)
    if (content.isLocked) {
        NonPlayableFileRow(
            symbol = "lock",
            primary = stringResource(R.string.content_paid_locked),
            secondary = "$starsLabel · ${stringResource(R.string.content_open_in_telegram)}",
            onClick = onOpenInSource,
        )
        MediaCaption(caption, maxLines, above = false, show = true)
        return
    }
    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    Box(modifier = Modifier.fillMaxWidth()) {
        if (content.items.size == 1) {
            SingleMedia(content.items.first(), onClick = { onMediaClick(content.items, 0) })
        } else {
            AlbumRow(content.items, onItemClick = { idx -> onMediaClick(content.items, idx) })
        }
        DurationChip(
            text = starsLabel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

/**
 * Card for [PostContent.OpenInSource] — invoice / giveaway / story / game /
 * gift code. Hortay doesn't reimplement these flows in-app, but they're real
 * channel posts (not service noise) so we surface a labelled affordance with
 * an "Open in Telegram" chevron. Tap routes via [onOpenInSource] which lifts
 * to [PostInteractions.onOpenClick] in PostCard.
 */
@Composable
private fun OpenInSourceBlock(content: PostContent.OpenInSource, onOpenInSource: () -> Unit) {
    NonPlayableFileRow(
        symbol = content.iconSymbol,
        primary = content.title,
        secondary = content.subtitle.ifBlank { stringResource(R.string.content_open_in_telegram) },
        onClick = onOpenInSource,
    )
}

@Composable
private fun UnsupportedBlock(content: PostContent.Unsupported) {
    Text(
        text = content.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
private fun WebPreviewCard(preview: WebPreview) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = preview.url.isNotBlank(), onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WebPreviewLeading(preview)
        Spacer(Modifier.width(12.dp))
        WebPreviewMetadata(preview, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LargeWebPreview(preview: WebPreview, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = preview.url.isNotBlank(), onClick = onClick)
            .padding(12.dp),
    ) {
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
            .clip(MaterialTheme.shapes.small),
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
                .clip(MaterialTheme.shapes.small),
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
 * [dev.lyo.hortay.ui.icons.Symbol], so we avoid the silent `sym_help`
 * fallback that would otherwise mark every unknown preview with a question
 * mark.
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

@Composable
private fun BoxScope.PlayBadge(durationSec: Int, hideCircle: Boolean = false) {
    // [hideCircle] suppresses only the centred play glyph — the duration chip
    // stays so the user still has the "this is a video, N seconds long" cue
    // even while the poster spinner is up. The outer Box's `.clickable` is
    // unaffected: tapping the slot still mounts the player.
    if (!hideCircle) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = "play_circle",
                tint = Color.White,
                size = 36.dp,
            )
        }
    }
    // durationSec == 0 marks an unplayable video (currently only guest-mode
    // "Media is too big" posts where t.me strips `<video src>`). Showing a
    // "0:00" chip would lie about the post's length; just the play badge
    // reads correctly as "this is a video, tap to open it elsewhere".
    if (durationSec > 0) {
        DurationChip(
            text = formatDuration(durationSec),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

@Composable
private fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun IconBadge(symbol: String) {
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

/**
 * Caption row that wraps photo/video/animation/document blocks. Telegram supports
 * caption-above-media (set when the poster ticks "Show caption above") so each block
 * calls this twice — once with [above]=true, once below — and the [show] flag picks
 * which one renders. Spacer goes on the side adjacent to the media so the gap between
 * caption and media is consistent regardless of which side the caption lives on.
 */
@Composable
private fun MediaCaption(caption: FormattedText, maxLines: Int, above: Boolean, show: Boolean) {
    if (!show || caption.text.isBlank()) return
    if (!above) Spacer(Modifier.height(12.dp))
    RichText(
        formatted = caption,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = maxLines,
        renderer = { rt, style, lines -> ExpandableText(rt, style, lines) },
    )
    if (above) Spacer(Modifier.height(12.dp))
}

/**
 * Self-contained body Text + "Показати більше" toggle. The collapsed render binds the
 * layout callback to flip [canExpand] when Compose reports overflow at [maxLines]; the
 * toggle stays hidden until that signal lands so short posts never see it. Tapping the
 * toggle flips [expanded] and the same Text re-renders without a clamp.
 *
 * The Text itself is a [LinkAwareText] — long-press on a link surfaces the Open / Copy
 * / Share sheet without us threading any state through this composable. Expand state
 * is keyed on [renderable] so post edits / scroll-and-return reset to collapsed.
 */
@Composable
private fun ExpandableText(
    renderable: dev.lyo.hortay.ui.text.RenderableText,
    style: TextStyle,
    maxLines: Int,
) {
    if (maxLines == Int.MAX_VALUE) {
        // Detail surface — never collapse, never offer a toggle.
        dev.lyo.hortay.ui.text.LinkAwareText(renderable = renderable, style = style)
        return
    }
    // Key on [renderable.contentKey] (source-text identity) — survives recompositions
    // where `renderable` itself or its `text` AnnotatedString changes (lambda churn
    // on the wrapping data class, plus spoiler reveal flipping colour spans inside
    // the AnnotatedString). The user's "show more" choice now persists through
    // reactions, edits-that-don't-change-text, and spoiler reveals.
    var expanded by remember(renderable.contentKey) { mutableStateOf(false) }
    var canExpand by remember(renderable.contentKey) { mutableStateOf(false) }
    dev.lyo.hortay.ui.text.LinkAwareText(
        renderable = renderable,
        style = style,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (!expanded && layout.hasVisualOverflow) canExpand = true
        },
    )
    if (canExpand && !expanded) {
        Text(
            text = stringResource(R.string.post_show_more),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { expanded = true },
        )
    }
}

private fun mediaAspectRatio(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 10f
    val raw = width.toFloat() / height.toFloat()
    // Clamp to keep extreme verticals/horizontals readable in the feed.
    return raw.coerceIn(9f / 16f, 21f / 9f)
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Read-only probe into [dev.lyo.hortay.data.MediaCache] that answers "is the
 * playback file already on disk?" without enqueuing a download. Used by inline
 * autoplay to enforce the "honour auto-download policy" contract — only files
 * that the user's policy already pulled may auto-play.
 *
 * Behaviour split:
 *  • [fileId] == null  → guest (web) mode AlbumItem.Video uses [remoteUrl];
 *    no MediaCache slot exists, so we return `true` and let the caller's
 *    autoplay-master toggle be the sole gate (matches "no cache concept here").
 *  • [remoteUrl] also null with no fileId → not a real video slot; `false`.
 *  • Real TDLib fileId → observe the slot via [MediaCache.observe] (no side
 *    effect) and emit a single resync request on first mount so cold-start
 *    state reflects the on-disk reality. The resync routes a GetFile answer
 *    through MediaCache's single-writer reducer, which flips the slot to
 *    Ready when the file is present from a prior session.
 *
 * Crucially, this composable never calls [MediaCache.ensure]: a side-effect
 * here would defeat the whole point of the cached-gate. The user's
 * [AutoDownloadStore] policy + the auto-downloader's `UpdateNewMessage` hook
 * are the only legitimate paths to MediaState.Ready for inline-autoplay files;
 * if a video isn't Ready when the post lands, the poster + play-badge wait
 * for an explicit tap (which mounts [TdVideoPlayer] with the full
 * ensure-and-stream pathway).
 */
@Composable
private fun isCachedReady(fileId: Int?, remoteUrl: String?): Boolean {
    if (fileId == null || fileId == 0) {
        // Web-mode video: caller decides via the master autoplay flag.
        return remoteUrl != null
    }
    val cache = LocalMediaCache.current
    val state by remember(fileId) { cache.observe(fileId) }
        .collectAsStateWithLifecycle()
    LaunchedEffect(fileId) { cache.resync(fileId) }
    return state is MediaState.Ready
}

/**
 * Read-only probe into [dev.lyo.hortay.data.MediaCache] that answers "is the
 * poster file actively downloading right now?" — used by [MediaWithSpoiler]
 * to suppress the centred play badge while [TdMediaImage]'s own progress
 * overlay is on screen, so the user never sees the two stacked circles.
 *
 * Returns false for:
 *   - null / 0 fileIds (guest mode, no MediaCache slot to observe).
 *   - any non-Downloading state (Idle / Ready / Failed) — the badge surfaces
 *     normally and the user can tap to play / open the viewer.
 */
@Composable
private fun isPosterDownloading(fileId: Int?): Boolean {
    if (fileId == null || fileId == 0) return false
    val cache = LocalMediaCache.current
    val state by remember(fileId) { cache.observe(fileId) }
        .collectAsStateWithLifecycle()
    return state is MediaState.Downloading
}

private fun formatFileSize(bytes: Long, units: Array<String>): String {
    if (bytes <= 0) return "—"
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    val whole = size >= 100 || idx == 0 || size == size.toLong().toDouble()
    return if (whole) "${size.toLong()} ${units[idx]}" else "%.1f %s".format(size, units[idx])
}
