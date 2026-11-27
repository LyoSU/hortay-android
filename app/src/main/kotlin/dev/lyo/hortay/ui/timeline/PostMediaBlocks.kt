package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.InlineAutoplay
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.hasSpoiler
import dev.lyo.hortay.data.isSecret
import dev.lyo.hortay.data.isUnplayableVideo
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalInlineVideoAutoplay
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.media.SpoilerKind
import dev.lyo.hortay.ui.media.SpoilerOverlay
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.TdVideoPlayer
import dev.lyo.hortay.ui.media.mediaSpoilerBlur
import dev.lyo.hortay.ui.media.rememberMediaSpoilerState
import dev.lyo.hortay.ui.media.rememberViewerSourceAnchor
import dev.lyo.hortay.ui.media.viewerSource
import dev.lyo.hortay.ui.theme.mediaFrame

@Composable
internal fun AlbumBlock(
    content: PostContent.PhotoAlbum,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    maxLines: Int,
    translation: FormattedText?,
    albumBleed: Dp = 0.dp,
) {
    val items = content.items
    if (items.isEmpty()) return
    val caption = translation ?: content.caption

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    if (items.size == 1) {
        SingleMedia(items.first(), onClick = { onMediaClick(items, 0) })
    } else {
        AlbumRow(items, onItemClick = { idx -> onMediaClick(items, idx) }, bleed = albumBleed)
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
}

@Composable
internal fun SingleMedia(item: AlbumItem, onClick: () -> Unit, isActive: Boolean = true) {
    val ratio = mediaAspectRatio(item.media.width, item.media.height)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium)
            // D1 — feed photo / video-poster / animation thumbnail is rectangular
            // photographic content (stickers route through StickerBlock, never here),
            // so the hairline frame applies on the SAME shape it clips to. Keeps a
            // pale screenshot / meme from dissolving into the near-white canvas.
            .mediaFrame(MaterialTheme.shapes.medium),
    ) {
        MediaWithSpoiler(item = item, onClick = onClick, isActive = isActive)
    }
}

/**
 * Renders a single album item — photo, video poster, or animation thumbnail — with
 * an opt-in spoiler / sensitive-content cover. The spoiler shimmer intercepts taps
 * and reveals the underlying media; once revealed, taps fall through to [onClick]
 * (fullscreen open). Used by both [SingleMedia] and the inside of [AlbumRow].
 *
 * For short videos (≤ [INLINE_AUTOPLAY_MAX_SEC]) we render a silent looping
 * [TdVideoPlayer] on top of the poster instead of a static play badge — Telegram's
 * own UX for "glance-able" clips. The poster stays underneath so the slot has a
 * frame to show while the playback file streams in. [isActive] gates this for
 * pager pages: only the page the user is on streams; neighbours show the still
 * (so an album of 5 clips doesn't spawn five ExoPlayers).
 */
