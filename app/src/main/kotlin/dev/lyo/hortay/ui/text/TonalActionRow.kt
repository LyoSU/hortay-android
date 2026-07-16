package dev.lyo.hortay.ui.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol

/**
 * The app-wide low-profile tonal affordance that invites a deeper or fuller surface — "Read full
 * post ›" under a clamped rich feed body, "View full table ›" under a compact table preview, and
 * "Показати більше ›" to expand a long code block. A full-width `surfaceContainerHigh` row with a
 * primary label and a trailing chevron (the chevron lives in the Compose row, never in the
 * translated string). Shared by both regular posts and rich messages — one action affordance.
 */
@Composable
internal fun TonalActionRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Symbol(
                name = "chevron_right",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
        }
    }
}
