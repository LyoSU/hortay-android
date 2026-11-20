package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.data.archive.ArchivedMediaResolver
import dev.lyo.hortay.data.archive.PostSnapshot
import dev.lyo.hortay.data.archive.SnapshotKind
import dev.lyo.hortay.ui.archive.components.RevisionMediaPreview
import dev.lyo.hortay.ui.archive.components.RevisionVersionPicker
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.media.rememberDeferredLoading
import dev.lyo.hortay.ui.text.RichText
import dev.lyo.hortay.ui.theme.ProvideReadingTypography
import dev.lyo.hortay.ui.timeline.ExpandableText
import kotlinx.collections.immutable.ImmutableList

/**
 * Bottom sheet showing the edit history of one message.
 *
 * Top to bottom: title + version count, a [RevisionVersionPicker] (newest selected), a
 * one-line summary of what the selected version changed, then that version's media
 * (compact, sharp when a source exists — see [RevisionMediaPreview]) and its text, rendered
 * through the same [RichText] pipeline as a post body so entities and custom emoji survive.
 * Versions after the first show their changes inline ([composeDiff]); a switch hides the
 * marks to read the clean version.
 *
 * Consecutive snapshots that look identical are folded into one version
 * ([buildRevisionEntries]) — display only, rows stay. When the history ends in a DELETED
 * row the sheet is in deleted mode: deleted title, no "Go to current".
 *
 * @param revisions null while the archive query is still loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostRevisionSheet(
    revisions: ImmutableList<PostSnapshot>?,
    onDismiss: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onGoToCurrent: (() -> Unit)? = null,
    mediaResolver: ArchivedMediaResolver? = null,
) {
    // skipPartiallyExpanded: open at full height so the action row at the bottom is
    // visible without the user having to drag the sheet up. Half-expanded was hiding
    // "Open in Telegram" below the visible viewport on tall revisions.
    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (revisions == null) {
            val showSpinner = rememberDeferredLoading(pending = true, key = Unit, graceMs = SCREEN_MOUNT_GRACE_MS)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 160.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showSpinner) CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }
        val entries = remember(revisions) { buildRevisionEntries(revisions) }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.revision_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
            return@ModalBottomSheet
        }
        RevisionSheetContent(
            entries = entries,
            isDeleted = revisions.any { it.kind == SnapshotKind.DELETED },
            onOpenInTelegram = onOpenInTelegram,
            onGoToCurrent = onGoToCurrent,
            mediaResolver = mediaResolver,
        )
    }
}

@Composable
private fun RevisionSheetContent(
    entries: ImmutableList<RevisionEntry>,
    isDeleted: Boolean,
    onOpenInTelegram: () -> Unit,
    onGoToCurrent: (() -> Unit)?,
    mediaResolver: ArchivedMediaResolver?,
) {
    // Selection by snapshot id, so a version captured while the sheet is open doesn't
    // shift the reader onto a different entry. Unknown / unset → newest.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedIndex = entries.indexOfFirst { it.snapshot.id == selectedId }.takeIf { it >= 0 } ?: entries.lastIndex
    val entry = entries[selectedIndex]
    var showChanges by rememberSaveable { mutableStateOf(true) }
    val versionCount = entries.count { it.change != RevisionChange.Deleted }

    // verticalScroll(): ModalBottomSheet doesn't scroll its content for us — it only
    // constrains the height — so long versions would run off the bottom with no way to
    // reach the action row.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(if (isDeleted) R.string.revision_sheet_title_deleted else R.string.revision_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(R.plurals.revision_versions_count, versionCount, versionCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        RevisionVersionPicker(
            entries = entries,
            selectedIndex = selectedIndex,
            onSelect = { selectedId = entries[it].snapshot.id },
        )
        val diffable = entry.previous != null &&
            (entry.change == RevisionChange.Text || entry.change == RevisionChange.TextAndMedia)
        ChangeSummary(
            change = entry.change,
            showToggle = diffable,
            showChanges = showChanges,
            onShowChangesChange = { showChanges = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        VersionContent(
            entry = entry,
            showDiff = diffable && showChanges,
            postDeleted = isDeleted,
            mediaResolver = mediaResolver,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!isDeleted && onGoToCurrent != null) {
                TextButton(onClick = onGoToCurrent) {
                    Text(stringResource(R.string.revision_go_to_current))
                }
            } else {
                Spacer(Modifier)
            }
            TextButton(onClick = onOpenInTelegram) {
                Text(stringResource(R.string.revision_open_in_telegram))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeSummary(
    change: RevisionChange,
    showToggle: Boolean,
    showChanges: Boolean,
    onShowChangesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = when (change) {
        RevisionChange.Published -> stringResource(R.string.revision_change_published)
        RevisionChange.FirstSeen -> stringResource(R.string.revision_baseline_caveat)
        RevisionChange.Text -> stringResource(R.string.revision_change_text)
        RevisionChange.Formatting -> stringResource(R.string.revision_change_formatting)
        RevisionChange.Media -> stringResource(R.string.revision_change_media)
        RevisionChange.TextAndMedia -> stringResource(R.string.revision_change_text_media)
        RevisionChange.Deleted -> stringResource(R.string.revision_change_deleted)
        RevisionChange.Unreadable -> stringResource(R.string.revision_unreadable)
        RevisionChange.NoVisibleChange -> null
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (showToggle) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(value = showChanges, role = Role.Switch, onValueChange = onShowChangesChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.revision_show_changes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = showChanges, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun VersionContent(
    entry: RevisionEntry,
    showDiff: Boolean,
    postDeleted: Boolean,
    mediaResolver: ArchivedMediaResolver?,
    modifier: Modifier = Modifier,
) {
    // A deletion marker carries no content of its own: show the last readable version,
    // the post as it was when it disappeared.
    val shown = when (entry.change) {
        RevisionChange.Deleted -> entry.previous
        RevisionChange.Unreadable -> null
        else -> entry
    } ?: return
    val previous = shown.previous
    val text: FormattedText = remember(shown, previous, showDiff) {
        if (showDiff && previous != null) composeDiff(previous.text, shown.text) else shown.text
    }
    Column(modifier.fillMaxWidth()) {
        shown.media?.let { media ->
            RevisionMediaPreview(media = media, resolver = mediaResolver, postDeleted = postDeleted)
            if (text.text.isNotBlank()) Spacer(Modifier.height(12.dp))
        }
        if (text.text.isNotBlank()) {
            ProvideReadingTypography {
                RichText(
                    formatted = text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = Int.MAX_VALUE,
                    renderer = { rt, style, lines -> ExpandableText(rt, style, lines) },
                )
            }
        }
    }
}
