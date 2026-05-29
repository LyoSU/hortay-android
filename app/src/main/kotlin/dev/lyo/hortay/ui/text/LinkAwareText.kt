@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.ui.media.SpoilerField
import dev.lyo.hortay.ui.media.TEXT_DENSITY_PX_PER_DOT
import dev.lyo.hortay.ui.media.drawSpoilerShimmer
import dev.lyo.hortay.ui.media.rememberSpoilerDrift

// Block-decoration geometry. Calibrated to line up with the sp text indents in
// FormattedTextRenderer (QUOTE_TEXT_INDENT / CODE_TEXT_INDENT) at the default font scale.
private val BLOCK_CORNER = 8.dp
private val QUOTE_BAR_WIDTH = 3.dp
private val BLOCK_VPAD = 6.dp
private val CODE_LABEL_FONT = 11.sp
private val CODE_LABEL_PAD = 6.dp
private val CODE_LABEL_CORNER = 4.dp

// Pressed-link highlight: a rounded fill behind the pressed link, inflated past the
// glyphs so it reads as a padded pill rather than a tight background.
private val LINK_HIGHLIGHT_PAD = 3.dp
private val LINK_HIGHLIGHT_CORNER = 5.dp

/**
 * Drop-in [Text] replacement with link-tap handling, long-press, spoiler dot-cloud overlay,
 * and paragraph-level block decorations (block quotes, code blocks).
 *
 * Block decorations ([RenderableText.blockDecorations]) are painted on a single backing
 * [Text] — the body never splits into separate composables, so the `maxLines` clamp and
 * the "Показати більше" toggle keep working, and every surface (feed / channel / comments /
 * detail) renders the block identically. Backgrounds are drawn with [Modifier.drawBehind]
 * (behind the glyphs); the code-block language label and the spoiler shimmer are drawn on
 * top via [Modifier.drawWithContent]. All geometry is read from the captured
 * [TextLayoutResult], so a quote/code block clipped away by `maxLines` simply isn't drawn.
 *
 * Spoiler model: see [RenderableText]. Each [SpoilerGroupInfo] is painted as ONE shimmer
 * over the union of its sub-range paths so a single logical spoiler reveals as one cover.
 */
