package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveRow(snapshot: PostSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isDeleted = snapshot.kind == SnapshotKind.DELETED
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClick,
        colors = if (isDeleted) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (isDeleted) stringResource(R.string.archive_row_kind_deleted)
                else stringResource(R.string.archive_row_kind_edited),
                style = MaterialTheme.typography.labelMedium,
                color = if (isDeleted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                snapshot.content.textPreview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
