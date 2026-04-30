package dev.lyo.hortay.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Compact quality indicator that lives on the full-screen viewer's overlay row,
 * styled to match Telegram: dark translucent pill, single label like "720p" plus
 * a small chevron. Tap opens a [VideoQualityPickerSheet] with the full ladder of
 * available re-encodes (and the original).
 *
 * Visible only when [VideoQualities.hasOptions] is true — single-quality videos
 * never see this UI.
 */
@Composable
fun QualityChip(
    current: VideoQuality,
    qualities: VideoQualities,
    onPick: (VideoQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = current.label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (open) {
        VideoQualityPickerSheet(
            qualities = qualities,
            current = current,
            onPick = {
                onPick(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * Material3 modal bottom sheet listing all available qualities. Highlights the
 * current pick with a leading checkmark. Tapping a row commits the choice and
 * dismisses.
 *
 * Sized rows (>= 48dp) and proper labelLarge typography keep this aligned with
 * MD3 list-item spec — the rest of the viewer's overlay is intentionally
 * Telegram-style (over-photo translucent pills), but a true bottom sheet
 * deserves the platform's standard list semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoQualityPickerSheet(
    qualities: VideoQualities,
    current: VideoQuality,
    onPick: (VideoQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = "Якість відео",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            )
            qualities.all.forEach { quality ->
                QualityRow(
                    quality = quality,
                    selected = quality.fileId == current.fileId,
                    onClick = { onPick(quality) },
                )
            }
        }
    }
}

@Composable
private fun QualityRow(
    quality: VideoQuality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Symbol(
                name = "check_box",
                tint = MaterialTheme.colorScheme.primary,
                size = 24.dp,
                modifier = Modifier.size(24.dp),
            )
        } else {
            // Empty 24dp spacer keeps the label column aligned across selected/unselected rows.
            Spacer(modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = quality.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            val subtitle = buildString {
                append("${quality.width}×${quality.height}")
                if (quality.sizeBytes > 0) append("  ·  ${formatSize(quality.sizeBytes)}")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    return if (size >= 100 || idx == 0 || size == size.toLong().toDouble()) {
        "${size.toLong()} ${units[idx]}"
    } else {
        "%.1f %s".format(size, units[idx])
    }
}
