package dev.lyo.hortay.ui.rich

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichListItem
import dev.lyo.hortay.data.rich.RichPlainText
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.text.CODE_COLLAPSED_LINES
import dev.lyo.hortay.ui.text.CodeBlock
import dev.lyo.hortay.ui.text.EditorialQuoteFrame
import dev.lyo.hortay.ui.util.rememberReducedMotion

/**
 * Renders a list of [RichBlock]s stacked in a plain [Column] — NEVER a nested LazyColumn,
 * so the whole rich body stays one item of the outer feed list. [path] is the block's
 * position path within the document (e.g. `"0.2"`), used only as a stable `remember` key
 * for collapsible [RichBlock.Details] state (position indices, not content).
 *
 * Sibling gaps are asymmetric ([blockSpacingBetween]) rather than a uniform arrangement, so a
 * heading binds tightly to the block it introduces and opens with air above — the editorial
 * rhythm that makes the document read as one article. The gap is inserted only BETWEEN blocks,
 * so the first / last block carries no outer padding.
 *
 * [readingColumn] applies the editorial reading layout at the TOP level of the document only
 * (nested recursion — list items, quote / details bodies — always passes `false`, so the policy
 * is one concept applied once): text-ish blocks are held to a [READING_MAX_WIDTH] measure
 * centred in the column, while media and tables break out edge-to-edge ([readingBleed]) past the
 * host card's horizontal inset. In the feed preview it stays `false` — that layout is unchanged.
 */
@Composable
internal fun RichBlocks(
    blocks: List<RichBlock>,
    path: String,
    modifier: Modifier = Modifier,
    readingColumn: Boolean = false,
    quoteDepth: Int = 0,
    listDepth: Int = 0,
) {
    // In-document anchor navigation (Reading only): the controller carries a per-target-block
    // BringIntoViewRequester and the transient "which block is flashing" state. Nested (non-reading)
    // RichBlocks ignore it — targets are always top-level, so only the top-level column attaches.
    val anchorController = if (readingColumn) LocalRichAnchorController.current else null
    val bleedInset = if (readingColumn) LocalRichBleedInset.current else RichBleedInset(0.dp, 0.dp)
    Column(
        modifier = modifier,
        horizontalAlignment = if (readingColumn) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockSpacingBetween(blocks[index - 1], block)))
            if (readingColumn) {
                val bleed = if (block.isEdgeToEdge()) {
                    Modifier.readingBleed(bleedInset.left, bleedInset.right)
                } else {
                    Modifier.widthIn(max = READING_MAX_WIDTH).fillMaxWidth()
                }
                val requester = anchorController?.requesterFor(index)
                val blockModifier = if (requester != null) bleed.bringIntoViewRequester(requester) else bleed
                Box(blockModifier) {
                    RichBlockContent(block, path = "$path.$index", quoteDepth = quoteDepth, listDepth = listDepth)
                    if (anchorController != null) {
                        RichAnchorHighlight(active = anchorController.highlightedIndex == index)
                    }
                }
            } else {
                RichBlockContent(block, path = "$path.$index", quoteDepth = quoteDepth, listDepth = listDepth)
            }
        }
    }
}

/** Corner radius on the anchor-landing highlight wash. */
private val ANCHOR_HIGHLIGHT_SHAPE = RoundedCornerShape(10.dp)

/**
 * Soft accent wash over a top-level block an in-document anchor jump just landed on. Fades in and
 * out on the (short) effects spec so the flash reads as a gentle pulse; under reduced motion it
 * snaps in and out (still a clear "you are here" acknowledgement, but with no animated cross-fade —
 * the accessible choice, since the highlight is feedback, not decoration, and must not be dropped).
 */
@Composable
private fun BoxScope.RichAnchorHighlight(active: Boolean) {
    val reduced = rememberReducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "rich-anchor-highlight",
    )
    if (alpha <= 0f) return
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * alpha)
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(ANCHOR_HIGHLIGHT_SHAPE)
            .background(color),
    )
}

/** Max text-column measure on wide layouts (tablet / foldable / landscape); on phones the column
 *  is narrower than this so it just fills the width. */
private val READING_MAX_WIDTH = 700.dp

