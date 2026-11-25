package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Top-level scope a feed item belongs to. Mirrors how Telegram itself splits chats: the
 * main timeline, any custom folder the user has, plus the dedicated archive list.
 */
@Immutable
sealed interface FilterScope {
    data object All : FilterScope
    data class Folder(val id: Int, val title: String) : FilterScope
    data object Archive : FilterScope
}

/**
 * Horizontally-scrollable strip of text tabs with an accent underline under the selected
 * one — the Threads / Bluesky / X feed-switcher idiom. A filled pill per folder read as a
 * row of buttons competing with the posts below; plain labels with one underline keep the
 * chrome quiet and make the active scope obvious.
 *
 * The underline is ONE indicator drawn by the row, not one per tab: on a scope switch it
 * slides and resizes from the old label to the new one on the fast spatial spec (snaps
 * under reduced motion). The earlier per-tab fade dimmed one bar out and another in, so the
 * eye lost where the selection went. Tab spans are recorded in the row's own coordinates
 * (physical px), so the indicator lands under the right label in RTL too. A faint bottom
 * hairline runs the full width of the bar so posts scrolling under it read as passing
 * beneath the tabs rather than being cut off by them.
 *
 * Telegram folders carry their own emoji inside the name's FormattedText, so we
 * render the plain text without re-decorating — the official client does the same
 * in its compact-chip variant.
 */
@Composable
fun FoldersBar(
    selected: FilterScope,
    folders: List<FolderTab>,
    showArchive: Boolean,
    onSelected: (FilterScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = when (selected) {
        FilterScope.All -> 0
        is FilterScope.Folder -> folders.indexOfFirst { it.id == selected.id }.let { if (it < 0) -1 else it + 1 }
        FilterScope.Archive -> if (showArchive) folders.size + 1 else -1
    }
    val spans = remember { mutableStateMapOf<Int, TabSpan>() }
    val target = spans[selectedIndex]
    val indicatorX = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    var indicatorPlaced by remember { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val slideSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(target, reducedMotion) {
        val span = target ?: return@LaunchedEffect
        if (!indicatorPlaced || reducedMotion) {
            // First placement (and reduced motion) snaps: the underline must not sweep in
            // from the row's start edge on the first frame.
            indicatorX.snapTo(span.start)
            indicatorWidth.snapTo(span.width)
            indicatorPlaced = true
        } else {
            launch { indicatorX.animateTo(span.start, slideSpec) }
            indicatorWidth.animateTo(span.width, slideSpec)
        }
    }
    val indicatorColor = MaterialTheme.colorScheme.primary
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HAIRLINE_ALPHA)
    val showIndicator = indicatorPlaced && target != null
    Row(
        modifier = modifier
            // Outside the scroll: the hairline spans the whole bar, not the scrolled content.
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = hairlineColor,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            }
            .horizontalScroll(rememberScrollState())
            // Groups the Role.Tab labels so TalkBack announces position and count.
            .selectableGroup()
            // Inside the scroll and with no padding modifier in between, so the chips'
            // positionInParent is exactly this draw scope's coordinate space.
            .drawWithContent {
                drawContent()
                if (!showIndicator) return@drawWithContent
                val h = INDICATOR_HEIGHT.toPx()
                val top = size.height - h
                // A rounded rect twice the height, clipped to its top half: rounded top
                // corners, flat bottom sitting on the hairline.
                clipRect(top = top) {
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(indicatorX.value, top),
                        size = Size(indicatorWidth.value.coerceAtLeast(0f), h * 2),
                        cornerRadius = CornerRadius(h, h),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
    ) {
        // Edge inset as spacers rather than padding (see drawWithContent above).
        Spacer(Modifier.width(EDGE_INSET - TAB_GAP))
        FolderChip(
            label = stringResource(R.string.folder_all),
            selected = selectedIndex == 0,
            onClick = { onSelected(FilterScope.All) },
            onSpan = { spans.record(0, it) },
        )
        folders.forEachIndexed { i, tab ->
            FolderChip(
                label = tab.title,
                selected = selectedIndex == i + 1,
                onClick = { onSelected(FilterScope.Folder(tab.id, tab.title)) },
                onSpan = { spans.record(i + 1, it) },
            )
        }
        if (showArchive) {
            val archiveIndex = folders.size + 1
            FolderChip(
                label = stringResource(R.string.folder_archive),
                selected = selectedIndex == archiveIndex,
                onClick = { onSelected(FilterScope.Archive) },
                onSpan = { spans.record(archiveIndex, it) },
            )
        }
        Spacer(Modifier.width(EDGE_INSET - TAB_GAP))
    }
}

@Immutable
data class FolderTab(val id: Int, val title: String)

/** Horizontal extent of a tab's label underline, in the row's physical px. */
@Immutable
private data class TabSpan(val start: Float, val width: Float)

private fun MutableMap<Int, TabSpan>.record(index: Int, span: TabSpan) {
    if (this[index] != span) this[index] = span
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onSpan: (TabSpan) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder-fg",
    )
    val insetPx = with(LocalDensity.current) { CHIP_PADDING.toPx() }
    Column(
        modifier = Modifier
            // Outermost, so the reported bounds are the whole chip; the underline spans the
            // label, i.e. the chip minus its horizontal padding.
            .onPlaced { coords ->
                val x = coords.positionInParent().x
                val w = coords.size.width.toFloat()
                onSpan(TabSpan(start = x + insetPx, width = (w - 2 * insetPx).coerceAtLeast(0f)))
            }
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    // J2: light selection tick when switching TO a different tab —
                    // re-tapping the active one stays silent (no scope change).
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            // widthIn too: an emoji-only folder label is narrower than a touch target.
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = CHIP_PADDING)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Bottom: the label keeps its distance to the shared underline, which sits on the
        // bar's bottom edge, whatever height the 48 dp minimum adds.
        verticalArrangement = Arrangement.Bottom,
    ) {
        // B4: emoji-only folder labels (Telegram folders carry their own emoji in
        // the name) otherwise render taller than text labels — the emoji glyph's
        // intrinsic line-height exceeds the text cap-to-baseline box. A FIXED
        // lineHeight + trimmed line-height edges + no font padding pins every tab to
        // the same content height regardless of whether the label is text or emoji.
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(
                lineHeight = 20.sp,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
        // Gap to the underline plus the underline's own height, which the row draws.
        Spacer(Modifier.height(10.dp + INDICATOR_HEIGHT))
    }
}

private val INDICATOR_HEIGHT = 3.dp
private val CHIP_PADDING = 12.dp
private val TAB_GAP = 2.dp
private val EDGE_INSET = 8.dp

/** Bottom hairline: present but barely — it marks the edge, it is not a divider. */
private const val HAIRLINE_ALPHA = 0.4f
