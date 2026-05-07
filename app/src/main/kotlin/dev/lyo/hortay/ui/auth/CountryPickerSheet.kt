package dev.lyo.hortay.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.Country
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Telegram-style country picker. Modal bottom sheet with a search field above a single
 * scrollable list — flag · localized name · dial code per row. Search filters by name
 * (case-insensitive) and by trimmed dial code so users can type "380" or "ukr" and find
 * the same row.
 *
 * Note: ModalBottomSheet handles its own dismissal (drag-down, scrim tap, back press).
 * We propagate a confirmed selection through [onPick] only — cancellation goes through
 * [onDismiss] without a value.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    countries: List<Country>,
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val filtered = remember(countries, query) {
        if (query.isBlank()) countries
        else countries.filter { c ->
            c.name.contains(query, ignoreCase = true) ||
                c.dialCode.contains(query) ||
                c.iso.contains(query, ignoreCase = true)
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(query) {
        // After a search edit the list jumps back to top so the first match is visible
        // without manual scrolling — matches the official Telegram behaviour.
        if (query.isNotEmpty() && filtered.isNotEmpty()) listState.scrollToItem(0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.country_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.country_picker_search), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = {
                    Symbol(
                        name = "search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 20.dp,
                    )
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = stringResource(R.string.country_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Pinned rows (Fragment anonymous numbers, custom country) come first
                    // when there's no active search; we draw a thin divider after them so
                    // the alphabetic block reads as a separate group, matching the visual
                    // grammar of official Telegram. During search the filter respects the
                    // user's intent and we don't break the result list with a divider.
                    val pinnedCount = if (query.isBlank()) {
                        filtered.indexOfFirst { !it.isCustom && it.iso != "FT" }
                            .takeIf { it > 0 } ?: 0
                    } else 0
                    itemsIndexed(filtered, key = { _, c -> c.iso }) { index, country ->
                        CountryRow(country = country, onClick = { onPick(country) })
                        if (pinnedCount > 0 && index == pinnedCount - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(country: Country, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The flag glyph alignment differs across vendor emoji fonts — wrapping it in a
        // fixed-width Box keeps the name column aligned across all rows regardless.
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text(text = country.flag, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = country.dialCode,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
