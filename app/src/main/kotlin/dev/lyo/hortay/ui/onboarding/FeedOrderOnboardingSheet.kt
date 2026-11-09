package dev.lyo.hortay.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayAlpha
import dev.lyo.hortay.ui.theme.HortaySpacing

/**
 * One-time first-run question: which direction should the feed read? The default
 * ([FeedOrder.OldestUnreadFirst], "like a chat") is unusual and used to go unexplained.
 *
 * [current] preselects the stored order. [onConfirm] receives the chosen order; [onDismiss]
 * fires for any dismissal without a choice. The host marks the one-shot flag in both cases
 * so the sheet never appears twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedOrderOnboardingSheet(
    current: FeedOrder,
    onConfirm: (FeedOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<FeedOrder?>(null) }
    val selected = picked ?: current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HortaySpacing.xl)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_feed_order_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(HortaySpacing.lg))
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(HortaySpacing.md),
            ) {
                FeedOrderOptionCard(
                    order = FeedOrder.Newest,
                    selected = selected == FeedOrder.Newest,
                    title = stringResource(R.string.settings_feed_order_newest_title),
                    description = stringResource(R.string.onboarding_feed_order_newest_desc),
                    onSelect = { picked = FeedOrder.Newest },
                    modifier = Modifier.weight(1f),
                )
                FeedOrderOptionCard(
                    order = FeedOrder.OldestUnreadFirst,
                    selected = selected == FeedOrder.OldestUnreadFirst,
                    title = stringResource(R.string.settings_feed_order_oldest_title),
                    description = stringResource(R.string.onboarding_feed_order_oldest_desc),
                    onSelect = { picked = FeedOrder.OldestUnreadFirst },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(HortaySpacing.lg))
            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_feed_order_confirm))
            }
            Spacer(Modifier.height(HortaySpacing.md))
            Text(
                text = stringResource(
                    R.string.onboarding_feed_order_footer,
                    stringResource(R.string.nav_profile),
                    stringResource(R.string.settings_section_feed),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(HortaySpacing.xl))
        }
    }
}

@Composable
private fun FeedOrderOptionCard(
    order: FeedOrder,
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = HortayAlpha.QUIET)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, outline),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(HortaySpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FeedOrderSchematic(newestOnTop = order == FeedOrder.Newest)
            Spacer(Modifier.height(HortaySpacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(HortaySpacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tiny decorative schematic: three stacked lines with the newest one tinted, plus an arrow
 * showing where the newest post sits. Pure Compose shapes, no assets.
 */
@Composable
private fun FeedOrderSchematic(newestOnTop: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HortayAlpha.HAIRLINE)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(HortaySpacing.sm)) {
            // (width, isNewest): the newest post leads when it sits on top, trails otherwise.
            val lines = listOf(44.dp to true, 36.dp to false, 40.dp to false)
            (if (newestOnTop) lines else lines.reversed()).forEach { (width, isNewest) ->
                SchematicLine(width = width, color = if (isNewest) accent else quiet)
            }
        }
        Spacer(Modifier.width(HortaySpacing.sm))
        Symbol(
            name = if (newestOnTop) "arrow_upward" else "arrow_downward",
            contentDescription = null,
            tint = accent,
            size = 24.dp,
        )
    }
}

@Composable
private fun SchematicLine(width: Dp, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(width = width, height = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}
