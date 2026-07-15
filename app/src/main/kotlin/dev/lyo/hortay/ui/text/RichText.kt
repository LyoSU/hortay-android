package dev.lyo.hortay.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.floor

/**
 * When non-null, tapping "Показати більше" on a clamped post opens the post-detail (comments)
 * screen instead of expanding the body inline — the same destination a card tap reaches.
 * Supplied by the auth feed / channel LazyColumn (both feed orders) so "open the post" is one
 * action. `null` off the feed and in guest mode, where there's no post-detail to open and
 * "Показати більше" falls back to an in-place inline expand. Lives in `ui.text` so the
 * timeline's `ExpandableText` can read it without `ui.text` depending on `ui.timeline`.
 */
internal val LocalShowFullPost = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Renderer for [FormattedText]. Block quotes / code blocks render the SAME way on every
 * surface — feed, channel, comments, full post — as padded [BlockBox] composables. A post
 * carrying a block is split into alternating plain-text / block segments stacked in a Column.
 *
 * Clamping is POST-WIDE, not per-element: on a clamped surface (finite [maxLines]) the whole
 * segmented column is wrapped in [ClampedPost], which caps it to ~`maxLines × line-height` and
 * shows exactly ONE "Показати більше". Plain-text segments render uncapped (the outer clamp is
 * the only clamp), so the post reads as a single unit and the cut can fall anywhere — including
 * inside a quote box — matching Telegram. The cut is SNAPPED to the nearest line boundary of
 * whatever segment straddles the budget, so it never bisects a glyph row (the "half a line"
 * artifact); inside a quote box it lands on the quote's own line boundary.
 *
 * Why post-wide and not per-element: per-element clamping (each plain-text run flowing through
 * [renderer] with its own [maxLines] + its own "Показати більше") splits one post into N+1
 * toggles once a block divides the body, and the collapsed height balloons to `18 × (N+1)`
 * lines instead of 18 total — a long post with two quotes showed two or three "Показати більше"
 * buttons. (Tried that — see commit `32f2472`; it cut on clean line boundaries but fragmented
 * the toggle.) The earlier post-wide attempt (`5cc8242`) was reverted for two reasons, both
 * fixed here:
 *   1. The pixel-height cut fell mid-line inside a quote box — now SNAPPED to the straddling
 *      segment's line boundary by [ClampedPost], so the cut stays on a clean row (it may still
 *      fall inside a quote, just never through the middle of a glyph row).
 *   2. The outer clamp fought each quote's own expand chevron (expanding a quote got re-clipped)
 *      — now [BlockBox] is rendered NON-INTERACTIVE while the post is collapsed (no chevron;
 *      the single post-level "Показати більше" is the only affordance). Chevrons come back only
 *      once the post is expanded inline or on a full-reading surface, where there is no clamp
 *      left to fight.
 *
 * Posts without any block skip segmentation and render as one [renderer] call (the cheap
 * line-clamped path). Top-level blocks only — a nested block (rare) still renders inline within
 * its parent box via [LinkAwareText]'s draw-behind path.
 */
@Composable
fun RichText(
    formatted: FormattedText,
    style: TextStyle,
    maxLines: Int,
    renderer: (@Composable (RenderableText, TextStyle, Int) -> Unit),
) {
    // Trim blank edges of the whole body first (stray leading / trailing newlines and
    // spaces TDLib or the web parser leave behind), so no surface renders a post with
    // empty lines hanging off the top or bottom.
    val src = remember(formatted) { formatted.trimmedBlankEdges() }
    val blocks = remember(src) { src.blockRanges() }
    // No top-level block → one [Text] with the caller's clamp + "Показати більше".
    if (blocks.isEmpty()) {
        renderer(rememberRenderableText(src), style, maxLines)
        return
    }

    val segments = remember(src, blocks) { buildSegments(src, blocks).toImmutableList() }

    if (maxLines == Int.MAX_VALUE) {
        // Full-reading surface (open post / comments): no outer clamp, blocks interactive.
        Column {
            segments.forEachIndexed { idx, segment ->
                // Segments are individually edge-trimmed, so spacing is a single consistent
                // gap rather than whatever stray newlines the source happened to carry.
                if (idx > 0) Spacer(Modifier.height(SEGMENT_GAP))
                SegmentSlot(segment, style, interactive = true, renderer)
            }
        }
    } else {
        // Clamped surface (feed / channel / caption): one post-level height clamp + ONE toggle.
        // [ClampedPost] lays the segments out itself (rather than receiving a pre-built Column)
        // so it can see each segment's height + its own vertical insets and snap the cut to a
        // clean line boundary instead of slicing a glyph row.
        ClampedPost(key = src, maxLines = maxLines, style = style, segments = segments, renderer = renderer)
    }
}

