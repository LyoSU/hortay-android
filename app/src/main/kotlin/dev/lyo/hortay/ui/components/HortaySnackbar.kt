package dev.lyo.hortay.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's one snackbar: every screen's `snackbarHost` goes through here so messages look
 * and behave the same everywhere.
 *
 * - Sits on the app's own raised surface (the same tonal family as sheets and the floating
 *   nav bar) instead of Material's default inverse — a near-black slab in the light theme —
 *   with the action in the primary colour.
 * - Swipes away sideways in either direction; an action-bearing message stays up for
 *   several seconds and used to be impossible to get rid of sooner.
 */
@Composable
fun HortaySnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        val swipe = rememberSwipeToDismissBoxState()
        LaunchedEffect(swipe.currentValue) {
            if (swipe.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
        }
        SwipeToDismissBox(state = swipe, backgroundContent = {}) {
            Snackbar(
                snackbarData = data,
                shape = SnackbarShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                actionContentColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SnackbarShape = RoundedCornerShape(20.dp)
