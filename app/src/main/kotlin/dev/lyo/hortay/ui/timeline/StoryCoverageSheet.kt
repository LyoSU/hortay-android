package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.media.TdAvatar
import kotlinx.collections.immutable.ImmutableList

/**
 * The posts behind a feed row's "Also covered by" line: one row per other channel that wrote
 * about the same story, oldest first. Tapping a row opens that channel at its post.
 *
 * [items] come from [storyCoverageItems], so the sheet lists exactly the channels the line
 * names (minus any whose post has since left the feed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryCoverageSheet(
    items: ImmutableList<StoryCoverageItem>,
    onOpen: (StoryCoverageItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            item(key = "title", contentType = "title") {
                Text(
                    text = stringResource(R.string.feed_also_covered_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.key }, contentType = { "post" }) { item ->
                val onClick = remember(item, onOpen) { { onOpen(item) } }
                StoryCoverageRow(item = item, onClick = onClick)
            }
        }
    }
}

@Composable
private fun StoryCoverageRow(item: StoryCoverageItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.link_action_open),
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TdAvatar(
            name = item.badge.title,
            thumb = item.badge.thumb,
            fileId = item.badge.fileId,
            remoteUrl = item.badge.avatarUrl,
            size = 40.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.badge.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatRelative(item.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (item.excerpt.isNotEmpty()) {
                Text(
                    text = item.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