/** Fallback bleed when no host declares [LocalRichBleedInset] — the pre-batch-7 behaviour (cancel
 *  only the card's own content padding). Real reading hosts override it with their true distance
 *  to the window edge. */
private val READING_EDGE_BLEED = 16.dp

/**
 * The distance from the rich body's own edge to the surface edge it should bleed to (the host
 * card's edge, which on a phone IS the window edge). Media and tables in reading mode cancel it via
 * [readingBleed] to run truly edge-to-edge instead of stopping at the text column.
 *
 * [left] / [right] are PHYSICAL (not start/end) on purpose: the bleed runs inside an RTL document's
 * forced [LayoutDirection.Rtl], but the host card's avatar column follows the DEVICE direction, so
 * the two directions can disagree. The host resolves its avatar-side vs trailing insets against its
 * OWN layout direction and hands over physical values, and [readingBleed] applies them verbatim.
 *
 * The HOST declares it (see PostCard, expanded/reading mode) so the rich layer carries no
 * per-screen constant — a host with different chrome (a future capped reading column, a comment
 * bubble) provides its own numbers and the bleed follows, with no change here. The default is the
 * legacy symmetric [READING_EDGE_BLEED] so an undeclared host keeps its old inset.
 */
@Immutable
internal data class RichBleedInset(val left: Dp, val right: Dp)

internal val LocalRichBleedInset = staticCompositionLocalOf { RichBleedInset(READING_EDGE_BLEED, READING_EDGE_BLEED) }

/**
 * Visual-figure blocks that read as full-bleed in the reading surface. Quotes, details, and the
 * inline Audio / VoiceNote player rows deliberately stay in the text column — a player row is a
 * text-column citizen (controls + waveform sized to the reading measure), not a figure, so bleeding
 * it would just stretch a control strip across the screen. (A Table is "edge-to-edge" only in that
 * its scroll viewport spans the width; its content is re-inset at rest — see RichTableFull.)
 */
private fun RichBlock.isEdgeToEdge(): Boolean = when (this) {
    is RichBlock.Photo,
    is RichBlock.Video,
    is RichBlock.Animation,
    is RichBlock.Collage,
    is RichBlock.Slideshow,
    is RichBlock.MapPreview,
    is RichBlock.Table,
    -> true
    else -> false
}

/**
 * Expands a block by [left] on its physical left and [right] on its physical right, and shifts it
 * so it bleeds past the host's content inset to the surface edge (the card edge = the window edge on
 * a phone) while still REPORTING the un-expanded width to its parent [Column] (the centred column
 * layout is undisturbed, and the parent's measured width doesn't change so there is no feedback
 * loop). Physical (not start/end) because the host resolves its avatar-side inset against the DEVICE
 * direction while this runs under the document's forced direction — see [RichBleedInset]. A no-op
 * when the incoming width is unbounded (nothing to bleed into).
 */
private fun Modifier.readingBleed(left: Dp, right: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val leftPx = left.roundToPx()
    val rightPx = right.roundToPx()
    val expanded = constraints.maxWidth + leftPx + rightPx
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = expanded),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-leftPx, 0) }
}

