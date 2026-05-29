package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.text.LinkAwareText
import dev.lyo.hortay.ui.text.RenderableText
import dev.lyo.hortay.ui.text.RichText

/**
 * Render the body of a post. [onMediaClick] fires with the resolved media list and the
 * index the user tapped, so callers can open a full-screen viewer with the correct page.
 *
 * The per-content render is delegated to sibling block files (`Post*Blocks.kt`,
 * `PostWebPreview.kt`, `PollBlock.kt`) so a Compose-Compiler stability change in
 * one block doesn't ripple a full-file recompile through every other block. This
 * file owns only the dispatcher, the cross-block constants, and the small
 * helpers (text-expansion, caption layout, MediaCache probes) that more than
 * one block calls.
 */
@Composable
fun PostBody(
    content: PostContent,
    modifier: Modifier = Modifier,
    onMediaClick: (List<AlbumItem>, Int) -> Unit = { _, _ -> },
    /** When true, text is rendered without `maxLines` clamps — used in detail screens. */
    expanded: Boolean = false,
    /**
     * When true, the body is wrapped in a [SelectionContainer] so text can be selected.
     * Decoupled from [expanded]: inline surfaces (feed, comment rows, comments anchor) keep
     * this false because they own a long-press → action-sheet gesture that would race the
     * selection detector. Selection happens only in the dedicated "Select text" sheet, which
     * renders [PostBody] with `selectable = true` and no surrounding long-press.
     */
    selectable: Boolean = false,
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
    // Text selection is gated on [selectable], NOT [expanded]. Every inline surface
    // (feed, comment rows, comments anchor) owns a long-press → action-sheet gesture;
    // wrapping the body in a SelectionContainer there would race the long-press detector
    // (a press would alternately raise the sheet or the selection handles). So inline
    // renders stay non-selectable and the action sheet offers an explicit "Select text"
    // entry that re-renders this body with `selectable = true` in a sheet of its own,
    // where no long-press competes.
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
    if (selectable) {
        SelectionContainer(modifier = modifier) { body() }
    } else {
        Box(modifier = modifier) { body() }
    }
}

// ---- Cross-block constants ---------------------------------------------------

internal val STICKER_MAX_SIDE = 168.dp
internal val ANIMATED_EMOJI_MAX_SIDE = 96.dp
internal val SPOILER_BLUR_RADIUS = 28.dp
internal val UNPLAYABLE_VIDEO_BLUR_RADIUS = 8.dp

// Threshold for treating a video as "glance-able" — same heuristic Telegram uses for
// inline silent autoplay. Videos at or below this duration play muted-and-looping in
// the feed; longer ones keep the static poster + play-badge until the user opens
// fullscreen, where audio and controls are available.
internal const val INLINE_AUTOPLAY_MAX_SEC = 60

// ---- Cross-block helpers -----------------------------------------------------

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
internal fun stickerBoxModifier(width: Int, height: Int, maxSide: Dp): Modifier {
    if (width <= 0 || height <= 0) return Modifier.size(maxSide)
    val ratio = width.toFloat() / height.toFloat()
    return if (ratio >= 1f) {
        Modifier.width(maxSide).height(maxSide / ratio)
    } else {
        Modifier.width(maxSide * ratio).height(maxSide)
    }
}

internal fun mediaAspectRatio(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 10f
    val raw = width.toFloat() / height.toFloat()
    // Clamp to keep extreme verticals/horizontals readable in the feed.
    return raw.coerceIn(9f / 16f, 21f / 9f)
}

internal fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun formatFileSize(bytes: Long, units: Array<String>): String {
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
 *  • Real TDLib fileId → observe the slot via [dev.lyo.hortay.data.MediaCache.observe]
 *    (no side effect) and emit a single resync request on first mount so cold-start
 *    state reflects the on-disk reality. The resync routes a GetFile answer
 *    through MediaCache's single-writer reducer, which flips the slot to
 *    Ready when the file is present from a prior session.
 *
 * Crucially, this composable never calls [dev.lyo.hortay.data.MediaCache.ensure]:
 * a side-effect here would defeat the whole point of the cached-gate. The user's
 * [dev.lyo.hortay.data.AutoDownloadStore] policy + the auto-downloader's
 * `UpdateNewMessage` hook are the only legitimate paths to MediaState.Ready
 * for inline-autoplay files; if a video isn't Ready when the post lands, the
 * poster + play-badge wait for an explicit tap (which mounts [TdVideoPlayer]
 * with the full ensure-and-stream pathway).
 */
@Composable
internal fun isCachedReady(fileId: Int?, remoteUrl: String?): Boolean {
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
 * to suppress the centred play badge while [dev.lyo.hortay.ui.media.TdMediaImage]'s
 * own progress overlay is on screen, so the user never sees the two stacked circles.
 *
 * Returns false for:
 *   - null / 0 fileIds (guest mode, no MediaCache slot to observe).
 *   - any non-Downloading state (Idle / Ready / Failed) — the badge surfaces
 *     normally and the user can tap to play / open the viewer.
 */
@Composable
internal fun isPosterDownloading(fileId: Int?): Boolean {
    if (fileId == null || fileId == 0) return false
    val cache = LocalMediaCache.current
    val state by remember(fileId) { cache.observe(fileId) }
        .collectAsStateWithLifecycle()
    return state is MediaState.Downloading
}

/**
 * Caption row that wraps photo/video/animation/document blocks. Telegram supports
 * caption-above-media (set when the poster ticks "Show caption above") so each block
 * calls this twice — once with [above]=true, once below — and the [show] flag picks
 * which one renders. Spacer goes on the side adjacent to the media so the gap between
 * caption and media is consistent regardless of which side the caption lives on.
 */
@Composable
internal fun MediaCaption(caption: FormattedText, maxLines: Int, above: Boolean, show: Boolean) {
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
internal fun ExpandableText(
    renderable: RenderableText,
    style: TextStyle,
    maxLines: Int,
) {
    if (maxLines == Int.MAX_VALUE) {
        // Detail surface — never collapse, never offer a toggle.
        LinkAwareText(renderable = renderable, style = style)
        return
    }
    // Key on [renderable.contentKey] (source-text identity) — survives recompositions
    // where `renderable` itself or its `text` AnnotatedString changes (lambda churn
    // on the wrapping data class, plus spoiler reveal flipping colour spans inside
    // the AnnotatedString). The user's "show more" choice now persists through
    // reactions, edits-that-don't-change-text, and spoiler reveals.
    var expanded by remember(renderable.contentKey) { mutableStateOf(false) }
    var canExpand by remember(renderable.contentKey) { mutableStateOf(false) }
    // In a reverseLayout feed the post would grow upward on expand and dump the reader at
    // its end; the feed supplies this to pin the post's top so the new lines reveal
    // downward instead. Null off the feed → no-op.
    val keepScrollOnExpand = LocalExpandScrollKeeper.current
    LinkAwareText(
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
                .clickable {
                    keepScrollOnExpand?.invoke()
                    expanded = true
                },
        )
    }
}
