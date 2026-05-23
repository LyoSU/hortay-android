package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

/**
 * Compact inline "ред." chip rendered after the timestamp on edited posts.
 *
 * SuggestionChip is too tall for a label-medium HeaderRow (~32 dp next to 12 sp
 * text — either it expands the row or the chip gets visually lost). We use a
 * subtle tinted background + label-medium text to match the visual weight of
 * the pencil icon and relative-time pair.
 */
@Composable
fun EditedChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = pluralStringResource(R.plurals.post_edited_chip_count, count, count)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