@Composable
private fun RichBlockContent(block: RichBlock, path: String, quoteDepth: Int = 0, listDepth: Int = 0) {
    when (block) {
        is RichBlock.SectionHeading -> RichInlineText(
            inline = block.text,
            style = richHeadingStyle(block.size),
        )
        is RichBlock.Paragraph -> RichInlineText(block.text, RichTypography.paragraph)
        is RichBlock.Footer -> RichInlineText(
            block.text,
            RichTypography.footer.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        is RichBlock.Unknown -> RichInlineText(RichInline.Plain(block.plainText), RichTypography.paragraph)

        is RichBlock.Preformatted -> RichCodeBox(block.text, block.language)
        is RichBlock.Math -> RichMathBlock(block.expression)

        RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is RichBlock.Anchor -> Unit // invisible scroll target — renders nothing

        is RichBlock.ListBlock -> RichList(block.items, path, quoteDepth, listDepth)

        is RichBlock.BlockQuote -> RichBlockQuote(credit = block.credit, depth = quoteDepth) {
            RichBlocks(block.blocks, path = "$path.q", quoteDepth = quoteDepth + 1, listDepth = listDepth)
        }
        is RichBlock.PullQuote -> RichPullQuote(text = block.text, credit = block.credit)

        is RichBlock.Details -> RichDetails(block, path, quoteDepth, listDepth)

        is RichBlock.Photo -> RichPhoto(block)
        is RichBlock.Video -> RichVideo(block)
        is RichBlock.Animation -> RichAnimation(block)
        is RichBlock.Audio -> RichAudio(block)
        is RichBlock.VoiceNote -> RichVoiceNote(block)
        is RichBlock.Collage -> RichCollage(block)
        is RichBlock.Slideshow -> RichSlideshow(block)
        is RichBlock.MapPreview -> RichMapPreview(block)
        is RichBlock.Table -> RichTable(block)
    }
}

// ---- Code / math box ----

/**
 * Renders through the app-wide [CodeBlock] (shared with regular posts), which owns the neutral
 * container, language pill, copy button, horizontal scroll and long-block cap. The code text is
 * drawn here at [RichTypography.code] (tighter line spacing than body); [CodeBlock] pins it LTR and
 * scrolls long lines. The internal cap engages only on the reading surface — a feed preview is
 * clamped post-wide by `ClampedContent`.
 *
 * Also the parse-failure fallback for [RichMathBlock] — a malformed formula renders as its raw
 * monospace source in the same box a `pageBlockPreformatted` uses.
 */
@Composable
internal fun RichCodeBox(text: RichInline, language: String?) {
    CodeBlock(
        rawText = RichPlainText.of(text),
        language = language,
        codeStyle = RichTypography.code,
        collapsedLines = if (LocalRichReading.current) CODE_COLLAPSED_LINES else null,
    ) {
        RichInlineText(inline = text, style = RichTypography.code, softWrap = false)
    }
}

// ---- Quotes ----

private val PULL_QUOTE_RULE = 3.dp

/**
 * `pageBlockBlockQuote` — rendered through the app-wide [EditorialQuoteFrame] (shared with regular
 * posts), which owns the accent tint, rounded-cap bar, radius and body/credit arrangement.
 * [depth] rotates the accent through the tonal palette so a nested quote reads as a new layer (see
 * [dev.lyo.hortay.ui.text.quoteAccentRole]); an optional [credit] byline sits under the quote body.
 */
@Composable
private fun RichBlockQuote(
    credit: RichInline?,
    depth: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    EditorialQuoteFrame(
        modifier = Modifier.fillMaxWidth(),
        depth = depth,
        credit = credit?.let { { RichQuoteCredit(it) } },
        content = content,
    )
}

/**
 * `pageBlockPullQuote` — a centred editorial pull quote: no background box, larger text, a short
 * accent rule above it, and generous side margins so it breaks the reading rhythm rather than
 * sitting in a frame.
 */
@Composable
private fun RichPullQuote(text: RichInline, credit: RichInline?) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(PULL_QUOTE_RULE)
                .clip(RoundedCornerShape(PULL_QUOTE_RULE / 2))
                .background(accent),
        )
        RichInlineText(
            text,
            RichTypography.h4.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        RichQuoteCredit(credit)
    }
}

/** Shared credit / attribution byline for both quote variants — footer size, medium weight, and
 *  a touch more contrast than plain secondary text. */
@Composable
private fun RichQuoteCredit(credit: RichInline?) {
    if (credit == null) return
    RichInlineText(
        inline = credit,
        style = RichTypography.footer.copy(
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        ),
    )
}

// ---- Lists ----

/** Per-level indent for a nested list, capped at [LIST_MAX_INSET_LEVELS] so a runaway nest can't
 *  march its content off the right edge. */
private val LIST_NEST_INSET = 16.dp
private const val LIST_MAX_INSET_LEVELS = 4

/** Gap between the (right-aligned) ordinal marker and the item body. */
private val LIST_MARKER_GAP = 8.dp

/** Checked-item body dim — a soft "done" cue without a strikethrough (unless the source text
 *  itself carries one). */
private const val CHECKED_ITEM_ALPHA = 0.8f