/** Inter-segment vertical gap (plain text ↔ block box). Shared by the full-reading Column and
 *  [ClampedPost]'s manual layout so both stack segments identically. */
private val SEGMENT_GAP = 8.dp

/**
 * One body segment: a plain-text run ([Segment.block] == null, rendered through the caller's
 * [renderer] at MAX so it never grows a "Показати більше" of its own — the only toggle is
 * post-level) or a quote / code [BlockBox]. [interactive] = "the post is fully shown": a clamped
 * preview passes `false` so blocks are frozen (no chevron to fight the outer clip).
 */
@Composable
private fun SegmentSlot(
    segment: Segment,
    style: TextStyle,
    interactive: Boolean,
    renderer: @Composable (RenderableText, TextStyle, Int) -> Unit,
) {
    val block = segment.block
    if (block != null) {
        BlockBox(text = segment.text, style = style, blockStyle = block, interactive = interactive)
    } else {
        renderer(rememberRenderableText(segment.text), style, Int.MAX_VALUE)
    }
}

/**
 * Caps a segmented post (text + quote/code boxes) to roughly [maxLines] worth of height and
 * reveals it whole with a single "Показати більше". There is no single backing [Text] to carry
 * a line clamp, so this lays the [segments] out itself (one [SegmentSlot] per segment, stacked
 * with [SEGMENT_GAP]) and clips the column to the budget when it overflows.
 *
 * Why it owns the layout: to clip on a CLEAN line boundary it has to know, for the segment that
 * straddles the budget, where that segment's text lines sit. The lines all share the body
 * `line-height`, but each block box shifts them off the post's global grid by its top inset
 * (8 dp padding, plus a code block's language header). Seeing each segment's measured height and
 * computing its inset lets the clip land on the last line that fully fits — inside a quote box if
 * that's where the budget falls, but never through the middle of a glyph row (the "half a line"
 * artifact that an unsnapped `maxLines × line-height` pixel cut produced).
 *
 * [expanded] is forwarded as each block's `interactive` flag so quote/code chevrons stay dormant
 * while the post is a clamped preview (they would otherwise grow content the outer clip
 * immediately hides — the bug that reverted the first post-wide clamp). Tapping the toggle opens
 * the full post via [LocalShowFullPost] when present (feed / channel), else expands in place
 * (guest mode / captions).
 */
