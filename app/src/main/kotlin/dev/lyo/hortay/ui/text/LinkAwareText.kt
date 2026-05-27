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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.lyo.hortay.ui.media.SpoilerField
import dev.lyo.hortay.ui.media.TEXT_DENSITY_PX_PER_DOT
import dev.lyo.hortay.ui.media.drawSpoilerShimmer
import dev.lyo.hortay.ui.media.rememberSpoilerDrift

/**
 * Drop-in [Text] replacement with link-tap handling, long-press, and spoiler dot-cloud
 * overlay. See [RenderableText] for the spoiler grouping model.
 *
 * Each [SpoilerGroupInfo] is painted as ONE shimmer over the union of its sub-range
 * paths. That's the visual fix for the "emoji and text reveal separately" bug: TDLib
 * commonly splits a single spoiler into two adjacent entities around a CustomEmoji,
 * but the group merges them back into one cover with one shared seed → continuous
 * particle pattern → reveals as one.
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

    val linkMod = if (renderable.linkRanges.isNotEmpty()) {
        Modifier.linkLongPress(
            linkRanges = renderable.linkRanges,
            layoutResult = layoutResult,
            onLongPress = { range -> pressedLink = range.url },
        )
    } else Modifier

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
        modifier = modifier.then(linkMod).then(spoilerMod),
    )

    pressedLink?.let { url ->
        LinkActionsSheet(url = url, onDismiss = { pressedLink = null })
    }
}
