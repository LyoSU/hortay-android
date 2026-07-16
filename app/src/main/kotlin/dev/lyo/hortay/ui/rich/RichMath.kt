package dev.lyo.hortay.ui.rich

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX math rendering for the rich-message reader ([dev.lyo.hortay.data.rich.RichInline.Math] /
 * [dev.lyo.hortay.data.rich.RichBlock.Math]).
 *
 * Engine: `ru.noties:jlatexmath-android`, a pure-Java jLaTeXMath port that lays a formula out and
 * draws it to an [android.graphics.Canvas] — no WebView, no JavaScript, fully offline, so it is
 * safe on an always-mounted feed / reading surface. A [JLatexMathDrawable] is a plain [android.graphics.drawable.Drawable]
 * with intrinsic width/height and a baked-in colour; it never animates and holds no resources, so
 * we build it eagerly inside a `remember` and blit it through [LatexCanvas].
 *
 * Colour is baked at build time (jLaTeXMath has no runtime tint), so every `remember` that produces
 * a drawable keys on the resolved `onSurface` colour — a light/dark theme flip re-runs the builder
 * and re-tints. A malformed expression makes jLaTeXMath throw; [buildLatexDrawable] swallows it and
 * returns null so the caller falls back to the monospace source echo — a bad formula NEVER crashes.
 *
 * Inline math (see [rememberInlineMathContent]) is placed baseline-aware via an [InlineTextContent]
 * placeholder; block math ([RichMathBlock]) is centred, scrolls horizontally with the shared edge
 * fade when it's wider than the column, taps to a pan/zoom viewer and long-presses to copy the raw
 * LaTeX.
 */

/**
 * Builds a [JLatexMathDrawable] for [expression] at [textSizePx] pixels, tinted [colorArgb].
 * Returns null when jLaTeXMath can't parse the expression (malformed LaTeX → [Throwable]) or when
 * the laid-out formula is empty, so callers degrade to the raw monospace source. Never throws.
 */
internal fun buildLatexDrawable(expression: String, textSizePx: Float, colorArgb: Int): JLatexMathDrawable? =
    runCatching {
        JLatexMathDrawable.builder(expression)
            .textSize(textSizePx)
            .color(colorArgb)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.getOrNull()?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }

/** Blits a laid-out [drawable] into the composable's own [Canvas], scaling it to the node's size
 *  (callers size the node to the drawable's intrinsic dimensions, so this is a 1:1 draw). */
@Composable
internal fun LatexCanvas(drawable: JLatexMathDrawable, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@Canvas
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

// ---- Inline math ----

/** Inline-content key for a math [expression]; prefixed so it can't collide with the custom-emoji
 *  (`ce-…`) or spoiler-emoji tags that share the same [InlineTextContent] map. */
internal fun mathTag(expression: String): String = "rich-math:$expression"

/**
 * Builds the [InlineTextContent] slots for every inline math [expressions] in a run, sized to the
 * surrounding [baseStyle] and tinted `onSurface` (keyed so a theme flip re-tints). Each slot draws
 * the laid-out formula; a formula jLaTeXMath can't parse falls back to its monospace source echo,
 * measured so its placeholder box fits (never a blank gap).
 *
 * Vertical placement is [PlaceholderVerticalAlign.AboveBaseline] — the box bottom sits on the text
 * baseline. For the overwhelmingly common inline forms (variables, superscripts like `x^2`) the
 * formula's own depth below its baseline is ~0, so this lands them exactly on the line; a formula
 * with real depth (an inline fraction, a subscript) floats up by that depth, because Compose has no
 * arbitrary-baseline placeholder align. That trade reads better than centring everything on the
 * line, which sinks the common superscript case below the baseline.
 */
@Composable
internal fun rememberInlineMathContent(
    expressions: Set<String>,
    baseStyle: TextStyle,
): Map<String, InlineTextContent> {
    val color = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val descriptionFmt = stringResource(R.string.rich_math_description)
    return remember(expressions, baseStyle, color) {
        if (expressions.isEmpty()) {
            emptyMap()
        } else {
            val basePx = with(density) {
                (if (baseStyle.fontSize.isSp) baseStyle.fontSize else DEFAULT_MATH_TEXT_SIZE).toPx()
            }
            val colorArgb = color.toArgb()
            expressions.associate { expr ->
                mathTag(expr) to inlineMathSlot(expr, basePx, colorArgb, color, baseStyle, density, measurer, descriptionFmt)
            }
        }
    }
}

private fun inlineMathSlot(
    expression: String,
    textSizePx: Float,
    colorArgb: Int,
    color: Color,
    baseStyle: TextStyle,
    density: Density,
    measurer: TextMeasurer,
    descriptionFmt: String,
): InlineTextContent {
    val drawable = buildLatexDrawable(expression, textSizePx, colorArgb)
    if (drawable == null) {
        // Parse failure → inline monospace echo. Measure it so the placeholder box is the right
        // size, and centre it (a text token, not a formula, so baseline-shift doesn't apply).
        val fallbackStyle = baseStyle.copy(fontFamily = FontFamily.Monospace)
        val measured = measurer.measure(AnnotatedString(expression), fallbackStyle)
        val width = with(density) { measured.size.width.toSp() }
        val height = with(density) { measured.size.height.toSp() }
        return InlineTextContent(
            placeholder = Placeholder(width, height, PlaceholderVerticalAlign.Center),
            children = { Text(text = expression, style = fallbackStyle, color = color, softWrap = false) },
        )
    }
    val width = with(density) { drawable.intrinsicWidth.toSp() }
    val height = with(density) { drawable.intrinsicHeight.toSp() }
    val description = descriptionFmt.format(expression)
    return InlineTextContent(
        placeholder = Placeholder(width, height, PlaceholderVerticalAlign.AboveBaseline),
        children = {
            LatexCanvas(
                drawable = drawable,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description },
            )
        },
    )
}

