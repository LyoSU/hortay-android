package dev.lyo.hortay.ui.archive.components

import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import dev.lyo.hortay.R

@Composable
fun EditedChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = pluralStringResource(R.plurals.post_edited_chip_count, count, count)
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = SuggestionChipDefaults.suggestionChipColors(),
    )
}
