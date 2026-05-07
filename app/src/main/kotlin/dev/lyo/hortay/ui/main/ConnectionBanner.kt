package dev.lyo.hortay.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape

/**
 * Slim banner that surfaces TDLib's connection state. Hidden when [status] is
 * [ConnectionStatus.Ready] — we don't want to remind the user that everything is fine.
 *
 * Visual upgrade (M3 Expressive):
 *   - Container is a `MaterialShapes.Bun`-shaped chip rather than an edge-to-edge bar.
 *     Bun reads as "soft alert pillow" — softer than a rectangle (less alarming for a
 *     transient connectivity hiccup), more attention-grabbing than a generic rounded
 *     chip (still says "look here").
 *   - Floats with a 16 dp horizontal margin and elevation rather than docking against
 *     the status-bar edge. Same visual idiom as the floating navigation bar — chrome
 *     hovering over content, not framing it.
 *
 * Material 3 colour-mapping:
 *   - WaitingForNetwork → errorContainer (the freeze is the user's network, surface it).
 *   - Connecting / Updating → secondaryContainer (transient, low-stakes).
 *
 * AnimatedVisibility slide + fade matches the New-posts pill below so the two
 * affordances feel like one vocabulary.
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
                stringResource(R.string.connection_waiting),
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            ConnectionStatus.Connecting -> Quad(
                "cloud_off",
                stringResource(R.string.connection_connecting),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            ConnectionStatus.Updating -> Quad(
                "sync",
                stringResource(R.string.connection_updating),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            ConnectionStatus.Ready -> return@AnimatedVisibility
        }
        val shape = HortayExpressive.ConnectionBanner.asComposeShape()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Bun polygon clips the chip silhouette consistently with reaction /
            // folder chips — single expressive vocabulary across all chip-like
            // affordances.
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(container, shape)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
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
}

private data class Quad(
    val symbol: String,
    val label: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)