@Composable
private fun ClampedPost(
    key: Any,
    maxLines: Int,
    style: TextStyle,
    segments: ImmutableList<Segment>,
    renderer: @Composable (RenderableText, TextStyle, Int) -> Unit,
) {
    var expanded by remember(key) { mutableStateOf(false) }
    var overflow by remember(key) { mutableStateOf(false) }
    val showFullPost = LocalShowFullPost.current
    val density = LocalDensity.current

    val bodyLine = when {
        style.lineHeight.isSp -> style.lineHeight
        style.fontSize.isSp -> style.fontSize * 1.4f
        else -> 20.sp
    }
    val codeHeader = MaterialTheme.typography.labelSmall
    val codeHeaderLine = when {
        codeHeader.lineHeight.isSp -> codeHeader.lineHeight
        codeHeader.fontSize.isSp -> codeHeader.fontSize * 1.4f
        else -> 16.sp
    }
    val lineHeightPx = with(density) { bodyLine.toPx() }
    val maxHeightPx = (lineHeightPx * maxLines).toInt()
    val gapPx = with(density) { SEGMENT_GAP.toPx() }.toInt()
    val blockPadPx = with(density) { BLOCK_VPAD.toPx() }
    val codeHeaderPx = with(density) { (codeHeaderLine.toPx() + CODE_HEADER_GAP.toPx()) }
    // Distance from each segment's TOP edge to its first text line (0 for plain text, the box's
    // top padding for a quote, plus the language-header strip for a code block) and from its
    // BOTTOM text line to its bottom edge. Used to snap the clip onto a line boundary.
    val topInsets = segments.map { seg ->
        when (val b = seg.block) {
            is FormattedText.Style.Pre -> blockPadPx + (if (!b.language.isNullOrBlank()) codeHeaderPx else 0f)
            is FormattedText.Style.BlockQuote -> blockPadPx
            else -> 0f
        }
    }
    val bottomInsets = segments.map { if (it.block != null) blockPadPx else 0f }

    Column {
        Layout(
            modifier = Modifier.clipToBounds(),
            content = { segments.forEach { SegmentSlot(it, style, interactive = expanded, renderer) } },
        ) { measurables, constraints ->
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val full = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
            val over = full > maxHeightPx
            // The toggle sits OUTSIDE this Layout, so flipping it never changes what this Layout
            // measures — `over` converges on the second pass and never loops.
            if (overflow != over) overflow = over

            val clipH = if (expanded || !over) {
                full
            } else {
                // Walk segments top-to-bottom; find the one that straddles the budget and snap the
                // cut to its last fully-visible text line.
                var y = 0
                var cut = 0
                for (i in placeables.indices) {
                    val childTop = if (i == 0) 0 else y + gapPx
                    val childBottom = childTop + placeables[i].height
                    if (maxHeightPx >= childBottom) { cut = childBottom; y = childBottom; continue }
                    cut = when {
                        // Budget falls in the gap above this segment → end at the previous one.
                        maxHeightPx <= childTop -> y
                        else -> {
                            val textTop = childTop + topInsets[i]
                            val textHeight = placeables[i].height - topInsets[i] - bottomInsets[i]
                            val visibleText = maxHeightPx - textTop
                            when {
                                // Budget lands above the first text line (inside the box's top
                                // inset) → show nothing of this segment.
                                visibleText <= 0f -> childTop
                                // Every text line fits; the budget fell in the bottom inset →
                                // keep the whole segment, padding and all.
                                visibleText >= textHeight -> childBottom
                                else -> (textTop + floor(visibleText / lineHeightPx) * lineHeightPx).toInt()
                            }
                        }
                    }
                    break
                }
                cut.coerceIn(0, full)
            }

            layout(width, clipH) {
                var y = 0
                placeables.forEachIndexed { i, p ->
                    if (i > 0) y += gapPx
                    p.place(0, y)
                    y += p.height
                }
            }
        }
        if (!expanded && overflow) {
            Text(
                text = stringResource(R.string.post_show_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable {
                        if (showFullPost != null) showFullPost() else expanded = true
                    },
            )
        }
    }
}

/**
 * Post-wide height clamp for an arbitrary body [content], the generic sibling of [ClampedPost].
 * [ClampedPost] can only lay out a [FormattedText]'s segments; a rich message
 * ([dev.lyo.hortay.data.PostContent.RichMessage]) is a heterogeneous block tree (headings,
 * media, tables) with no single text grid, so it can't ride that path. This shares the same
 * shell — cap to roughly [maxLines] × the [style] line height, reveal whole with a single
 * "Показати більше" — minus the line-boundary snapping (there is no glyph row to snap onto in a
 * table or a photo; a plain height clip is the honest cut). The clamp budget is computed from
 * the same `maxLines` a text post uses, so a rich post collapses to the same feed height.
 *
 * Tapping the toggle opens the full post via [LocalShowFullPost] when present (feed / channel),
 * else expands in place (guest mode / captions) — identical to [ClampedPost].
 *
 * [fadeColor], when set, paints a soft bottom scrim (blending to that colour — pass the host
 * card's container colour, NOT white) over the last ~2 lines of the clipped content whenever it
 * actually overflows, so a rich feed preview dissolves into its "read full post" affordance
 * instead of hard-cutting. [affordance], when set, REPLACES the default "Показати більше" text
 * with a caller-supplied composable (e.g. the rich "Read full post" button); it receives the
 * same expand action the default toggle uses. [forceAffordance] shows that affordance even when
 * the content fits the pixel budget — used when the document was projected or is partial, so
 * there is more to read past the fold than the clamp alone can detect.
 */
@Composable
internal fun ClampedContent(
    key: Any,
    maxLines: Int,
    style: TextStyle,
    fadeColor: Color? = null,
    forceAffordance: Boolean = false,
    affordance: (@Composable (onExpand: () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember(key) { mutableStateOf(false) }
    var overflow by remember(key) { mutableStateOf(false) }
    val showFullPost = LocalShowFullPost.current
    val density = LocalDensity.current

    val bodyLine = when {
        style.lineHeight.isSp -> style.lineHeight
        style.fontSize.isSp -> style.fontSize * 1.4f
        else -> 20.sp
    }
    val maxHeightPx = with(density) { (bodyLine.toPx() * maxLines).toInt() }
    val fadeHeightPx = with(density) { bodyLine.toPx() * 2f }

    Column {
        // Fade only when the content is genuinely clipped (over && !expanded) — a projected /
        // partial document that fits the budget shows the affordance but no scrim over real text.
        val fade = fadeColor
        val fadeModifier = if (fade != null && overflow && !expanded) {
            Modifier.drawWithContent {
                drawContent()
                val fh = fadeHeightPx.coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, fade),
                        startY = size.height - fh,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fh),
                    size = Size(size.width, fh),
                )
            }
        } else {
            Modifier
        }
        Layout(
            modifier = Modifier.clipToBounds().then(fadeModifier),
            content = content,
        ) { measurables, constraints ->
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val full = placeables.sumOf { it.height }
            val over = full > maxHeightPx
            // The toggle sits OUTSIDE this Layout (in the enclosing Column), so flipping
            // `overflow` never changes what this Layout measures — it converges in one pass.
            if (overflow != over) overflow = over
            val clipH = if (expanded || !over) full else maxHeightPx
            layout(width, clipH) {
                var y = 0
                placeables.forEach { p -> p.place(0, y); y += p.height }
            }
        }
        if (!expanded && (overflow || forceAffordance)) {
            val onExpand = { if (showFullPost != null) showFullPost() else expanded = true }
            if (affordance != null) {
                affordance(onExpand)
            } else {
                Text(
                    text = stringResource(R.string.post_show_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onExpand),
                )
            }
        }
    }
}

