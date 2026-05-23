package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import dev.lyo.hortay.ui.icons.Symbol
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveScope
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.ui.archive.components.ArchiveRow
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    onBack: () -> Unit,
    onOpenInTelegram: (PostSnapshot) -> Unit = {},
) {
    val snapshots by viewModel.snapshots.collectAsState()
    val filter by viewModel.filter.collectAsState()
    var openSnapshot by remember { mutableStateOf<PostSnapshot?>(null) }
    var queryText by remember { mutableStateOf("") }

    LaunchedEffect(queryText) {
        delay(250)
        viewModel.setQuery(queryText)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text(stringResource(R.string.archive_search_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter.kind == null,
                    onClick = { viewModel.setKind(null) },
                    label = { Text(stringResource(R.string.archive_filter_all)) },
                )
                FilterChip(
                    selected = filter.kind == SnapshotKind.DELETED,
                    onClick = { viewModel.setKind(SnapshotKind.DELETED) },
                    label = { Text(stringResource(R.string.archive_filter_deleted)) },
                )
                FilterChip(
                    selected = filter.kind == SnapshotKind.VERSION,
                    onClick = { viewModel.setKind(SnapshotKind.VERSION) },
                    label = { Text(stringResource(R.string.archive_filter_edited)) },
                )
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter.scope == null,
                    onClick = { viewModel.setScope(null) },
                    label = { Text(stringResource(R.string.archive_scope_all)) },
                )
                FilterChip(
                    selected = filter.scope == ArchiveScope.POSTS,
                    onClick = { viewModel.setScope(ArchiveScope.POSTS) },
                    label = { Text(stringResource(R.string.archive_scope_posts)) },
                )
                FilterChip(
                    selected = filter.scope == ArchiveScope.COMMENTS,
                    onClick = { viewModel.setScope(ArchiveScope.COMMENTS) },
                    label = { Text(stringResource(R.string.archive_scope_comments)) },
                )
            }
            if (snapshots.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.archive_empty_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn {
                    items(snapshots, key = { it.id }) { snap ->
                        ArchiveRow(snap, onClick = { openSnapshot = snap })
                    }
                }
            }
        }
    }

    val current = openSnapshot
    if (current != null) {
        val revisions by viewModel.observeRevisionsFor(current)
            .collectAsState(initial = persistentListOf())
        PostRevisionSheet(
            revisions = revisions,
            onDismiss = { openSnapshot = null },
            onOpenInTelegram = { onOpenInTelegram(current) },
        )
    }
}
