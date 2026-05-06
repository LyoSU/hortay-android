package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.web.MigrationCoordinator
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * One-time bottom sheet shown after the user signs in if they had any
 * anonymous-mode subscriptions. Each guest-mode channel surfaces with a
 * checkbox; the user picks any subset (or none) and we drive the migration
 * via [coordinator]. Skipping ("Не зараз") still counts as "shown" — we
 * don't reopen the sheet on subsequent sign-ins.
 *
 * The sheet stays mounted while the migration is in flight: a progress
 * indicator on the confirm button shows N/M as channels join one at a time.
 * The sheet auto-dismisses when the coordinator's pending-proposal flips
 * back to null (i.e. on confirm completion or skip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationProposalSheet(
    coordinator: MigrationCoordinator,
    candidates: List<String>,
    onDismiss: () -> Unit,
) {
    if (candidates.isEmpty()) return
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val progress by coordinator.progress.collectAsStateWithLifecycle()

    // Default selection: everything checked. Most users who built a guest-mode
    // list have already curated it, so the cheap default is "yes, migrate all"
    // — they uncheck what they don't want rather than building from zero.
    val selected = remember(candidates) {
        mutableStateOf(candidates.toSet())
    }
    var inFlight by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            // Block dismiss while migration is running — losing the sheet
            // mid-run hides progress feedback and could orphan the user.
            if (!inFlight) onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.migration_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.migration_subtitle,
                    candidates.size,
                    candidates.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Trade-off disclaimer. The previous subtitle implied a one-way
            // "import bookmarks" flow. In reality this issues real
            // SearchPublicChat + JoinChat calls, so the user shows up in
            // the channel admin's member list. Surface that explicitly so the
            // confirmation is informed, not surprised-by-default.
            Text(
                text = stringResource(R.string.migration_subtitle_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val allSelected = selected.value.size == candidates.size
                TextButton(
                    onClick = {
                        selected.value = if (allSelected) emptySet() else candidates.toSet()
                    },
                    enabled = !inFlight,
                ) {
                    Text(
                        text = stringResource(
                            if (allSelected) R.string.migration_deselect_all
                            else R.string.migration_select_all,
                        ),
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items = candidates, key = { it }) { username ->
                    val checked = username in selected.value
                    // Whole-row toggleable for Talkback users — the entire row
                    // announces as one Checkbox-role target rather than forcing
                    // them to land on the 24dp box. Material guidance for list
                    // rows with leading checkboxes; passing onCheckedChange=null
                    // to the Checkbox itself delegates click handling to the row.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                enabled = !inFlight,
                                role = Role.Checkbox,
                                onValueChange = { isChecked ->
                                    selected.value = if (isChecked) selected.value + username
                                    else selected.value - username
                                },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !inFlight,
                            onCheckedChange = null,
                        )
                        Symbol(
                            name = "campaign",
                            contentDescription = null,
                            size = 20.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (inFlight && progress != null) {
                val p = progress!!
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            if (p.total == 0) 0f
                            else p.processed.toFloat() / p.total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.migration_progress_label,
                            p.processed,
                            p.total,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            coordinator.dismiss()
                            onDismiss()
                        }
                    },
                    enabled = !inFlight,
                ) {
                    Text(stringResource(R.string.migration_skip))
                }
                Button(
                    onClick = {
                        inFlight = true
                        scope.launch {
                            coordinator.confirm(selected.value.toList())
                            inFlight = false
                            onDismiss()
                        }
                    },
                    enabled = !inFlight && selected.value.isNotEmpty(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.migration_confirm,
                            selected.value.size,
                        ),
                    )
                }
            }
        }
    }
}
