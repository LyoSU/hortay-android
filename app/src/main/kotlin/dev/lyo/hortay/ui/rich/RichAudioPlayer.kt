package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.LocalRichAudioController
import dev.lyo.hortay.ui.media.RichAudioController
import dev.lyo.hortay.ui.timeline.NonPlayableFileRow
import dev.lyo.hortay.ui.timeline.formatDuration

/**
 * Inline audio player row for a rich `pageBlockAudio`. Round play/pause button (download →
 * play folded into the button), title/performer, a linear progress bar and an elapsed / total
 * time readout. Playback is owned by the single-active-source [RichAudioController], so it
 * survives scrolling and starting this track stops any other.
 *
 * A block with no resolvable file id falls back to the informational [NonPlayableFileRow]
 * (nothing to download or play).
 */
@Composable
internal fun RichAudioRow(block: RichBlock.Audio) {
    val fileId = block.fileId
    if (fileId == null) {
        NonPlayableFileRow(
            symbol = "audio_file",
            primary = block.title.ifBlank { stringResource(R.string.content_audio_fallback) },
            secondary = block.performer.takeUnless { it.isBlank() }.orEmpty(),
            onClick = null,
        )
        return
    }
    RichAudioPlayerRow(
        fileId = fileId,
        durationSec = block.durationSec,
        symbol = "audio_file",
        title = block.title.ifBlank { stringResource(R.string.content_audio_fallback) },
        performer = block.performer.takeUnless { it.isBlank() },
        waveform = null,
        shape = MaterialTheme.shapes.medium,
    )
}

/** Inline voice-note player row — like [RichAudioRow] but with a waveform progress track. */
@Composable
internal fun RichVoiceNoteRow(block: RichBlock.VoiceNote) {
    val fileId = block.fileId
    if (fileId == null) {
        NonPlayableFileRow(
            symbol = "mic",
            primary = stringResource(R.string.voice_message),
            secondary = formatDuration(block.durationSec),
            onClick = null,
            shape = MaterialTheme.shapes.large,
        )
        return
    }
    RichAudioPlayerRow(
        fileId = fileId,
        durationSec = block.durationSec,
        symbol = "mic",
        title = stringResource(R.string.voice_message),
        performer = null,
        waveform = block.waveform,
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun RichAudioPlayerRow(
    fileId: Int,
    durationSec: Int,
    symbol: String,
    title: String,
    performer: String?,
    waveform: ByteArray?,
    shape: Shape,
) {
    val controller = LocalRichAudioController.current
    val playback by controller.state.collectAsStateWithLifecycle()
    val active = playback?.key == fileId
    val phase = if (active) playback?.phase else null
    val totalMs = durationSec.coerceAtLeast(0) * 1000L
    val positionMs = if (active) playback?.positionMs ?: 0L else 0L
    val fraction = if (active && totalMs > 0L) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    val playCd = stringResource(R.string.rich_audio_play)
    val pauseCd = stringResource(R.string.rich_audio_pause)
    val loadingCd = stringResource(R.string.rich_audio_loading)
    val buttonCd = when (phase) {
        RichAudioController.Phase.Playing -> pauseCd
        RichAudioController.Phase.Loading -> loadingCd
        else -> playCd
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    onClickLabel = buttonCd,
                    role = Role.Button,
                    onClick = { controller.toggle(fileId, durationSec) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                RichAudioController.Phase.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                RichAudioController.Phase.Playing -> Symbol(
                    name = "pause",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 24.dp,
                    filled = true,
                )
                else -> Symbol(
                    name = "play_arrow",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 24.dp,
                    filled = true,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (symbol == "audio_file") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (waveform != null) {
                val bars = remember(waveform) { decodeWaveform(waveform) }
                RichWaveform(
                    bars = bars,
                    fraction = fraction,
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Elapsed / total once the track is loaded; total only at rest.
                    text = if (active) {
                        "${formatDuration((positionMs / 1000L).toInt())} / ${formatDuration(durationSec)}"
                    } else {
                        formatDuration(durationSec)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (performer != null) {
                    Text(
                        text = " · $performer",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