@Composable
private fun RichList(items: List<RichListItem>, path: String, quoteDepth: Int = 0, listDepth: Int = 0) {
    val markerStyle = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    // Ordinal markers only (a checklist item has a glyph box, not text). Measuring the WIDEST marker
    // sizes the column dynamically, so "viii." / "MMM." never clip a 28 dp slot and every item body
    // shares one left edge regardless of ordinal width.
    val markers = remember(items) { items.map { if (it.hasCheckbox) null else listMarker(it) } }
    val markerWidthPx = remember(markers, markerStyle) {
        markers.filterNotNull().maxOfOrNull { measurer.measure(it, markerStyle).size.width } ?: 0
    }
    val markerWidth = with(LocalDensity.current) { markerWidthPx.toDp() }
    val inset = LIST_NEST_INSET * minOf(listDepth, LIST_MAX_INSET_LEVELS)

    Column(
        modifier = if (inset > 0.dp) Modifier.padding(start = inset) else Modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                if (item.hasCheckbox) {
                    RichChecklistBox(
                        checked = item.isChecked,
                        // Read-only checked state for screen readers (the checklist mirrors the
                        // source post; Hortay never toggles it), so no onClick / toggleable action.
                        modifier = Modifier
                            .padding(top = 1.dp, end = 8.dp)
                            .semantics { toggleableState = ToggleableState(item.isChecked) },
                    )
                } else {
                    // Right-aligned in its measured column + baseline-aligned to the body's first
                    // line, so ordinals line up on their trailing dot and sit on the text baseline.
                    Text(
                        text = markers[index].orEmpty(),
                        style = markerStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(markerWidth).alignByBaseline(),
                    )
                    Spacer(Modifier.width(LIST_MARKER_GAP))
                }
                val bodyDim = item.hasCheckbox && item.isChecked
                RichBlocks(
                    item.blocks,
                    path = "$path.$index",
                    modifier = Modifier
                        .weight(1f)
                        .then(if (item.hasCheckbox) Modifier else Modifier.alignByBaseline())
                        .then(if (bodyDim) Modifier.alpha(CHECKED_ITEM_ALPHA) else Modifier),
                    quoteDepth = quoteDepth,
                    listDepth = listDepth + 1,
                )
            }
        }
    }
}

private val CHECKBOX_SIZE = 18.dp
private val CHECKBOX_SHAPE = RoundedCornerShape(5.dp)

/**
 * Read-only Telegram-style checklist glyph — a small rounded square that fills with the accent and
 * shows a hand-drawn check when done, an outlined box when not. Deliberately NOT a Material
 * `Checkbox` (that reads as an interactive form control with its own ripple / min-touch size); the
 * source post's checklist is display-only here.
 */
@Composable
private fun RichChecklistBox(checked: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(CHECKBOX_SIZE)
            .clip(CHECKBOX_SHAPE)
            .then(if (checked) Modifier.background(accent) else Modifier.border(1.5.dp, outline, CHECKBOX_SHAPE)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(CHECKBOX_SIZE * 0.62f)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = size.minDimension * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val path = Path().apply {
                    moveTo(0.14f * w, 0.55f * h)
                    lineTo(0.42f * w, 0.82f * h)
                    lineTo(0.86f * w, 0.22f * h)
                }
                drawPath(path, color = onAccent, style = stroke)
            }
        }
    }
}

/** Marker text for an ordered/unordered/checkbox list item. Prefers TDLib's pre-rendered
 *  [RichListItem.label]; otherwise derives one from `type` + `value`. */
private fun listMarker(item: RichListItem): String {
    item.label.takeIf { it.isNotBlank() }?.let { return it }
    return when (item.type) {
        "1" -> "${item.value}."
        "a" -> "${alpha(item.value, upper = false)}."
        "A" -> "${alpha(item.value, upper = true)}."
        "i" -> "${roman(item.value).lowercase()}."
        "I" -> "${roman(item.value)}."
        else -> "•"
    }
}

/** Bijective base-26: 1 -> a, 26 -> z, 27 -> aa. */
private fun alpha(value: Int, upper: Boolean): String {
    if (value <= 0) return value.toString()
    val sb = StringBuilder()
    var n = value
    while (n > 0) {
        n--
        sb.append('a' + (n % 26))
        n /= 26
    }
    val s = sb.reverse().toString()
    return if (upper) s.uppercase() else s
}

