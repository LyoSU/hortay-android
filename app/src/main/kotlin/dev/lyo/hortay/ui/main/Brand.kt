package dev.lyo.hortay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.ui.theme.DisplayFontFamily

/**
 * Brand wordmark for the feed's top bar — the primary brand surface on the clean
 * canvas. Plus Jakarta Sans ExtraBold ([DisplayFontFamily]) at `headlineMedium`
 * with tightened tracking and tinted `primary` so the brand voice carries the
 * accent colour while the rest of the canvas stays near-white.
 *
 * [trailing] hosts a same-row trailing affordance — currently the connection-state
 * chip (reusing the [ConnectionBanner] vocabulary) when the caller wires one. The
 * slot collapses when null, so callers that don't supply it keep the bare wordmark.
 */
@Composable
fun BrandRow(
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Hortay",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.8).sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        trailing?.invoke()
    }
}
