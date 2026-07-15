package dev.lyo.hortay.ui.rich

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichListItem
import dev.lyo.hortay.ui.icons.Symbol

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
 */
@Composable
internal fun RichBlocks(
    blocks: List<RichBlock>,
    path: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockSpacingBetween(blocks[index - 1], block)))
            RichBlockContent(block, path = "$path.$index")
        }
    }
}

@Composable
private fun RichBlockContent(block: RichBlock, path: String) {
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

        is RichBlock.ListBlock -> RichList(block.items, path)

        is RichBlock.BlockQuote -> RichQuoteBox(credit = block.credit, pull = false) {
            RichBlocks(block.blocks, path = "$path.q")
        }
        is RichBlock.PullQuote -> RichQuoteBox(credit = block.credit, pull = true) {
            RichInlineText(
                block.text,
                RichType.paragraph.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is RichBlock.Details -> RichDetails(block, path)

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

private val QUOTE_BAR = 3.dp

/**
 * Accent quote box shared by [RichBlock.BlockQuote] and [RichBlock.PullQuote] — a
 * `primary @ 10%` tint with a quote-mark glyph in the top-right corner. A block quote also
 * draws the left accent bar and insets its content past it; a pull quote drops the bar and
 * centres its (caller-supplied) text.
 */
@Composable
private fun RichQuoteBox(
    credit: RichInline?,
    pull: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.10f))
            .then(
                if (pull) Modifier else Modifier.drawBehind { drawRect(accent, size = Size(QUOTE_BAR.toPx(), size.height)) },
            ),
    ) {
        Column(
            modifier = Modifier.padding(
                start = if (pull) 16.dp else 13.dp,
                end = 26.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = if (pull) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            content()
            if (credit != null) {
                RichInlineText(
                    inline = credit,
                    style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        Symbol(
            name = "format_quote",
            tint = accent.copy(alpha = 0.55f),
            size = 16.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
        )
    }
}

// ---- Lists ----

private val LIST_MARKER_WIDTH = 28.dp

@Composable
private fun RichList(items: List<RichListItem>, path: String) {
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
                RichBlocks(item.blocks, path = "$path.$index", modifier = Modifier.weight(1f))
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

@Composable
private fun RichDetails(block: RichBlock.Details, path: String) {
    // Position-keyed (NOT rememberSaveable): cold launch collapses to the model's isOpen.
    var open by remember(path) { mutableStateOf(block.isOpen) }
    val chevron by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "rich-details-chevron",
    )
    val toggleLabel = stringResource(if (open) R.string.rich_details_collapse else R.string.rich_details_expand)
    val toggle = { open = !open }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = toggle)
                // Expand / collapse action carries the open state to TalkBack (announced with
                // its own localized "expanded" / "collapsed"), which a bare clickable can't.
                .semantics {
                    if (open) collapse { toggle(); true } else expand { toggle(); true }
                }
                .padding(vertical = 4.dp),
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
        val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(sizeSpec) + fadeIn(fadeSpec),
            exit = shrinkVertically(sizeSpec) + fadeOut(fadeSpec),
        ) {
            RichBlocks(block.blocks, path = "$path.d", modifier = Modifier.padding(top = RICH_BLOCK_GAP))
        }
    }
}
