package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.archive.RevisionChange
import dev.lyo.hortay.ui.archive.RevisionEntry
import dev.lyo.hortay.ui.archive.RevisionLabelPrecision
import dev.lyo.hortay.ui.archive.revisionLabelPrecisions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Horizontally scrolling row of selectable version chips, newest selected and in view.
 *
 * Replaced the dot timeline (a row of dots with a time under each). That strip printed
 * bare `HH:mm` labels — several entries read the same "17:35", the labels sat in a fixed
 * height and were cut off at large font sizes, and the first dot slid under the sheet's
 * start edge. Nothing in it said which entry was the publication or the deletion. Chips
 * carry a self-describing label ("Published 17:20", "Deleted 18:03"), get seconds only when
 * two labels would otherwise collide and a date when versions span days
 * ([revisionLabelPrecisions]), and grow with the font instead of clipping.
 */
@Composable
fun RevisionVersionPicker(
    entries: ImmutableList<RevisionEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = rememberVersionTimeLabels(entries)
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        // Open on the newest (last) version: scrolling to the last index pins it to the
        // row's end edge, so the versions before it stay visible to its start.
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    LaunchedEffect(selectedIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == selectedIndex }
        val fullyVisible = item != null &&
            item.offset >= listState.layoutInfo.viewportStartOffset &&
            item.offset + item.size <= listState.layoutInfo.viewportEndOffset
        if (!fullyVisible && selectedIndex in entries.indices) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        modifier = modifier.selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(entries, key = { _, e -> e.snapshot.id }) { index, entry ->
            val time = labels[index]
            val label = when (entry.change) {
                RevisionChange.Published -> stringResource(R.string.revision_chip_published, time)
                RevisionChange.Deleted -> stringResource(R.string.revision_chip_deleted, time)
                else -> time
            }
            val description = when (entry.change) {
                RevisionChange.Published, RevisionChange.Deleted -> label
                else -> stringResource(R.string.revision_chip_edited_cd, time)
            }
            val onClick = remember(index, onSelect) { { onSelect(index) } }
            VersionChip(
                label = label,
                description = description,
                selected = index == selectedIndex,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun VersionChip(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.background(cs.secondaryContainer)
                } else {
                    Modifier.border(1.dp, cs.outlineVariant, shape)
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 32.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            softWrap = false,
        )
    }
}

/**
 * Locale-aware time (and, when needed, date / seconds) for every entry, honouring the
 * device's 12 / 24-hour setting and the app language via the platform's best-pattern
 * lookup rather than a hand-written format.
 */
@Composable
private fun rememberVersionTimeLabels(entries: ImmutableList<RevisionEntry>): ImmutableList<String> {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    return remember(entries, locale, is24h) {
        val zone = TimeZone.getDefault()
        val timestamps = entries.map { it.snapshot.seenAtMs }
        val precisions = revisionLabelPrecisions(timestamps, zone)
        val hour = if (is24h) "H" else "h"
        val formats = HashMap<RevisionLabelPrecision, SimpleDateFormat>()
        timestamps.mapIndexed { i, ts ->
            val p = precisions[i]
            val fmt = formats.getOrPut(p) {
                val skeleton = when (p) {
                    RevisionLabelPrecision.Time -> "${hour}mm"
                    RevisionLabelPrecision.TimeSeconds -> "${hour}mmss"
                    RevisionLabelPrecision.DateTime -> "MMMd${hour}mm"
                    RevisionLabelPrecision.DateTimeSeconds -> "MMMd${hour}mmss"
                    RevisionLabelPrecision.YearDateTime -> "yMMMd${hour}mm"
                    RevisionLabelPrecision.YearDateTimeSeconds -> "yMMMd${hour}mmss"
                }
                SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                    .apply { timeZone = zone }
            }
            fmt.format(Date(ts))
        }.toImmutableList()
    }
}
