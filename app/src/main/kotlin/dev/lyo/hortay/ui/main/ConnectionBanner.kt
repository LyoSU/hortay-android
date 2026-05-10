@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.delay

/**
 * Slim banner that surfaces TDLib's connection state and, with priority, the
 * shared FLOOD_WAIT throttle deadline.
 *
 * Why FLOOD_WAIT gets its own surface. Telegram's per-method rate limit is
 * fully recoverable (TDLib backs off, the next call lands), but the gap looks
 * to the user like a multi-second hang on whatever they last tapped — usually
 * comments, where our prefetch storm + their tap collide. Saying so out loud
 * is cheaper than the apparent-freeze: a labelled pause is qualitatively
 * different from "the app is broken." Pure-throttle banner takes precedence
 * over [ConnectionStatus] because it's the more specific (and more
 * actionable) signal — and because during a long throttle TDLib's connection
 * is still Ready, the existing state would have shown nothing.
 *
 * Visual upgrade (M3 Expressive):
 *   - Container is a stadium chip (`CircleShape` on a wide Row collapses to true
 *     pill) rather than an edge-to-edge bar. Stadium scales gracefully across
 *     locales — Ukrainian "оновлюється…" and German "Verbindungsaufbau…" can
 *     differ in width by 60–80 %, and the canonical Cookie/Burst/Bun polygons
 *     would distort their character ridges into elongated ovals at the long-text
 *     extreme. Reserved for 1:1 elements per Google's M3 Expressive guidance.
 *   - Floats with a 16 dp horizontal margin and elevation rather than docking
 *     against the status-bar edge. Same idiom as the floating navigation bar.
 *
 * Material 3 colour-mapping:
 *   - FLOOD_WAIT → tertiaryContainer (it's a wait, not a failure; M3 tertiary
 *     is the "informational/temporal" slot in Hortay's palette).
 *   - WaitingForNetwork → errorContainer (the freeze is the user's network, surface it).
 *   - Connecting / Updating → secondaryContainer (transient, low-stakes).
 *
 * AnimatedVisibility slide + fade matches the New-posts pill below so the two
 * affordances feel like one vocabulary.
 */
@Composable
fun ConnectionBanner(
    status: ConnectionStatus,
    floodWaitUntilMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    // Re-ticks every second while we're inside the window; falls through to 0
    // (no further recomposition) once the deadline passes. Keyed on the
    // deadline so a fresh FLOOD_WAIT registration restarts the loop with the
    // newer value rather than waiting for the old loop to exit. Compose's
    // produceState gives us cancellation on dispose for free.
    val floodSecondsRemaining by produceState(0L, floodWaitUntilMs) {
        if (floodWaitUntilMs <= 0L) {
            value = 0L
            return@produceState
        }
        while (true) {
            val now = System.currentTimeMillis()
            val remaining = ((floodWaitUntilMs - now + 999L) / 1_000L).coerceAtLeast(0L)
            value = remaining
            if (remaining <= 0L) break
            // 1s tick aligns the displayed countdown to wall-clock seconds —
            // sub-second updates would just churn recomposition without any
            // user-visible change.
            delay(1_000L)
        }
    }
    // Surfacing threshold. A FLOOD_WAIT under 5 s feels like a normal "the app
    // is thinking" pause and doesn't justify a banner that the user might
    // misread as an error. Anything longer is where the labelled-wait
    // experience starts to pay off versus an apparent freeze.
    val showFlood = floodSecondsRemaining >= FLOOD_SURFACE_THRESHOLD_SEC
    val visible = showFlood || status != ConnectionStatus.Ready
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spatial) { -it } + fadeIn(effects),
        exit = slideOutVertically(spatial) { -it } + fadeOut(effects),
        modifier = modifier,
    ) {
        // Build the chip content. Branch on the FLOOD case first; only fall
        // through to the connection-status mapping when the throttle isn't
        // active. The when-on-status branch still includes Ready as a
        // defensive return — AnimatedVisibility's exit animation can run
        // recompositions on the way out where `visible` is false and `status`
        // has flipped to Ready.
        val (symbol, label, container, content) = if (showFlood) {
            Quad(
                "pause",
                pluralStringResource(
                    R.plurals.connection_flood_wait,
                    floodSecondsRemaining.toInt(),
                    floodSecondsRemaining.toInt(),
                ),
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else when (status) {
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
        val shape = CircleShape
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
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

private const val FLOOD_SURFACE_THRESHOLD_SEC = 5L

private data class Quad(
    val symbol: String,
    val label: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)
