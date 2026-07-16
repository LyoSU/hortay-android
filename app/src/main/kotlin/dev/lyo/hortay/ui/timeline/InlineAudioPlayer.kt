package dev.lyo.hortay.ui.timeline

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.AudioPlaybackSession
import dev.lyo.hortay.ui.media.LocalAudioPlaybackSession

/**
 * App-wide inline audio / voice-note player row, shared by the regular feed's `AudioBlock` /
 * `VoiceNoteBlock` and the rich-message audio / voice blocks. A round play/pause button folds
 * in the download state (idle → download-then-play, in-flight → progress ring, ready →
 * play/pause); audio shows title + performer with a linear progress bar, a voice note shows a
 * waveform decoded from TDLib's packed samples. Both show an elapsed / total readout.
 *
 * Playback is owned by the single-active-source [AudioPlaybackSession] (via
 * [LocalAudioPlaybackSession]), so it survives scrolling the row off-screen and starting this
 * track stops whatever else was playing — the same session across regular and rich surfaces.
 *
 * [onOpenInSource], when non-null, adds a trailing "open in Telegram" affordance (the regular
 * feed keeps its escape hatch; rich blocks pass null). A [fileId] of null — a rare hydration
 * path with no resolvable file — degrades to the informational [NonPlayableFileRow].
 *
 * @param isVoice selects the voice-note presentation (waveform track, no title line, larger
 *  container shape) vs the audio presentation (title + performer, linear bar).
 */
@Composable
internal fun InlineAudioPlayerRow(
    fileId: Int?,
    durationSec: Int,
    title: String,
    performer: String?,
    waveform: ByteArray?,
    isVoice: Boolean,
    onOpenInSource: (() -> Unit)?,
) {
    val symbol = if (isVoice) "mic" else "audio_file"
    val shape = if (isVoice) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    if (fileId == null) {
        NonPlayableFileRow(
            symbol = symbol,
            primary = title,
            secondary = if (isVoice) formatDuration(durationSec) else performer.orEmpty(),
            onClick = onOpenInSource,
            shape = shape,
        )
        return
    }

    val session = LocalAudioPlaybackSession.current
    val playback by session.state.collectAsStateWithLifecycle()
    val active = playback?.key == fileId
    val phase = if (active) playback?.phase else null
    val totalMs = durationSec.coerceAtLeast(0) * 1000L
    val positionMs = if (active) playback?.positionMs ?: 0L else 0L
    val fraction = if (active && totalMs > 0L) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    val playCd = stringResource(R.string.rich_audio_play)
    val pauseCd = stringResource(R.string.rich_audio_pause)
    val loadingCd = stringResource(R.string.rich_audio_loading)
    val buttonCd = when (phase) {
        AudioPlaybackSession.Phase.Playing -> pauseCd
        AudioPlaybackSession.Phase.Loading -> loadingCd
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
                    onClick = { session.toggle(fileId, durationSec) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                AudioPlaybackSession.Phase.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                AudioPlaybackSession.Phase.Playing -> Symbol(
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
            if (!isVoice) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isVoice) {
                val bars = remember(waveform) { waveform?.let(::decodeWaveform) ?: IntArray(0) }
                AudioWaveform(
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
        if (onOpenInSource != null) {
            val openCd = stringResource(R.string.content_open_in_telegram)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = openCd, role = Role.Button, onClick = onOpenInSource),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "open_in_new",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                )
            }
        }
    }
}