// ---- Block math ----

/**
 * `pageBlockMathematicalExpression` — a display formula on its own line: centred when it fits the
 * column, horizontally scrollable with the shared edge fade when it's wider. Tap opens a pan/zoom
 * viewer; long-press copies the raw LaTeX with a haptic tick + "Copied" toast; TalkBack reads the
 * localized "Formula: <source>". A formula jLaTeXMath can't parse falls back to [RichCodeBox] — the
 * same monospace box a `pageBlockPreformatted` uses — so a malformed expression degrades, never
 * crashes.
 */
@Composable
internal fun RichMathBlock(expression: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val sizePx = with(density) { BLOCK_MATH_TEXT_SIZE.toPx() }
    val drawable = remember(expression, onSurface, sizePx) {
        buildLatexDrawable(expression, sizePx, onSurface.toArgb())
    }
    if (drawable == null) {
        RichCodeBox(dev.lyo.hortay.data.rich.RichInline.Plain(expression), language = "")
        return
    }

    val formulaWidth = with(density) { drawable.intrinsicWidth.toDp() }
    val formulaHeight = with(density) { drawable.intrinsicHeight.toDp() }
    val fadeColor = MaterialTheme.colorScheme.surface
    val scrollState = rememberScrollState()

    var enlarged by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.post_copied_toast)
    val description = stringResource(R.string.rich_math_description).format(expression)
    val enlargeLabel = stringResource(R.string.rich_math_enlarge)
    val copyLabel = stringResource(R.string.rich_math_copy)

    val copy = {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        clipboard.setText(AnnotatedString(expression))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalFoundationApi::class)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = enlargeLabel,
                onLongClickLabel = copyLabel,
                onClick = { enlarged = true },
                onLongClick = copy,
            )
            .semantics { contentDescription = description },
    ) {
        val fits = formulaWidth <= maxWidth
        if (fits) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LatexCanvas(drawable, Modifier.size(formulaWidth, formulaHeight))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The formula dissolves into the surface colour at whichever side is still
                    // clipped — the same live "more this way" swipe hint tables and code use.
                    .drawWithContent {
                        drawContent()
                        val fadeW = MATH_EDGE_FADE.toPx()
                        if (scrollState.value > 0) {
                            drawRect(Brush.horizontalGradient(listOf(fadeColor, Color.Transparent), 0f, fadeW))
                        }
                        if (scrollState.value < scrollState.maxValue) {
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, fadeColor), size.width - fadeW, size.width))
                        }
                    },
            ) {
                Box(modifier = Modifier.horizontalScroll(scrollState)) {
                    LatexCanvas(drawable, Modifier.size(formulaWidth, formulaHeight))
                }
            }
        }
    }

    if (enlarged) {
        RichMathViewerDialog(expression = expression, onDismiss = { enlarged = false })
    }
}

// ---- Enlarged viewer ----

/** Pan/pinch-zoom clamp for the fullscreen formula viewer. */
private const val MATH_VIEWER_MIN_ZOOM = 0.5f
private const val MATH_VIEWER_MAX_ZOOM = 5f

/**
 * Fullscreen pan- and pinch-zoomable rendering of a single formula, mirroring the media / table
 * viewer idiom — a borderless [Dialog] whose back press and scrim dismiss both route through
 * [onDismiss]. The formula is rebuilt at a larger text size for crispness under zoom. The top bar
 * copies the raw LaTeX. A formula that failed to parse shows its monospace source instead of a
 * blank canvas.
 */
@Composable
private fun RichMathViewerDialog(expression: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val onSurface = MaterialTheme.colorScheme.onSurface
        val density = LocalDensity.current
        val sizePx = with(density) { MATH_VIEWER_TEXT_SIZE.toPx() }
        val drawable = remember(expression, onSurface, sizePx) {
            buildLatexDrawable(expression, sizePx, onSurface.toArgb())
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MATH_VIEWER_MIN_ZOOM, MATH_VIEWER_MAX_ZOOM)
            offset += panChange
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                RichMathViewerBar(expression = expression, onClose = onDismiss)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .transformable(transformState),
                    contentAlignment = Alignment.Center,
                ) {
                    if (drawable != null) {
                        val w = with(density) { drawable.intrinsicWidth.toDp() }
                        val h = with(density) { drawable.intrinsicHeight.toDp() }
                        LatexCanvas(
                            drawable = drawable,
                            modifier = Modifier
                                .size(w, h)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                    } else {
                        Text(
                            text = expression,
                            style = RichType.code,
                            color = onSurface,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMathViewerBar(expression: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val copiedMsg = stringResource(R.string.post_copied_toast)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Symbol(name = "close", contentDescription = stringResource(R.string.action_close))
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                clipboard.setText(AnnotatedString(expression))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        ) {
            Symbol(name = "content_copy", contentDescription = stringResource(R.string.rich_math_copy))
        }
    }
}

// ---- Sizing ----

/** Fallback inline math size when the surrounding run's font size isn't expressed in sp. */
private val DEFAULT_MATH_TEXT_SIZE = 16.sp

/** Block (display) math sits a touch larger than body text ([RichType.paragraph] is 16 sp) for
 *  presence on its own line. */
private val BLOCK_MATH_TEXT_SIZE = 20.sp

/** Re-render size for the fullscreen viewer — large enough that a heavy formula stays crisp before
 *  the user even zooms. */
private val MATH_VIEWER_TEXT_SIZE = 44.sp

/** Width of the horizontal edge-fade scrim on a formula wider than the column. */
private val MATH_EDGE_FADE = 24.dp