/** Lines an explicitly-collapsible quote previews at on an interactive surface (full post /
 *  comments, or a post expanded inline) before its chevron reveals the rest, so a long
 *  collapsible quote teases compactly instead of as a tall wall. Frozen previews inside a
 *  collapsed [ClampedPost] ignore this — they render full and the post-level clip cuts. */
private const val COLLAPSED_QUOTE_LINES = 3

/** Top + bottom padding inside a [BlockBox]. Shared with [ClampedPost]'s inset math so the cut
 *  snaps onto the box's real text-line grid; changing it here keeps the snap correct. */
private val BLOCK_VPAD = 8.dp

/** Gap between a code block's language header and its body. Part of a code segment's top inset. */
private val CODE_HEADER_GAP = 4.dp

/**
 * A padded block quote or code block. Renders identically on every surface (feed, channel,
 * comments, full post).
 *
 *  * **Quote** — accent bar + `primary @ 10%` tint, a quote-mark glyph in the top-right
 *    corner that marks it as a quote, body at full readability.
 *  * **Code** — `surfaceContainerHigh` box, monospace body, optional language header.
 *
 * The box hugs its content width rather than filling the row, so a short quote reads as a
 * pulled-in block instead of a full-width band.
 *
 * An explicitly-collapsible quote always previews at [COLLAPSED_QUOTE_LINES] (feed AND detail) —
 * collapsing shrinks, so it never fights the outer clip. [interactive] decides only whether a
 * block carries its own chevron and whether non-collapsible blocks clamp:
 *  * **false** — a frozen preview inside a collapsed [ClampedPost]. WHEN the card can open the
 *    post (auth feed / channel), a collapsible quote shows its short preview with a PASSIVE chevron
 *    cue (it doesn't toggle — the card tap reveals the rest); in guest mode, where there's no
 *    detail screen, it stays full instead so it can't end up unreachable. Every other block renders
 *    at FULL length and the enclosing height clip does the cutting (so a long quote contributes its
 *    real height to the post-level overflow check and nothing is left unreachable). A per-block
 *    inline EXPAND is withheld here because it would just grow content the outer clip immediately
 *    hides.
 *  * **true** — the post is fully shown (expanded inline, or a full-reading surface). A
 *    collapsible quote previews at [COLLAPSED_QUOTE_LINES] WITH a chevron; every other block shows
 *    whole. The chevron appears once the body overflows and toggles both ways.
 */
