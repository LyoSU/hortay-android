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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.media.SpoilerField
import dev.lyo.hortay.ui.media.TEXT_DENSITY_PX_PER_DOT
import dev.lyo.hortay.ui.media.drawSpoilerShimmer
import dev.lyo.hortay.ui.media.rememberSpoilerDrift

// Pressed-link highlight: a rounded fill behind the pressed link, inflated past the
// glyphs so it reads as a padded pill rather than a tight background.
private val LINK_HIGHLIGHT_PAD = 3.dp
private val LINK_HIGHLIGHT_CORNER = 5.dp

/**
 * Drop-in [Text] replacement with link-tap handling, long-press, a padded pressed-link
 * highlight, and the spoiler dot-cloud overlay.
 *
 * Paragraph-level blocks (quotes, code) are NOT handled here — RichText lifts them into
 * padded composables. This renders the single backing [Text] for one inline run.
 *
 * Pressed-link highlight: a non-consuming pointer observer ([linkPressHighlight]) reports
 * which tappable range the finger is on; we paint a rounded, glyph-inflated fill behind it
 * via [Modifier.drawBehind]. Works for every link kind (URL / @mention / inline mention /
 * hashtag) because [RenderableText.pressableRanges] covers them all.
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
            val len = layout.layoutInput.text.length
            if (len == 0) return@drawBehind
            val startOff = range.first.coerceIn(0, len)
            val endOff = (range.last + 1).coerceIn(startOff, len)
            if (endOff <= startOff) return@drawBehind
            val firstLine = layout.getLineForOffset(startOff)
            val lastLine = layout.getLineForOffset((endOff - 1).coerceAtLeast(startOff))
            val padH = LINK_HIGHLIGHT_PAD.toPx()
            val corner = CornerRadius(LINK_HIGHLIGHT_CORNER.toPx())
            for (line in firstLine..lastLine) {
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(line)
                val left = if (line == firstLine) {
                    layout.getHorizontalPosition(startOff, usePrimaryDirection = true)
                } else {
                    layout.getLineLeft(line)
                }
                val right = if (line == lastLine) {
                    layout.getHorizontalPosition(endOff, usePrimaryDirection = true)
                } else {
                    layout.getLineRight(line)
                }
                val x = (minOf(left, right) - padH).coerceAtLeast(0f)
                val r = (maxOf(left, right) + padH).coerceAtMost(size.width)
                if (r <= x) continue
                drawRoundRect(linkHighlight, Offset(x, top), Size(r - x, bottom - top), corner)
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

    val spoilerMod = if (spoilerGroups.isNotEmpty()) {
        Modifier.drawWithContent {
            drawContent()
            if (mergedPaths.isEmpty()) return@drawWithContent
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
        // Behind → front: pressed-link highlight, then the glyphs, then spoiler shimmer.
        modifier = modifier
            .then(linkMod)
            .then(pressMod)
            .then(highlightMod)
            .then(spoilerMod),
    )

    pressedLink?.let { url ->
        LinkActionsSheet(url = url, onDismiss = { pressedLink = null })
    }
}
