package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Placeholder rows shown while [TimelineUiState] is Loading or
 * [ChannelUiState] is Resolving. Shape matches PostCard roughly so the
 * transition to the real LazyColumn is visually quiet — same trick
 * Telegram-Android's `messageSkeletons` uses.
 *
 * Intentionally NOT animated: we want users to perceive "loading" without
 * the cost of a shimmer effect that recomposes 60 times per second and
 * fights cold-start RPCs for CPU.
 *
 * Root paints an OPAQUE [background] (matching both host Scaffolds'
 * `containerColor`). This is load-bearing: besides the standalone Loading /
 * Resolving gate, [SkeletonFeed] doubles as the cold-entry COVER painted ON
 * TOP of an already-mounted feed while [rememberBoundaryReveal] performs its
 * bottom-glued → top-aligned reposition (TimelineScreen + ChannelScreen Ready
 * branches). A transparent root let the live, still-misaligned feed bleed
 * through the gaps between placeholder bars — so the "cover" exposed the exact
 * reposition frame it exists to hide, read by users as "skeleton lines on a
 * transparent background" on every scope swap (Archive ↔ All ↔ Folder).
 */
@Stable
@Composable
internal fun SkeletonFeed(rowCount: Int = 6, modifier: Modifier = Modifier) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        repeat(rowCount) { SkeletonRow(placeholderColor) }
    }
}

@Composable
private fun SkeletonRow(color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(12.dp))
            Box(
                modifier = Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}