@Composable
private fun BlockBox(
    text: FormattedText,
    style: TextStyle,
    blockStyle: FormattedText.Style,
    interactive: Boolean,
) {
    val isCode = blockStyle is FormattedText.Style.Pre
    val expandable = (blockStyle as? FormattedText.Style.BlockQuote)?.expandable == true
    val language = (blockStyle as? FormattedText.Style.Pre)?.language

    var expanded by remember(text) { mutableStateOf(false) }
    var canExpand by remember(text) { mutableStateOf(false) }
    // Tapping a clamped feed card opens the post (auth feed / channel set this); in guest mode it's
    // null — there's no detail screen, so a collapsed quote on a short guest post would be
    // unreachable. Only collapse a non-interactive quote when this escape hatch exists.
    val canOpenPost = LocalShowFullPost.current != null
    // An explicitly-collapsible quote previews at a few lines on EVERY surface that can reveal the
    // rest — feed included. Collapsing is the SHRINK direction, so unlike a per-block EXPAND it
    // never fights the outer [ClampedPost] clip (the reason other blocks stay frozen-full in a
    // clamped preview): in the feed `interactive` is false so there's no chevron and `expanded`
    // can't flip, leaving the quote pinned at [COLLAPSED_QUOTE_LINES]; tapping the card opens the
    // post, where the chevron lives. Other blocks render full (the post-level clip does the
    // cutting in the feed); a guest-mode quote with no post to open also stays full and waits for
    // the post-level "Показати більше" to expand it inline.
    val effectiveMax = when {
        expanded -> Int.MAX_VALUE
        expandable && (interactive || canOpenPost) -> COLLAPSED_QUOTE_LINES
        else -> Int.MAX_VALUE
    }
    // Chevron shows on a collapsible quote whose body actually overflows. It TOGGLES inline only
    // on an interactive surface; in a clamped feed preview it's a PASSIVE "there's more" cue (no
    // click of its own — the card tap opens the post), so it never grows content the outer clip
    // would hide nor fights the card's tap / long-press gesture.
    val chevronToggles = interactive
    val showChevron = canExpand && (interactive || canOpenPost)
    val expandLabel = stringResource(if (expanded) R.string.post_show_less else R.string.post_show_more)

    val accent = MaterialTheme.colorScheme.primary
    val boxBg = if (isCode) MaterialTheme.colorScheme.surfaceContainerHigh else accent.copy(alpha = 0.10f)
    val contentStyle = if (isCode) style.copy(fontFamily = FontFamily.Monospace) else style
    val rt = rememberRenderableText(text)
    // Right gutter clears the corner affordances: a quote always carries the top-right
    // quote glyph; the expand chevron shares that strip on the bottom-right. Code has no
    // quote glyph, so it only reserves the gutter when the chevron is present.
    val endPad = when {
        !isCode -> 26.dp
        showChevron -> 26.dp
        else -> 12.dp
    }

    val body: @Composable () -> Unit = {
        LinkAwareText(
            renderable = rt,
            style = contentStyle,
            maxLines = effectiveMax,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                // Detect overflow on every surface (feed preview included) so the "there's more"
                // chevron can show on a clamped feed card, not only on interactive surfaces.
                if (!expanded && layout.hasVisualOverflow) canExpand = true
            },
        )
    }

    // The accent bar is painted with drawBehind (full box height) rather than a
    // fillMaxHeight child under IntrinsicSize.Min: with the box now sized to its content,
    // pairing IntrinsicSize.Min height with content-driven width forced a double intrinsic
    // measure that flickered the box on every collapse/expand toggle. drawBehind sizes off
    // the laid-out height directly, so there's nothing to re-measure.
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(boxBg)
            .then(
                if (isCode) {
                    Modifier
                } else {
                    Modifier.drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                },
            )
            .then(
                if (showChevron && chevronToggles) {
                    Modifier.clickable(role = Role.Button, onClickLabel = expandLabel) { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        if (isCode) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = endPad, top = BLOCK_VPAD, bottom = BLOCK_VPAD),
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(CODE_HEADER_GAP))
                }
                body()
            }
        } else {
            // Content inset past the drawn 3.dp bar (start = 13) so text never touches it.
            Box(modifier = Modifier.padding(start = 13.dp, end = endPad, top = BLOCK_VPAD, bottom = BLOCK_VPAD)) {
                body()
            }
            // Quote marker — a faint quote-mark glyph in the top-right corner so the block
            // reads as a quote even before the reader notices the accent bar.
            Symbol(
                name = "format_quote",
                tint = accent.copy(alpha = 0.55f),
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 8.dp),
            )
        }
        if (showChevron) {
            // Chevron: down ("›" rotated 90°) when collapsed = "expand", up (270°) when
            // expanded = "collapse". Reuses the bundled `chevron_right` drawable. In a feed preview
            // it stays the collapsed (down) form as a passive "there's more" cue — the card tap,
            // not this glyph, opens the post.
            Symbol(
                name = "chevron_right",
                tint = (if (isCode) MaterialTheme.colorScheme.onSurfaceVariant else accent).copy(alpha = 0.7f),
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 8.dp)
                    .rotate(if (expanded) 270f else 90f),
            )
        }
    }
}

