package dev.lyo.hortay.ui.text

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * The ONE code-block container shared by regular posts ([dev.lyo.hortay.ui.text]'s `BlockBox`) and
 * rich messages ([dev.lyo.hortay.ui.rich]'s `RichCodeBox`). It owns everything AROUND the code —
 * the flat neutral `surfaceContainerHigh` container (12dp radius), the top-corner language pill,
 * the top-right copy button, horizontal scrolling with a scroll-derived edge fade, and the
 * long-block line cap + expand — while each caller renders the code TEXT itself through [content]
 * (regular posts a monospace [LinkAwareText]; rich messages `RichInlineText` at `RichTypography.code`).
 *
 * The code content is pinned LTR regardless of document / UI direction (source lines read
 * left-to-right) and is NOT wrapped — long lines scroll horizontally instead. [rawText] is the
 * plain source copied verbatim by the copy button. [codeStyle] drives the line-cap budget (its
 * line height × [collapsedLines]).
 *
 * [collapsedLines] caps a long block and reveals it with a [TonalActionRow] beneath the container;
 * pass `null` to render every line uncapped. Regular posts pass the cap only on a fully-shown
 * surface — inside a clamped feed preview the post-wide clip does the cutting, so no per-block
 * toggle competes with it; rich messages cap only on the reading surface for the same reason.
 */
@Composable
internal fun CodeBlock(
    rawText: String,
    language: String?,
    codeStyle: TextStyle,
    collapsedLines: Int?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember(rawText) { mutableStateOf(false) }
    var overflow by remember(rawText) { mutableStateOf(false) }
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val density = LocalDensity.current
    val clampHeightPx = if (collapsedLines != null && !expanded) {
        with(density) { (codeLineHeight(codeStyle).toPx() * collapsedLines).toInt() }
    } else {
        null
    }

    Column(modifier = modifier) {
        // The whole container is pinned LTR so the pill sits at the leading (left) corner, the copy
        // button at the trailing (right) corner, and the code reads left-to-right even in an RTL
        // document. The expand affordance below stays in the ambient direction (localized label).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CODE_RADIUS))
                    .background(containerColor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CODE_HEADER_HEIGHT)
                        .padding(start = CODE_CONTENT_PAD, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!language.isNullOrBlank()) CodeLanguagePill(language)
                    Spacer(Modifier.weight(1f))
                    CodeCopyButton(rawText)
                }
                CodeScrollViewport(
                    fadeColor = containerColor,
                    clampHeightPx = clampHeightPx,
                    onOverflow = { if (overflow != it) overflow = it },
                    content = content,
                )
            }
        }
        if (collapsedLines != null && overflow && !expanded) {
            TonalActionRow(text = stringResource(R.string.post_show_more), onClick = { expanded = true })
        }
    }
}

/** A long code block caps at ~this many lines before the [TonalActionRow] expand affordance shows. */
internal const val CODE_COLLAPSED_LINES = 12

private val CODE_RADIUS = 12.dp
private val CODE_HEADER_HEIGHT = 36.dp
private val CODE_CONTENT_PAD = 12.dp
private val CODE_EDGE_FADE = 24.dp
private const val COPY_CHECK_MS = 1500L

/** Header height a code segment reserves at its top — read by [dev.lyo.hortay.ui.text] `ClampedPost`
 *  so the post-wide line-snap clip lands on the code's real text grid. */
internal val CODE_HEADER_INSET = CODE_HEADER_HEIGHT

/** Bottom padding under a code segment's last line — the counterpart to [CODE_HEADER_INSET]. */
internal val CODE_BOTTOM_INSET = CODE_CONTENT_PAD

private fun codeLineHeight(style: TextStyle) = when {
    style.lineHeight.isSp -> style.lineHeight
    style.fontSize.isSp -> style.fontSize * 1.4f
    else -> 20.sp
}

/** The horizontally-scrollable code viewport with a scroll-derived edge fade (the same "more this
 *  way" idiom RichTable uses) and an optional vertical line cap. */
@Composable
private fun CodeScrollViewport(
    fadeColor: Color,
    clampHeightPx: Int?,
    onOverflow: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scroller: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(start = CODE_CONTENT_PAD, end = CODE_CONTENT_PAD, bottom = CODE_CONTENT_PAD),
        ) { content() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val w = CODE_EDGE_FADE.toPx()
                if (scrollState.value > 0) {
                    drawRect(Brush.horizontalGradient(listOf(fadeColor, Color.Transparent), 0f, w))
                }
                if (scrollState.value < scrollState.maxValue) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, fadeColor), size.width - w, size.width))
                }
            },
    ) {
        if (clampHeightPx != null) {
            Layout(modifier = Modifier.clipToBounds(), content = scroller) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
                val width = placeables.maxOfOrNull { it.width } ?: 0
                val full = placeables.sumOf { it.height }
                // The affordance sits OUTSIDE this Layout, so flipping `overflow` never changes what
                // this measures — it converges in one pass and never loops.
                onOverflow(full > clampHeightPx)
                val h = if (full > clampHeightPx) clampHeightPx else full
                layout(width, h) {
                    var y = 0
                    placeables.forEach { it.place(0, y); y += it.height }
                }
            }
        } else {
            scroller()
        }
    }
}

/** Small monospace pill in the container's leading corner naming the code's language. */
@Composable
private fun CodeLanguagePill(language: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = language,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Copies [rawText] verbatim to the clipboard with a haptic tick and a brief "Copied" toast, and
 * swaps its glyph to a check for [COPY_CHECK_MS] as inline confirmation. The swap animates on the
 * effects spec; reduced motion snaps it instantly. Reuses the app's `content_copy` haptic idiom
 * (see reactions / poll votes) and the shared `post_copied_toast` string.
 */
@Composable
private fun CodeCopyButton(rawText: String) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.post_copied_toast)
    val copyDesc = stringResource(R.string.rich_code_copy)
    val reduced = rememberReducedMotion()
    var copied by remember(rawText) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CHECK_MS)
            copied = false
        }
    }
    val spec = if (reduced) snap() else MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = copyDesc) {
                clipboard.setText(AnnotatedString(rawText))
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                copied = true
            }
            .semantics { contentDescription = copyDesc },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = copied, animationSpec = spec, label = "code-copy-icon") { done ->
            Symbol(
                name = if (done) "check_circle" else "content_copy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
                contentDescription = null,
            )
        }
    }
}
