package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.media.AudioPlaybackSession
import dev.lyo.hortay.ui.media.AudioSpeedChip
import dev.lyo.hortay.ui.media.LocalAudioPlaybackSession
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalMediaPassive
import dev.lyo.hortay.ui.theme.tabularFigures

/**
 * App-wide inline audio / voice-note player row, shared by the regular feed's `AudioBlock` /
 * `VoiceNoteBlock` and the rich-message audio / voice blocks. A 48 dp tonal [PlayDisc] folds
 * in the download state (idle → download-then-play, in-flight → progress ring, ready →
 * play/pause); audio shows title, a thin [AudioProgressTrack] and "performer · duration", a
 * voice note shows a waveform decoded from TDLib's packed samples and its duration. The
 * elapsed / total readout uses tabular figures so ticking seconds never shift the line.
 *
 * Playback is owned by the single-active-source [AudioPlaybackSession] (via
 * [LocalAudioPlaybackSession]), so it survives scrolling the row off-screen and starting this
 * track stops whatever else was playing — the same session across regular and rich surfaces.
 * While composed, the row registers with the session ([AudioPlaybackSession.attachRow]) so the
 * app-level mini-player stays hidden as long as the track's own controls are on screen. The
 * loaded track also shows a playback-speed chip (1× → 1.5× → 2×), shared with the mini-player.
 *
 * Recomposition budget: the session ticks position every 200 ms. Only derived values that
 * change rarely (active, phase, whole elapsed seconds) are read in composition; the progress
 * fraction is read in the draw phase of the waveform / track, so a tick redraws one layer.
 *
 * [onOpenInSource] is used only by the informational [NonPlayableFileRow] fallback (no
 * resolvable file, or a tombstone without the file on disk). A playable row has no trailing
 * "open in Telegram" button any more: on the device it read as the row's primary action, and
 * the post's ⋯ / long-press menu already carries "Open in Telegram" for every post.
 *
 * @param isVoice selects the voice-note presentation (waveform track, no title line) vs the
 *  audio presentation (title + performer, linear track).
 * @param cover optional album art painted under the play glyph (the feed's `AudioBlock` passes
 *  the track's album cover; voice notes and rich-message audio pass null).
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
    cover: TdMedia? = null,
) {
    val symbol = if (isVoice) "mic" else "audio_file"
    val fallbackSecondary = if (isVoice) {
        formatDuration(durationSec)
    } else {
        listOfNotNull(performer, formatDuration(durationSec)).joinToString(" · ")
    }
    if (fileId == null) {
        NonPlayableFileRow(symbol = symbol, primary = title, secondary = fallbackSecondary, onClick = onOpenInSource)
        return
    }
    // Tombstone (deleted post): playback would `ensure` (download) a file TDLib can no longer
    // fetch. Offer the player only when the file is already on disk — that path is local —
    // and otherwise degrade to the informational row. Observe-only: no ensure/resync here.
    if (LocalMediaPassive.current) {
        val cache = LocalMediaCache.current
        val cached by remember(fileId) { cache.observe(fileId) }.collectAsStateWithLifecycle()
        if (cached !is MediaState.Ready) {
            NonPlayableFileRow(symbol = symbol, primary = title, secondary = fallbackSecondary, onClick = onOpenInSource)
            return
        }
    }

    val session = LocalAudioPlaybackSession.current
    DisposableEffect(session, fileId) {
        session.attachRow(fileId)
        onDispose { session.detachRow(fileId) }
    }
    val playbackState = session.state.collectAsStateWithLifecycle()
    val active by remember(playbackState, fileId) { derivedStateOf { playbackState.value?.key == fileId } }
    val phase by remember(playbackState, fileId) {
        derivedStateOf { playbackState.value?.takeIf { it.key == fileId }?.phase }
    }
    val elapsedSec by remember(playbackState, fileId) {
        derivedStateOf {
            val p = playbackState.value
            if (p != null && p.key == fileId) (p.positionMs / 1000L).toInt() else 0
        }
    }
    val totalMs = durationSec.coerceAtLeast(0) * 1000L
    // Draw-phase reader: never call this from composition.
    val progress = remember(playbackState, fileId, totalMs) {
        {
            val p = playbackState.value
            if (p != null && p.key == fileId && totalMs > 0L) {
                (p.positionMs.toFloat() / totalMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val isActive = remember(playbackState, fileId) { { playbackState.value?.key == fileId } }

    val buttonCd = when (phase) {
        AudioPlaybackSession.Phase.Playing -> stringResource(R.string.rich_audio_pause)
        AudioPlaybackSession.Phase.Loading -> stringResource(R.string.rich_audio_loading)
        else -> stringResource(R.string.rich_audio_play)
    }
    val glyph = when (phase) {
        AudioPlaybackSession.Phase.Playing -> PlayGlyph.Pause
        AudioPlaybackSession.Phase.Loading -> PlayGlyph.Loading
        else -> PlayGlyph.Play
    }
    val track = remember(title, performer, isVoice) {
        AudioPlaybackSession.TrackInfo(title = title, performer = performer, isVoice = isVoice)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContentBlockShape)
            .background(contentBlockColor)
            // 8 dp start: the 48 dp disc is its own touch target, so its visible edge sits
            // where a 40 dp badge would with 12 dp padding — file and audio rows line up.
            .padding(start = 8.dp, end = if (active) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayDisc(
            glyph = glyph,
            cover = cover,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    onClickLabel = buttonCd,
                    role = Role.Button,
                    onClick = { session.toggle(fileId, durationSec, track) },
                )
                .semantics { contentDescription = buttonCd },
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!isVoice) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isVoice) {
                val bars = remember(waveform) { waveform?.let(::decodeWaveform) ?: IntArray(0) }
                AudioWaveform(
                    bars = bars,
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                )
            } else {
                AudioProgressTrack(
                    progress = progress,
                    showThumb = isActive,
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                )
            }
            val time = if (active) {
                "${formatDuration(elapsedSec)} / ${formatDuration(durationSec)}"
            } else {
                formatDuration(durationSec)
            }
            AudioMetaLine(performer = if (isVoice) null else performer, time = time)
        }
        if (active) {
            Spacer(Modifier.width(4.dp))
            AudioSpeedChip()
        }
    }
}

/**
 * "Performer · 1:35" for audio, "0:14" for voice. The performer ellipsizes; the time never
 * truncates and uses tabular figures so the per-second tick doesn't jitter its neighbours.
 */
@Composable
private fun AudioMetaLine(performer: String?, time: String) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!performer.isNullOrBlank()) {
            Text(
                text = performer,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(text = " · ", style = style, color = color, maxLines = 1)
        }
        Text(text = time, style = style.tabularFigures(), color = color, maxLines = 1, softWrap = false)
    }
}