/** A piece of the body: plain text ([block] == null) or a [block] quote / code run. */
private data class Segment(val text: FormattedText, val block: FormattedText.Style?)

private data class BlockRange(val start: Int, val end: Int, val style: FormattedText.Style)

/**
 * Top-level block ranges (quotes / code), de-overlapped greedily outer-first. A block
 * nested inside another (rare) is dropped here and renders inline within its parent box.
 */
private fun FormattedText.blockRanges(): List<BlockRange> {
    val raw = spans
        .mapNotNull { sp ->
            val isBlock = sp.style is FormattedText.Style.BlockQuote || sp.style is FormattedText.Style.Pre
            if (!isBlock) return@mapNotNull null
            val st = sp.start.coerceIn(0, text.length)
            val en = sp.end.coerceIn(st, text.length)
            if (st >= en) null else BlockRange(st, en, sp.style)
        }
        .sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
    if (raw.isEmpty()) return emptyList()

    val out = mutableListOf<BlockRange>()
    var lastEnd = -1
    for (b in raw) {
        if (b.start >= lastEnd) {
            out += b
            lastEnd = b.end
        }
    }
    return out.sortedBy { it.start }
}

/**
 * Slice [source] into alternating plain-text / block pieces. Each piece is a
 * [FormattedText] whose spans are re-anchored to the slice, with its blank edges trimmed
 * — so a block box carries no empty leading / trailing line inside, and plain runs between
 * blocks shed the `\n\n` the source injects around them. Empty pieces are dropped.
 */
private fun buildSegments(source: FormattedText, blocks: List<BlockRange>): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = 0
    fun addPlain(start: Int, end: Int) {
        if (start >= end) return
        val seg = source.slice(start, end).trimmedBlankEdges()
        if (seg.text.isNotEmpty()) out += Segment(seg, block = null)
    }
    for (b in blocks) {
        addPlain(cursor, b.start)
        val blockSeg = source.slice(b.start, b.end).trimmedBlankEdges()
        if (blockSeg.text.isNotEmpty()) out += Segment(blockSeg, block = b.style)
        cursor = b.end
    }
    addPlain(cursor, source.text.length)
    return out
}

/** Drop leading / trailing whitespace (spaces, tabs, newlines) and re-anchor every span. */
private fun FormattedText.trimmedBlankEdges(): FormattedText {
    if (text.isEmpty()) return this
    var s = 0
    var e = text.length
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    if (s == 0 && e == text.length) return this
    if (s >= e) return FormattedText.Empty
    val sub = text.substring(s, e)
    val newSpans = spans.mapNotNull { sp ->
        val ns = (sp.start - s).coerceAtLeast(0)
        val ne = (sp.end - s).coerceAtMost(e - s)
        if (ne <= ns) null else FormattedText.Span(ns, ne, sp.style)
    }
    return FormattedText(sub, newSpans)
}

/**
 * Substring preserving overlapping spans (clipped + re-anchored). The block-type wrapper
 * span covering the WHOLE slice is dropped — [BlockBox] provides that styling — while
 * inner spans (including a nested block, which then renders inline) are kept.
 */
private fun FormattedText.slice(start: Int, end: Int): FormattedText {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    if (s == e) return FormattedText.Empty
    val slicedText = text.substring(s, e)
    val sliceLen = e - s
    val slicedSpans = spans.mapNotNull { span ->
        val newStart = (span.start - s).coerceAtLeast(0)
        val newEnd = (span.end - s).coerceAtMost(sliceLen)
        if (newEnd <= newStart) return@mapNotNull null
        val isBlock = span.style is FormattedText.Style.BlockQuote || span.style is FormattedText.Style.Pre
        if (isBlock && newStart == 0 && newEnd == sliceLen) return@mapNotNull null
        FormattedText.Span(newStart, newEnd, span.style)
    }
    return FormattedText(slicedText, slicedSpans)
}
