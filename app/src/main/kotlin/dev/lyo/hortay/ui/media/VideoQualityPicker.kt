package dev.lyo.hortay.ui.media

import dev.lyo.hortay.ui.components.formatByteSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.icons.Symbol

/**
 * Compact quality indicator in the fullscreen video's bottom control row, next to
 * [SpeedChip] and the mute disc. Single label like "720p" on the viewer's chrome capsule.
 * Tap opens [VideoQualityPickerSheet] with the full ladder of available re-encodes (and the
 * original). Quality keeps the sheet rather than [SpeedChip]'s compact segment strip: each
 * option carries a resolution and file-size subtitle, and the ladder can run to five or
 * more steps, which one row of segments can't hold at large font scale.
 *
 * Visible only when [VideoQualities.hasOptions] is true — single-quality videos
 * never see this UI.
 *
 * [holdChrome] is called with true while the sheet is open and false once it closes, so the
 * playback chrome's auto-hide doesn't remove the chip under the open sheet.
 */
@Composable
fun QualityChip(
    current: VideoQuality,
    qualities: VideoQualities,
    onPick: (VideoQuality) -> Unit,
    modifier: Modifier = Modifier,
    holdChrome: (Boolean) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    ViewerPillChip(
        text = current.label,
        onClickLabel = stringResource(R.string.media_video_quality),
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        DisposableEffect(Unit) {
            holdChrome(true)
            onDispose { holdChrome(false) }
        }
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
 * The capsule label shared by the viewer's playback-setting chips ([QualityChip],
 * [SpeedChip]). Same height ([VIEWER_CHROME_DISC], 44 dp) and backdrop as the round chrome
 * discs beside it (mute, save, share), with a stadium outline, so the bottom row reads as
 * one set of controls; a [VIEWER_CHIP_MIN_WIDTH] floor keeps "1×" and "720p" the same
 * width class. minimumInteractiveComponentSize reserves the 48 dp touch target around it,
 * and heightIn lets the capsule grow with the font scale instead of clipping. Clip before
 * clickable so the ripple follows the capsule. The plate uses the viewer-wide
 * [CHROME_BACKDROP_ALPHA]: the chip carries small text, which needs ~4.5:1 over a white
 * frame, not the 3:1 an icon gets away with. [contentDescription], when set, replaces the
 * visible text for screen readers (e.g. "Playback speed 1.5×" over "1.5×").
 */
@Composable
internal fun ViewerPillChip(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = VIEWER_CHROME_DISC)
            .widthIn(min = VIEWER_CHIP_MIN_WIDTH)
            .clip(CircleShape)
            .viewerChromeBackdrop(CircleShape)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            // With a description the visible text is redundant for TalkBack ("1.5×").
            modifier = if (contentDescription != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/** Narrowest playback chip, so a one-glyph "1×" doesn't shrink below the round discs' weight. */
private val VIEWER_CHIP_MIN_WIDTH = 56.dp

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
@Composable
private fun VideoQualityPickerSheet(
    qualities: VideoQualities,
    current: VideoQuality,
    onPick: (VideoQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val units = stringArrayResource(R.array.size_units)
    ViewerPickerSheet(
        title = stringResource(R.string.media_video_quality),
        onDismiss = onDismiss,
    ) {
        qualities.all.forEach { quality ->
            val subtitle = buildString {
                append("${quality.width}×${quality.height}")
                if (quality.sizeBytes > 0) append("  ·  ${formatByteSize(quality.sizeBytes, units)}")
            }
            ViewerPickerRow(
                label = quality.label,
                subtitle = subtitle,
                selected = quality.fileId == current.fileId,
                onClick = { onPick(quality) },
            )
        }
    }
}

/**
 * Modal bottom sheet frame shared by the viewer's pickers: a title over a
 * [selectableGroup] of [ViewerPickerRow]s, so TalkBack announces position and count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            )
            rows()
        }
    }
}

/** One radio row of a [ViewerPickerSheet]; the current pick carries a leading check. */
@Composable
internal fun ViewerPickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Symbol(
                name = "check_circle",
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
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