@Composable
internal fun MediaWithSpoiler(
    item: AlbumItem,
    onClick: () -> Unit,
    isActive: Boolean = true,
    sourceShape: Shape = MaterialTheme.shapes.medium,
) {
    // Every tile registers as a fullscreen-viewer transition source (see
    // [rememberViewerSourceAnchor]): the viewer finds it by media to grow out of it and
    // shrink back into it. [sourceShape] is the clip the caller applies around this tile,
    // so the viewer's corner radius lands on the tile's own corners.
    val viewerAnchor = rememberViewerSourceAnchor(item.media, sourceShape)
    val spoiler = rememberMediaSpoilerState(item.media.fileId, covered = item.hasSpoiler || item.isSecret)
    val revealed = spoiler.isRevealed
    val unplayable = item.isUnplayableVideo
    val inlineAutoplayEnabled = LocalInlineVideoAutoplay.current
    // Deleted-post tombstone: media is observe-only. No autoplay (would mount an
    // ExoPlayer + drive playback on a server-deleted file), no tap-to-open (the card
    // tap already routes to the revision sheet), no play badge (implies tappable
    // playback we don't offer). See [LocalMediaPassive].
    val passive = LocalMediaPassive.current
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
        // Feed video keeps the duration heuristic via [InlineAutoplay.ShortClip];
        // rich (instant-view) video carries TDLib's explicit `needAutoplay` as
        // [InlineAutoplay.Always] / [InlineAutoplay.Never] and skips the heuristic.
        && asVideo.autoplayAllowed()
        && inlineAutoplayEnabled
        && !passive
    // K2 smart-casts asVideo to AlbumItem.Video — autoplayEligible's chain
    // includes `asVideo != null` and short-circuit && propagates the cast.
    val autoplayVideo = autoplayEligible && isCachedReady(
        fileId = asVideo.playbackFileId,
        remoteUrl = asVideo.remoteVideoUrl,
    )
    // Animation inline autoplay. The feed never routes here for its always-on GIFs
    // (its dedicated [AnimationBlock] owns that path); album animations default to
    // [InlineAutoplay.Never] and stay a static poster + "GIF" chip. Only a rich
    // animation block with TDLib `needAutoplay = true` ([InlineAutoplay.Always])
    // opts into inline playback, reusing the same cache-ready + master-toggle +
    // active-page gates as the video branch above — no separate visibility tracker.
    val asAnimation = item as? AlbumItem.Animation
    val animationAutoplayEligible = revealed
        && isActive
        && asAnimation != null
        && asAnimation.autoplay == InlineAutoplay.Always
        && inlineAutoplayEnabled
        && !passive
    val autoplayAnimation = animationAutoplayEligible && isCachedReady(
        fileId = asAnimation.playbackFileId,
        remoteUrl = asAnimation.remoteVideoUrl,
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
    // Letterbox backdrop: the poster is cropped to the slot while the player fits the
    // video inside it, so whenever the two aspects differ (the 21:9 / 9:16 feed clamp, or
    // poster geometry that disagrees with the stream) the poster's sharp edges showed as a
    // second copy around the moving picture. Once the first frame is on screen the poster
    // blurs and dims into a backdrop; before that it stays sharp so the slot never flashes
    // a blurred preview. Keyed on the player being mounted so a remount starts sharp again.
    var videoFrameShown by remember(autoplayVideo || autoplayAnimation) { mutableStateOf(false) }
    val backdrop = rememberLetterboxBackdrop(videoFrameShown)
    val onFirstFrame = remember { { videoFrameShown = true } }
    val posterLoading = !autoplayVideo &&
        revealed &&
        isPosterDownloading(item.media.fileId)
    // Blur regime:
    //   • spoiler / sensitive: heavy blur until the user reveals, un-blurring from the tap
    //     point while the reveal circle grows ([mediaSpoilerBlur]).
    //   • unplayable video: light blur as a visual "this is a preview, you'll need to
    //     open Telegram to actually watch it" cue. Keeps the poster recognisable while
    //     making clear the in-app player can't drive it.
    // Media-kind description for the primary post image — resolved once here
    // rather than per-call since it only depends on the sealed [AlbumItem] type.
    val mediaDescription = when (item) {
        is AlbumItem.Photo -> stringResource(R.string.media_desc_photo)
        is AlbumItem.Video -> stringResource(R.string.media_desc_video)
        is AlbumItem.Animation -> stringResource(R.string.media_desc_gif)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .viewerSource(viewerAnchor)
            .clickable(enabled = revealed && !passive, role = Role.Button, onClick = onClick),
    ) {
        // When the autoplayer mounts on top, suppress the poster's own progress spinner —
        // TdVideoPlayer renders its own MediaLoadingOverlay for the playback file, and a
        // poster-side spinner would stack visibly on top of it ("два кружки в центрі").
        // Spoiler blur: while covered, the underlying photo is heavily blurred via
        // RenderEffect; pre-S the overlay's minithumb cover stands in for it.
        TdMediaImage(
            media = item.media,
            contentDescription = mediaDescription,
            showProgress = !(autoplayVideo || autoplayAnimation),
            modifier = Modifier
                .fillMaxSize()
                .let { if (unplayable) it.blur(UNPLAYABLE_VIDEO_BLUR_RADIUS) else it }
                .mediaSpoilerBlur(spoiler, SPOILER_BLUR_RADIUS)
                .letterboxBackdrop(backdrop),
        )
        when {
            autoplayVideo -> {
                TdVideoPlayer(
                    fileId = asVideo.playbackFileId,
                    remoteUrl = asVideo.remoteVideoUrl,
                    autoPlay = true,
                    // Feed clips loop; rich video honours TDLib's `isLooped` via [loop].
                    autoLoop = asVideo.loop,
                    showControls = false,
                    muted = true,
                    // Seed the player's [AspectRatioFrameLayout] with the poster's
                    // geometry so the first layout pass already matches the outer
                    // [SingleMedia] [Modifier.aspectRatio]. Without it the texture
                    // fills the parent box until [Player.Listener.onVideoSizeChanged]
                    // fires, visible as a brief stretch-then-snap on autoplay mount.
                    initialAspect = asVideo.media.aspectRatio,
                    onFirstFrameRendered = onFirstFrame,
                    modifier = Modifier.fillMaxSize(),
                )
                DurationChip(
                    text = formatDuration(asVideo.durationSec),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
            autoplayAnimation -> {
                // Silent looping MP4 GIF — same inline path the feed's [AnimationBlock]
                // uses, kept to the "GIF" chip affordance (no play badge, per the rich
                // animation design). Loops unconditionally (animations are inherently
                // looped; TDLib exposes no `isLooped` on `pageBlockAnimation`).
                TdVideoPlayer(
                    fileId = asAnimation.playbackFileId,
                    remoteUrl = asAnimation.remoteVideoUrl,
                    autoPlay = true,
                    autoLoop = true,
                    showControls = false,
                    muted = true,
                    initialAspect = asAnimation.media.aspectRatio,
                    onFirstFrameRendered = onFirstFrame,
                    modifier = Modifier.fillMaxSize(),
                )
                DurationChip(
                    text = "GIF",
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
            else -> MediaOverlay(item, hidePlayBadge = posterLoading || passive)
        }
        if (!revealed) {
            SpoilerOverlay(
                state = spoiler,
                kind = if (item.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = item.media.fileId ?: 0,
                coverThumb = item.media.minithumbBytes,
            )
        }
    }
}

/**
 * Whether this video slot is eligible for silent inline autoplay, by policy:
 *  - [InlineAutoplay.ShortClip] (feed default) — the duration heuristic;
 *  - [InlineAutoplay.Always] (rich `needAutoplay = true`) — always;
 *  - [InlineAutoplay.Never] (rich `needAutoplay = false`) — never (static poster + play badge).
 * The cache-ready / master-toggle / active-page gates apply on top of this in [MediaWithSpoiler].
 */
private fun AlbumItem.Video.autoplayAllowed(): Boolean = when (autoplay) {
    InlineAutoplay.Never -> false
    InlineAutoplay.ShortClip -> durationSec in 1..INLINE_AUTOPLAY_MAX_SEC
    InlineAutoplay.Always -> true
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
        is AlbumItem.Photo -> if (item.isLive) {
            LivePhotoBadge(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
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
 * preserves the same "one-player invariant" the old HorizontalPager version had via
 * state.currentPage.
 *
 * Edge-to-edge strip: [bleed] is the horizontal inset of the surface around this row (the
 * post card's content padding). The scrolling viewport is widened by [bleed] on both sides
 * ([bleedHorizontal]) and given the same amount back as `contentPadding`, so at rest the first
 * item still starts at the text edge and the last one ends at the content end edge, but while
 * swiping the photos travel all the way to the card edge instead of being clipped at the
 * content inset (a visible gutter before). Sizing (row height, min / max item width) still
 * derives from the CONTENT width — the [BoxWithConstraints] sits outside the bleed — so a
 * photo is exactly as large as before; only the clip window grew. Hosts whose surroundings
 * must not be overflowed (comment rows indented past an avatar) keep the default 0.
 *
 * Snapping uses [SnapPosition.Center], which is what `rememberSnapFlingBehavior(state)` always
 * defaulted to; it centres an item inside the area between the content paddings (Compose
 * subtracts `beforeContentPadding` / `afterContentPadding`), so the bleed does not shift where
 * items rest, and the first / last items still clamp to the start / end insets.
 */
@Composable
internal fun AlbumRow(items: List<AlbumItem>, onItemClick: (Int) -> Unit, bleed: Dp = 0.dp) {
    val state = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(state, snapPosition = SnapPosition.Center)
    val activeIndex by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            // Item offsets are measured from the end of the start content padding, so the
            // centre of the content column (the same point Center snapping targets) is half of
            // the viewport minus both paddings. With symmetric padding this is also the centre
            // of the whole bled viewport.
            val contentCenter =
                (info.viewportSize.width - info.beforeContentPadding - info.afterContentPadding) / 2
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - contentCenter) }
                ?.index ?: 0
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val rowWidth = maxWidth
            val rowHeight = rowWidth * 0.75f
            val maxItemWidth = rowWidth * 0.92f
            val minItemWidth = rowWidth * 0.42f

            LazyRow(
                state = state,
                flingBehavior = flingBehavior,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = bleed),
                modifier = Modifier
                    .bleedHorizontal(bleed)
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
                            // D2 — album inner tiles are nested media, so they take the
                            // tighter nested radius (shapes.small, 12 dp) rather than the
                            // in-card shapes.medium used by the single-media block.
                            .clip(MaterialTheme.shapes.small)
                            // D1 — rectangular photographic content, frame on the same shape.
                            .mediaFrame(MaterialTheme.shapes.small),
                    ) {
                        MediaWithSpoiler(
                            item = item,
                            onClick = { onItemClick(index) },
                            isActive = index == activeIndex,
                            sourceShape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }
        // Dots under the strip, driven by the centred item — a live "how much is left to
        // scroll" cue for the snapping strip.
        AlbumPositionDots(
            current = activeIndex,
            count = items.size,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

@Composable
internal fun VideoBlock(content: PostContent.Video, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
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
internal fun AnimationBlock(content: PostContent.Animation, onMediaClick: (List<AlbumItem>, Int) -> Unit, maxLines: Int, translation: FormattedText?) {
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

    val spoiler = rememberMediaSpoilerState(content.playbackFileId, covered = content.hasSpoiler || content.isSecret)
    val revealed = spoiler.isRevealed
    // Tombstone: keep the static (minithumb/cached) poster, but never mount the
    // autoplayer or route a tap to a server-deleted file. See [LocalMediaPassive].
    val passive = LocalMediaPassive.current
    val viewerAnchor = rememberViewerSourceAnchor(content.media, MaterialTheme.shapes.medium)
    // Same letterbox backdrop as [MediaWithSpoiler].
    val playerMounted = revealed && !passive
    var videoFrameShown by remember(playerMounted) { mutableStateOf(false) }
    val backdrop = rememberLetterboxBackdrop(videoFrameShown)
    val onFirstFrame = remember { { videoFrameShown = true } }

    MediaCaption(caption, maxLines, above = true, show = content.captionAbove)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium)
            // D1 — inline animation (silent MP4 GIF) is opaque rectangular video content.
            .mediaFrame(MaterialTheme.shapes.medium)
            .viewerSource(viewerAnchor)
            .clickable(enabled = revealed && !passive, role = Role.Button) { onMediaClick(items, 0) },
    ) {
        // Same suppression as MediaWithSpoiler: when the GIF autoplayer is mounted, its own
        // MediaLoadingOverlay covers the loading state — the poster's spinner would just
        // stack on top. When the spoiler is up, blur the still poster too.
        TdMediaImage(
            media = content.media,
            contentDescription = stringResource(R.string.media_desc_gif),
            showProgress = !revealed,
            modifier = Modifier
                .fillMaxSize()
                .mediaSpoilerBlur(spoiler, SPOILER_BLUR_RADIUS)
                .letterboxBackdrop(backdrop),
        )
        // Only mount the video player once the spoiler is revealed — otherwise we'd start
        // an ExoPlayer + TDLib download for content the user explicitly hasn't asked to see
        // yet, which is exactly the leak the spoiler/secret flags are meant to prevent.
        // Passive tombstones likewise keep the static poster only — the "GIF" chip stays so
        // the reader still knows what the deleted post carried.
        if (revealed) {
            if (!passive) {
                TdVideoPlayer(
                    fileId = content.playbackFileId,
                    remoteUrl = content.remoteVideoUrl,
                    autoPlay = true,
                    autoLoop = true,
                    showControls = false,
                    muted = true,
                    // Same seed-before-decoder pattern as the inline video branch
                    // above; the GIF's [TdMedia.width] / [TdMedia.height] match the
                    // animation stream so the poster and the player layout in a
                    // single frame.
                    initialAspect = content.media.aspectRatio,
                    onFirstFrameRendered = onFirstFrame,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DurationChip(text = "GIF", modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        } else {
            SpoilerOverlay(
                state = spoiler,
                kind = if (content.isSecret) SpoilerKind.Sensitive else SpoilerKind.Spoiler,
                seed = content.playbackFileId,
                coverThumb = content.media.minithumbBytes,
            )
        }
    }
    MediaCaption(caption, maxLines, above = false, show = !content.captionAbove)
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
 *     [PostContent.PhotoAlbum] but stamp a small "⭐ N" chip on the top edge so the user
 *     knows the post is paid (otherwise it reads identically to a free album).
 *     The caption follows [captionAbove] in either case.
 */
@Composable
internal fun PaidMediaBlock(
    content: PostContent.PaidMedia,
    onMediaClick: (List<AlbumItem>, Int) -> Unit,
    maxLines: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
    albumBleed: Dp = 0.dp,
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
            AlbumRow(content.items, onItemClick = { idx -> onMediaClick(content.items, idx) }, bleed = albumBleed)
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

@Composable
internal fun BoxScope.PlayBadge(durationSec: Int, hideCircle: Boolean = false) {
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
internal fun DurationChip(text: String, modifier: Modifier = Modifier) {
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

/**
 * 0 → 1 progress of the poster turning into a letterbox backdrop behind inline video,
 * eased with the theme's effects spec. Read in the draw phase by [letterboxBackdrop].
 */
@Composable
private fun rememberLetterboxBackdrop(active: Boolean): () -> Float {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "letterboxBackdrop",
    )
    return remember { { progress } }
}

/**
 * Blurs (API 31+) and dims the poster under a playing inline video, so the bands the
 * fitted video leaves read as a soft backdrop instead of a second sharp copy of the
 * picture. Below API 31 RenderEffect is unavailable and only the dim applies.
 */
private fun Modifier.letterboxBackdrop(progress: () -> Float): Modifier = this
    .graphicsLayer {
        val p = progress()
        renderEffect = if (p > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = LETTERBOX_BLUR_RADIUS.toPx() * p
            BlurEffect(r, r, TileMode.Clamp)
        } else {
            null
        }
        clip = p > 0f
    }
    .drawWithContent {
        drawContent()
        val p = progress()
        if (p > 0f) drawRect(Color.Black, alpha = LETTERBOX_SCRIM_ALPHA * p)
    }

private val LETTERBOX_BLUR_RADIUS = 24.dp
private const val LETTERBOX_SCRIM_ALPHA = 0.35f
