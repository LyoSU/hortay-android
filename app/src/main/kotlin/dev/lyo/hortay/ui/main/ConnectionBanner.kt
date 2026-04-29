package dev.lyo.hortay.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Slim banner that surfaces TDLib's connection state. Hidden when [status] is
 * [ConnectionStatus.Ready] — we don't want to remind the user that everything is fine.
 *
 * Material 3 colour-mapping:
 *   - WaitingForNetwork → errorContainer (the freeze is the user's network, surface it).
 *   - Connecting / Updating → secondaryContainer (transient, low-stakes).
 *
 * Slides in from the top so it never displaces the feed; the AnimatedVisibility's slide
 * + fade combination matches the New-posts pill so the two affordances feel like one
 * vocabulary.
 */
@Composable
fun ConnectionBanner(status: ConnectionStatus, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = status != ConnectionStatus.Ready,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val (symbol, label, container, content) = when (status) {
            ConnectionStatus.WaitingForNetwork -> Quad(
                "wifi_off",
                "Очікує мережі",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            ConnectionStatus.Connecting -> Quad(
                "cloud_off",
                "З'єднання…",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            ConnectionStatus.Updating -> Quad(
                "sync",
                "Оновлення…",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            ConnectionStatus.Ready -> return@AnimatedVisibility
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(container)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                name = symbol,
                tint = content,
                size = 18.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

private data class Quad(
    val symbol: String,
    val label: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)
