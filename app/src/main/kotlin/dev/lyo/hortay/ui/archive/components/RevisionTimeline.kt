package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Horizontal row of dots, one per revision, with timestamp labels.
 * Filled dot = selected revision; outlined = others. Tap selects.
 *
 * Label format adapts to the timestamps: when all revisions land on the same
 * calendar day the label is `HH:mm` (compact, fits long timelines); when at
 * least one revision crosses into a different day the label includes
 * `dd MMM` so the user can tell "edit on Mon 10:30" apart from
 * "another edit on Wed 10:35".
 */
@Composable
fun RevisionTimeline(
    timestamps: ImmutableList<Long>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = remember(timestamps) { pickFormatter(timestamps) }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        timestamps.forEachIndexed { i, ts ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(if (i == selectedIndex) 16.dp else 12.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary
                                .copy(alpha = if (i == selectedIndex) 1f else 0.35f)
                        )
                        .clickable { onSelect(i) }
                )
                Spacer(Modifier.height(4.dp))
                Text(formatter.format(Date(ts)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun pickFormatter(timestamps: List<Long>): SimpleDateFormat {
    if (timestamps.size <= 1) return SimpleDateFormat("HH:mm", Locale.getDefault())
    val cal = Calendar.getInstance()
    val days = HashSet<Long>(timestamps.size)
    for (ts in timestamps) {
        cal.timeInMillis = ts
        days += cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR)
        if (days.size > 1) return SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    }
    return SimpleDateFormat("HH:mm", Locale.getDefault())
}
