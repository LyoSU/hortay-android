package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

/**
 * Bottom bar of a public channel the account has not joined: "Read without joining"
 * (secondary, outlined) and Join (primary, filled). Both carry the `clear` intensity —
 * plain shapes, no press morph: the screen's content stays the focus.
 *
 * The bar draws behind the navigation-bar inset ([bottomInset]) so the feed doesn't show
 * through a strip under it. Each button takes half the width and wraps to two lines
 * instead of clipping, which keeps both labels whole at font scale 2.0 and in the longest
 * locales. [busy] disables both while a follow is in flight.
 */
@Composable
internal fun ChannelJoinBar(
    visible: Boolean,
    busy: Boolean,
    bottomInset: Dp,
    onJoin: () -> Unit,
    onReadWithoutJoining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.TopCenter) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .widthIn(max = READING_WIDTH_CAP)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomInset + 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onReadWithoutJoining,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        BarLabel(stringResource(R.string.follow_read_without_joining))
                    }
                    Button(
                        onClick = onJoin,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        BarLabel(stringResource(R.string.channels_join))
                    }
                }
            }
        }
    }
}

@Composable
private fun BarLabel(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
