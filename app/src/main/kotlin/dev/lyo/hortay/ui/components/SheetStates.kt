package dev.lyo.hortay.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

private val ExpandedSheetValues = setOf(SheetValue.Hidden, SheetValue.Expanded)

/**
 * State for a modal sheet that opens straight to its full height — no half-expanded stop to drag
 * through. The app's default for every sheet whose content is short or scrolls on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberExpandedSheetState(): SheetState =
    rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = ExpandedSheetValues)
