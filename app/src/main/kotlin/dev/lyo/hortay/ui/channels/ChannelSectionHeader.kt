package dev.lyo.hortay.ui.channels

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.tabularFigures

/**
 * Quiet section label above a group of channel rows (signed-in and guest Channels tabs):
 * a small muted title and the row count, announced as a heading. With [onToggle] the whole
 * header expands / collapses its section; the chevron turns over vertically, so it reads
 * the same in right-to-left layouts. `heightIn`, not a fixed height: the label may wrap at
 * font scale 2.0.
 */
@Composable
fun ChannelSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val toggleLabel = stringResource(if (expanded) R.string.channels_section_collapse else R.string.channels_section_expand)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { heading() }
            // End padding lines the chevron up with the rows' trailing content.
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Title and count take the free width as one unit, so the chevron sits at the end
        // instead of splitting the row's spare space with a second weighted child.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall.tabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onToggle != null) {
            Spacer(Modifier.width(8.dp))
            val turn by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "channel-section-chevron",
            )
            Symbol(
                name = "keyboard_arrow_down",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
                modifier = Modifier.rotate(turn),
            )
        }
    }
}