private fun roman(value: Int): String {
    if (value <= 0) return value.toString()
    val symbols = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )
    val sb = StringBuilder()
    var n = value
    for ((magnitude, symbol) in symbols) {
        while (n >= magnitude) {
            sb.append(symbol)
            n -= magnitude
        }
    }
    return sb.toString()
}

// ---- Details (collapsible) ----

/**
 * External control seam for [RichBlock.Details] expansion. Every rendered [RichDetails] observes
 * this holder (keyed by the block's document [path]) and forces itself open when its path is
 * requested. It is the hook a later batch uses to auto-open a COLLAPSED details section that an
 * in-document anchor tap lands inside — the anchor logic lives elsewhere; this only exposes the
 * "request expand" verb. The [LocalRichDetailsExpansion] default is `null`, so a document with no
 * controller keeps the plain cold-launch behaviour (open follows the model's `isOpen`).
 */
@Stable
internal class RichDetailsExpansion {
    private val requested = mutableStateSetOf<String>()

    /** Request that the details block at [path] be expanded — idempotent and sticky across
     *  recomposition. */
    fun requestExpand(path: String) {
        requested.add(path)
    }

    /** True once [requestExpand] was called for [path]. Read from composition so the matching
     *  [RichDetails] recomposes into the open state when its path becomes requested. */
    fun isExpandRequested(path: String): Boolean = path in requested
}

internal val LocalRichDetailsExpansion = staticCompositionLocalOf<RichDetailsExpansion?> { null }

private val DETAILS_CONTAINER_SHAPE = RoundedCornerShape(12.dp)
private val DETAILS_NESTING_LINE = 1.5.dp

@Composable
private fun RichDetails(block: RichBlock.Details, path: String, quoteDepth: Int = 0, listDepth: Int = 0) {
    // Position-keyed (NOT rememberSaveable): cold launch collapses to the model's isOpen.
    var open by remember(path) { mutableStateOf(block.isOpen) }
    // External auto-open seam: when the controller marks this path requested, force it open (the
    // user can still collapse it afterwards — the effect only re-fires if the request flips).
    val requestedOpen = LocalRichDetailsExpansion.current?.isExpandRequested(path) == true
    LaunchedEffect(requestedOpen) { if (requestedOpen) open = true }

    val reduced = rememberReducedMotion()
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = if (reduced) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "rich-details-chevron",
    )
    val toggleLabel = stringResource(if (open) R.string.rich_details_collapse else R.string.rich_details_expand)
    val toggle = { open = !open }
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    // Neutral container surfaces ONLY while expanded; a collapsed details is a plain row.
    Column(
        modifier = if (open) {
            Modifier.clip(DETAILS_CONTAINER_SHAPE).background(MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            Modifier
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DETAILS_CONTAINER_SHAPE)
                .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = toggle)
                // Expand / collapse action carries the open state to TalkBack (announced with
                // its own localized "expanded" / "collapsed"), which a bare clickable can't.
                .semantics {
                    if (open) collapse { toggle(); true } else expand { toggle(); true }
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = if (open) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                RichInlineText(block.header, MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(8.dp))
            Symbol(
                name = "chevron_right",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron),
            )
        }
        val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
        // Content fades on the (shorter) effects spec while the height opens on the spatial spec,
        // so text resolves a touch ahead of the section unfolding; reduced motion swaps instantly.
        val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) EnterTransition.None else expandVertically(sizeSpec) + fadeIn(fadeSpec),
            exit = if (reduced) ExitTransition.None else shrinkVertically(sizeSpec) + fadeOut(fadeSpec),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            ) {
                Box(
                    // Thin nesting line down the content's start edge, content inset past it.
                    modifier = Modifier
                        .drawBehind { drawRect(color = lineColor, size = Size(DETAILS_NESTING_LINE.toPx(), size.height)) }
                        .padding(start = 16.dp),
                ) {
                    RichBlocks(block.blocks, path = "$path.d", quoteDepth = quoteDepth, listDepth = listDepth)
                }
            }
        }
    }
}
