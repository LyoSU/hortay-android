package dev.lyo.hortay.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.collections.immutable.ImmutableList

/**
 * One primary channel action, renderable either as a tile in [ActionTileStrip] or — when the
 * strip can't hold its labels — as a plain row. [tileLabel] is the short form shown under the
 * tile icon ("Mute", "Link"); [rowLabel] is the full phrase ("Mute notifications", "Copy
 * link") the row fallback shows and the tile announces as its accessible name, so the short
 * visible label never has to carry the whole meaning. For a toggle, both [symbol] and the
 * labels describe the action a tap performs (Telegram's convention), never the current state:
 * a state glyph next to an action label reads as a contradiction.
 */
@Immutable
internal data class ChannelActionTile(
    val key: String,
    val symbol: String,
    val tileLabel: String,
    val rowLabel: String,
    val iconTint: Color,
    val onClick: () -> Unit,
)

/**
 * Equal-width tonal tiles (icon over a short label) for the channel's primary actions.
 *
 * The strip is only worth it with two or more tiles — a single full-width tile reads as a
 * stray button, so one tile goes straight to [row]. It also falls back to [row] for every
 * action when any label can't be set inside its tile in at most [TILE_LABEL_MAX_LINES] lines
 * without breaking a word: that is what happens at font scale 2.0 or with long German /
 * Ukrainian labels, and a tile with a label chopped mid-word is worse than a list row. The
 * check measures the real text with the current density (font scale included), so it adapts
 * to the locale and the window width rather than to a hardcoded scale threshold.
 */
@Composable
internal fun ActionTileStrip(
    tiles: ImmutableList<ChannelActionTile>,
    /** Horizontal inset of the tile row; the row fallback keeps its own list-item inset. */
    contentInset: Dp,
    row: @Composable (ChannelActionTile) -> Unit,
) {
    if (tiles.size < 2) {
        tiles.forEach { row(it) }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val count = tiles.size
        val labels = tiles.map { it.tileLabel }
        val availablePx = with(density) {
            ((maxWidth - contentInset * 2 - TILE_GAP * (count - 1)) / count - TILE_PADDING_H * 2).roundToPx()
        }
        val fits = remember(labels, availablePx, style, density) {
            availablePx > 0 && labels.all { label ->
                val wrapped = measurer.measure(
                    text = label,
                    style = style,
                    constraints = Constraints(maxWidth = availablePx),
                )
                val longestWord = label.split(' ').maxOfOrNull { word ->
                    measurer.measure(text = word, style = style, softWrap = false).size.width
                } ?: 0
                wrapped.lineCount <= TILE_LABEL_MAX_LINES && longestWord <= availablePx
            }
        }
        if (fits) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentInset)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                tiles.forEach { tile ->
                    ActionTile(
                        tile = tile,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        } else {
            Column { tiles.forEach { row(it) } }
        }
    }
}

@Composable
private fun ActionTile(tile: ChannelActionTile, modifier: Modifier = Modifier) {
    // Surface(onClick) gives the tile Role.Button semantics, the ripple clipped to its
    // shape, and a minimum interactive size. The content's own semantics are replaced by
    // [ChannelActionTile.rowLabel], which merges up into the button as its accessible name:
    // the visible tile label is the short form ("Link") of that phrase ("Copy link").
    Surface(
        onClick = tile.onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // Top-aligned, not centred: the strip stretches every tile to the tallest one, and a
        // label that wraps to two lines (font scale 2.0, de / uk / ru) would otherwise lift its
        // icon above the icons of the one-line neighbours. Top alignment keeps all icons on
        // one line and lets the labels hang below them.
        Column(
            modifier = Modifier
                .heightIn(min = TILE_MIN_HEIGHT)
                .clearAndSetSemantics { contentDescription = tile.rowLabel }
                .padding(horizontal = TILE_PADDING_H, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Symbol(name = tile.symbol, tint = tile.iconTint, size = 24.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = tile.tileLabel,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = TILE_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val TILE_GAP = 8.dp
private val TILE_PADDING_H = 8.dp
private val TILE_MIN_HEIGHT = 72.dp
private const val TILE_LABEL_MAX_LINES = 2