@Composable
fun LinkAwareText(
    renderable: RenderableText,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    // Key on [renderable.contentKey] (source text identity) rather than `renderable`
    // or its `text` AnnotatedString: RenderableText carries a `spoilerDispersion`
    // lambda whose identity flips per-recomposition, and `text` itself mutates when
    // a spoiler is revealed (Transparent → onSurface). contentKey is stable across
    // both, so long-press sheet and captured TextLayout survive reactions / edits /
    // expand-toggles / spoiler reveals.
    var layoutResult by remember(renderable.contentKey) { mutableStateOf<TextLayoutResult?>(null) }
    var pressedLink by remember(renderable.contentKey) { mutableStateOf<String?>(null) }
    var pressedRange by remember(renderable.contentKey) { mutableStateOf<IntRange?>(null) }

    val linkMod = if (renderable.linkRanges.isNotEmpty()) {
        Modifier.linkLongPress(
            linkRanges = renderable.linkRanges,
            layoutResult = layoutResult,
            onLongPress = { range -> pressedLink = range.url },
        )
    } else Modifier

    // ---- Pressed-link highlight (all tappable entity kinds) ----
    val pressableRanges = renderable.pressableRanges
    val linkHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val pressMod = if (pressableRanges.isNotEmpty()) {
        Modifier.linkPressHighlight(
            ranges = pressableRanges,
            layoutResult = layoutResult,
            onPressedRangeChange = { pressedRange = it },
        )
    } else Modifier
    val highlightMod = if (pressableRanges.isNotEmpty()) {
        Modifier.drawBehind {
            val layout = layoutResult ?: return@drawBehind
            val range = pressedRange ?: return@drawBehind
            val chars = layout.layoutInput.text
            val len = chars.length
            if (len == 0) return@drawBehind
            // Trim whitespace off the entity edges so the highlight starts/ends exactly
            // where the visible glyphs do, not on a leading/trailing space.
            var startOff = range.first.coerceIn(0, len)
            var endOff = (range.last + 1).coerceIn(startOff, len)
            while (startOff < endOff && chars[startOff].isWhitespace()) startOff++
            while (endOff > startOff && chars[endOff - 1].isWhitespace()) endOff--
            if (endOff <= startOff) return@drawBehind
            val firstLine = layout.getLineForOffset(startOff)
            val lastLine = layout.getLineForOffset(endOff - 1)
            val padH = LINK_HIGHLIGHT_PAD.toPx()
            val corner = CornerRadius(LINK_HIGHLIGHT_CORNER.toPx())
            for (line in firstLine..lastLine) {
                // Clip to THIS line's slice, bounded by the last VISIBLE glyph — never
                // getLineRight, which runs to the margin / a wrap space and reads as
                // highlighted empty space.
                val segStart = maxOf(startOff, layout.getLineStart(line))
                val segEnd = minOf(endOff, layout.getLineEnd(line, visibleEnd = true))
                if (segStart >= segEnd) continue
                val left = layout.getHorizontalPosition(segStart, usePrimaryDirection = true)
                val right = layout.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                val x = (minOf(left, right) - padH).coerceAtLeast(0f)
                val r = (maxOf(left, right) + padH).coerceAtMost(size.width)
                if (r <= x) continue
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                drawRoundRect(linkHighlight, Offset(x, top), Size(r - x, bottom - top), corner)
            }
        }
    } else Modifier

    // ---- Block decorations (quote bar + tint, code box, code language label) ----
    val blockDecorations = renderable.blockDecorations
    val quoteBar = MaterialTheme.colorScheme.primary
    val quoteTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val codeBoxBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeLabelChip = MaterialTheme.colorScheme.surfaceContainerHighest
    val textMeasurer = rememberTextMeasurer()
    val codeLabels = remember(blockDecorations) {
        blockDecorations.filter { it.kind == BlockDecoration.Kind.Code && !it.language.isNullOrBlank() }
    }

    val blockMod = if (blockDecorations.isNotEmpty()) {
        Modifier.drawBehind {
            val layout = layoutResult ?: return@drawBehind
            val len = layout.layoutInput.text.length
            if (len == 0 || layout.lineCount == 0) return@drawBehind
            // Last character actually laid out — anything past it was clipped by maxLines.
            val lastVisible = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
            val corner = CornerRadius(BLOCK_CORNER.toPx())
            val vpad = BLOCK_VPAD.toPx()
            for (deco in blockDecorations) {
                if (deco.start >= lastVisible) continue
                val s = deco.start.coerceIn(0, len - 1)
                val eIncl = (deco.end - 1).coerceIn(s, lastVisible - 1)
                val firstLine = layout.getLineForOffset(s)
                val lastLine = layout.getLineForOffset(eIncl)
                val top = (layout.getLineTop(firstLine) - vpad).coerceAtLeast(0f)
                val bottom = (layout.getLineBottom(lastLine) + vpad).coerceAtMost(size.height)
                if (bottom <= top) continue
                val boxSize = Size(size.width, bottom - top)
                when (deco.kind) {
                    BlockDecoration.Kind.Quote -> {
                        drawRoundRect(quoteTint, Offset(0f, top), boxSize, corner)
                        // Clip the bar to the box's rounded silhouette so its outer
                        // corners get the same chamfer (matches the old QuoteRow look).
                        val clip = Path().apply {
                            addRoundRect(RoundRect(Rect(0f, top, size.width, bottom), corner))
                        }
                        clipPath(clip) {
                            drawRect(quoteBar, Offset(0f, top), Size(QUOTE_BAR_WIDTH.toPx(), bottom - top))
                        }
                    }
                    BlockDecoration.Kind.Code -> {
                        drawRoundRect(codeBoxBg, Offset(0f, top), boxSize, corner)
                    }
                }
            }
        }
    } else Modifier

    // ---- Spoiler shimmer (see RenderableText doc) ----
    val spoilerGroups = renderable.spoilerGroups
    val spoilerDispersion = renderable.spoilerDispersion
    val spoilerColor = MaterialTheme.colorScheme.onSurface
    val spoilerDrift by rememberSpoilerDrift()

    // One particle field per group, reused across frames so steady-state shimmer allocates
    // nothing (see [SpoilerField]).
    val spoilerFields = remember(renderable.contentKey) { HashMap<Int, SpoilerField>() }

    // Merged clip path per group, rebuilt ONLY when the text layout changes — never per
    // frame. `Path.op(Union)` and `getPathForRange` are heavy, and the layout is stable
    // across the whole dispersion animation, so caching here removes the per-frame Path
    // churn that the animating drift/dispersion would otherwise trigger.
    val mergedPaths: Map<Int, Path> = remember(layoutResult, spoilerGroups) {
        val layout = layoutResult ?: return@remember emptyMap()
        val textLen = layout.layoutInput.text.length
        buildMap {
            for (group in spoilerGroups) {
                val merged = Path()
                var any = false
                for (r in group.ranges) {
                    val end = r.last.coerceAtMost(textLen - 1) + 1
                    val start = r.first.coerceIn(0, end)
                    if (start >= end) continue
                    val sub = layout.getPathForRange(start, end)
                    if (any) merged.op(merged, sub, PathOperation.Union) else merged.addPath(sub)
                    any = true
                }
                if (any) put(group.groupId, merged)
            }
        }
    }

    // One overlay pass over the content: spoiler shimmer first, then code language chips.
    // Both sit ON TOP of the glyphs, and a single drawWithContent owns the one drawContent()
    // call (chaining two drawWithContent modifiers would paint the text twice).
    val overlayMod = if (spoilerGroups.isNotEmpty() || codeLabels.isNotEmpty()) {
        Modifier.drawWithContent {
            drawContent()
            if (mergedPaths.isNotEmpty()) {
                for (group in spoilerGroups) {
                    val progress = spoilerDispersion(group.groupId) ?: continue
                    if (progress >= 1f) continue
                    val path = mergedPaths[group.groupId] ?: continue
                    val field = spoilerFields.getOrPut(group.groupId) { SpoilerField() }
                    clipPath(path) {
                        drawSpoilerShimmer(
                            field = field,
                            seed = group.seed,
                            drift = spoilerDrift,
                            color = spoilerColor,
                            dispersionProgress = progress,
                            densityPxPerDot = TEXT_DENSITY_PX_PER_DOT,
                        )
                    }
                }
            }
            val layout = layoutResult
            if (layout != null && codeLabels.isNotEmpty() && layout.lineCount > 0) {
                val len = layout.layoutInput.text.length
                val lastVisible = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                val pad = CODE_LABEL_PAD.toPx()
                for (deco in codeLabels) {
                    val lang = deco.language ?: continue
                    if (len == 0 || deco.start >= lastVisible) continue
                    val line = layout.getLineForOffset(deco.start.coerceIn(0, len - 1))
                    val measured = textMeasurer.measure(
                        text = lang,
                        style = TextStyle(
                            color = codeLabelColor,
                            fontSize = CODE_LABEL_FONT,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    val tw = measured.size.width.toFloat()
                    val th = measured.size.height.toFloat()
                    // Opaque chip in the box's top-right corner so the label stays legible
                    // even when the first code line runs long underneath it.
                    val chipW = tw + pad
                    val chipH = th + pad * 0.5f
                    val chipLeft = (size.width - chipW - pad).coerceAtLeast(0f)
                    val chipTop = (layout.getLineTop(line) + BLOCK_VPAD.toPx()).coerceAtLeast(0f)
                    drawRoundRect(
                        color = codeLabelChip,
                        topLeft = Offset(chipLeft, chipTop),
                        size = Size(chipW, chipH),
                        cornerRadius = CornerRadius(CODE_LABEL_CORNER.toPx()),
                    )
                    drawText(measured, topLeft = Offset(chipLeft + pad * 0.5f, chipTop + pad * 0.25f))
                }
            }
        }
    } else Modifier

    Text(
        text = renderable.text,
        inlineContent = renderable.inlineContent,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout ->
            layoutResult = layout
            onTextLayout(layout)
        },
        // Draw order (behind → front): block boxes, pressed-link highlight, then the
        // glyphs, then spoiler shimmer / code labels on top.
        modifier = modifier
            .then(linkMod)
            .then(pressMod)
            .then(blockMod)
            .then(highlightMod)
            .then(overlayMod),
    )

    pressedLink?.let { url ->
        LinkActionsSheet(url = url, onDismiss = { pressedLink = null })
    }
}
