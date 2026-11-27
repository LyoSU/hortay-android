package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.rememberExpandedSheetState

/**
 * The choice behind a post's "Hide channel from feed": for a while, or until the reader
 * shows the channel again. A sheet rather than four menu items because the post menu is
 * about the post — only this one entry is about its channel.
 *
 * [onStopReading] (a channel read without joining) adds "Stop reading this channel" under
 * a divider: hiding keeps the channel in the Channels tab and the background checks, while
 * stopping drops it — two intents, kept apart. Null leaves the durations alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HideChannelSheet(
    channelName: String,
    onPick: (HideDuration) -> Unit,
    onDismiss: () -> Unit,
    onStopReading: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.hide_channel_sheet_title, channelName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HideDuration.entries.forEach { duration ->
                SheetChoiceRow(text = stringResource(duration.label), onClick = { onPick(duration) })
            }
            if (onStopReading != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SheetChoiceRow(text = stringResource(R.string.follow_stop_reading_channel), onClick = onStopReading)
            }
        }
    }
}

@Composable
private fun SheetChoiceRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
