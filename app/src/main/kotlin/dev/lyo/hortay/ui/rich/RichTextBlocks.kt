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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichListItem
import dev.lyo.hortay.ui.icons.Symbol
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
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (readingColumn) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockSpacingBetween(blocks[index - 1], block)))
            if (readingColumn) {
                val blockModifier = if (block.isEdgeToEdge()) {
                    Modifier.readingBleed()
                } else {
                    Modifier.widthIn(max = READING_MAX_WIDTH).fillMaxWidth()
                }
                Box(blockModifier) { RichBlockContent(block, path = "$path.$index", quoteDepth = quoteDepth) }
            } else {
                RichBlockContent(block, path = "$path.$index", quoteDepth = quoteDepth)
            }
        }
    }
}

/** Max text-column measure on wide layouts (tablet / foldable / landscape); on phones the column
 *  is narrower than this so it just fills the width. */
private val READING_MAX_WIDTH = 700.dp

/** Horizontal inset the host post card applies to the rich body; media / tables in reading mode
 *  cancel it via [readingBleed] to reach the card edge. Keep in sync with PostCard's content
 *  padding. */
private val READING_EDGE_BLEED = 16.dp

/** Media / table blocks read as full-bleed figures in the reading surface. Quotes and details
 *  stay inside the text column. */
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
 * Expands a block by [READING_EDGE_BLEED] on each horizontal side and shifts it left by the same,
 * so it bleeds past the host card's content inset to the card edge while still REPORTING the
 * un-expanded width to its parent [Column] (the centred column layout is undisturbed). A no-op
 * when the incoming width is unbounded (nothing to bleed into).
 */
private fun Modifier.readingBleed(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val insetPx = READING_EDGE_BLEED.roundToPx()
    val expanded = constraints.maxWidth + insetPx * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = expanded),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-insetPx, 0) }
}

@Composable
private fun RichBlockContent(block: RichBlock, path: String, quoteDepth: Int = 0) {
    when (block) {
        is RichBlock.SectionHeading -> RichInlineText(
            inline = block.text,
            style = richHeadingStyle(block.size),
        )
        is RichBlock.Paragraph -> RichInlineText(block.text, RichType.paragraph)
        is RichBlock.Footer -> RichInlineText(
            block.text,
            RichType.footer.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        is RichBlock.Unknown -> RichInlineText(RichInline.Plain(block.plainText), RichType.paragraph)

        is RichBlock.Preformatted -> RichCodeBox(block.text, block.language)
        is RichBlock.Math -> RichCodeBox(RichInline.Plain(block.expression), language = "")

        RichBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is RichBlock.Anchor -> Unit // invisible scroll target — renders nothing

        is RichBlock.ListBlock -> RichList(block.items, path, quoteDepth)

        is RichBlock.BlockQuote -> RichBlockQuote(credit = block.credit, depth = quoteDepth) {
            RichBlocks(block.blocks, path = "$path.q", quoteDepth = quoteDepth + 1)
        }
        is RichBlock.PullQuote -> RichPullQuote(text = block.text, credit = block.credit)

        is RichBlock.Details -> RichDetails(block, path, quoteDepth)

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
 * `surfaceContainerHigh` box with monospace body and an optional language label — the same
 * visual idiom as the [dev.lyo.hortay.ui.text] code block. Content is pinned LTR even inside
 * an RTL document (source lines read left-to-right).
 */
@Composable
private fun RichCodeBox(text: RichInline, language: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RichInlineText(
                    inline = text,
                    style = RichType.code,
                )
            }
        }
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
            RichType.h4.copy(textAlign = TextAlign.Center),
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
        style = RichType.footer.copy(
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        ),
    )
}

// ---- Lists ----

private val LIST_MARKER_WIDTH = 28.dp

@Composable
private fun RichList(items: List<RichListItem>, path: String, quoteDepth: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                if (item.hasCheckbox) {
                    Symbol(
                        name = if (item.isChecked) "check_box" else "check_box_outline_blank",
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Read-only checked state for screen readers (the checklist mirrors the
                        // source post; Hortay never toggles it), so no onClick / toggleable action.
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .semantics { toggleableState = ToggleableState(item.isChecked) },
                    )
                } else {
                    Text(
                        text = listMarker(item),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(LIST_MARKER_WIDTH),
                    )
                }
                RichBlocks(
                    item.blocks,
                    path = "$path.$index",
                    modifier = Modifier.weight(1f),
                    quoteDepth = quoteDepth,
                )
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
private fun RichDetails(block: RichBlock.Details, path: String, quoteDepth: Int = 0) {
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
                    RichBlocks(block.blocks, path = "$path.d", quoteDepth = quoteDepth)
                }
            }
        }
    }
}
