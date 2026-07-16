package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-singleton session holder for inline audio / voice-note playback, shared by
 * BOTH consumers app-wide: the regular feed's Audio / VoiceNote posts
 * ([dev.lyo.hortay.ui.timeline.InlineAudioPlayerRow] via `AudioBlock` / `VoiceNoteBlock`)
 * and the rich-message audio / voice blocks. There is one player and one session, so the
 * two surfaces can never talk over each other.
 *
 * Exactly ONE audio source plays at a time across the whole app: starting a new track
 * stops the previous one. That invariant is structural, not policed — the session
 * owns a single [ExoPlayer] (audio-capable, acquired from the shared [ExoPlayerPool])
 * and swaps its media item per track. The player OUTLIVES the row composables that drive
 * it, so playback survives scrolling the source row off-screen; a row re-attaches to the
 * live [state] on remount (keyed on the playback fileId).
 *
 * Threading: every [ExoPlayer] touch happens on the main looper via [scope]
 * ([Dispatchers.Main.immediate]). [MediaCache] observe/ensure is thread-safe and rides
 * the same scope.
 *
 * Session-scoped state hard rule: cleared on TDLib `loggedOut` via [bindLogoutClear] —
 * a fresh sign-in never inherits the previous account's playing track or its player.
 */
@Stable
class AudioPlaybackSession(
    private val pool: ExoPlayerPool,
    private val cache: MediaCache,
) {
    /** Playback phase for the single active track. */
    enum class Phase { Loading, Playing, Paused }

    /**
     * Snapshot of the one active track, or `null` when nothing is loaded. [key] is the
     * playback fileId — a row renders its player state only when `state.key == its fileId`.
     */
    @Immutable
    data class Playback(
        val key: Int,
        val phase: Phase,
        val positionMs: Long,
        val durationMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<Playback?>(null)
    val state: StateFlow<Playback?> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    /**
     * Tap handler for a play/pause button. Same track playing → pause; same track paused →
     * resume; anything else → stop whatever's playing and download-then-play [fileId]. Taps
     * while [Phase.Loading] are ignored (the download is already in flight).
     */
    fun toggle(fileId: Int, durationSec: Int) {
        val current = _state.value
        if (current != null && current.key == fileId) {
            when (current.phase) {
                Phase.Playing -> pause()
                Phase.Paused -> resume()
                Phase.Loading -> Unit
            }
            return
        }
        play(fileId, durationSec)
    }

    private fun play(fileId: Int, durationSec: Int) {
        stopInternal()
        _state.value = Playback(fileId, Phase.Loading, positionMs = 0L, durationMs = durationSec * 1000L)
        loadJob = scope.launch {
            cache.ensure(fileId, DownloadPriority.Foreground)
            // Suspend until the file lands on disk (Ready with a non-empty path).
            val path = cache.observe(fileId)
                .map { (it as? MediaState.Ready)?.path?.takeIf(String::isNotEmpty) }
                .filterNotNull()
                .first()
            // A newer tap may have superseded this track while the download was in flight.
            if (_state.value?.key != fileId) return@launch
            val active = ensurePlayer()
            active.setMediaItem(MediaItem.fromUri("file://$path"))
            active.prepare()
            active.playWhenReady = true
            _state.update { it?.copy(phase = Phase.Playing) }
            startTicker()
        }
    }

    private fun pause() {
        player?.playWhenReady = false
        tickJob?.cancel()
        _state.update { it?.copy(phase = Phase.Paused) }
    }

    private fun resume() {
        player?.let { it.playWhenReady = true }
        _state.update { it?.copy(phase = Phase.Playing) }
        startTicker()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // Audio-capable (not muted) — this is the one place the app streams sound.
        val created = pool.acquire(muted = false)
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onTrackEnded()
            }
        })
        player = created
        return created
    }

    private fun onTrackEnded() {
        tickJob?.cancel()
        // Park at the start so the next tap replays from the beginning (Telegram's behaviour).
        player?.let {
            it.playWhenReady = false
            it.seekTo(0)
        }
        _state.update { it?.copy(phase = Phase.Paused, positionMs = 0L) }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val p = player ?: break
                val pos = p.currentPosition.coerceAtLeast(0L)
                _state.update { it?.copy(positionMs = pos) }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopInternal() {
        loadJob?.cancel()
        tickJob?.cancel()
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    /** Stop playback and release the pooled player. Idempotent. */
    fun clear() {
        stopInternal()
        player?.let { pool.release(it, muted = false) }
        player = null
        _state.value = null
    }

    /** Clear on TDLib logout — session-scoped state hard rule. */
    fun bindLogoutClear(loggedOut: SharedFlow<Unit>, appScope: CoroutineScope) {
        appScope.launch { loggedOut.collect { clear() } }
    }

    private companion object {
        // 200 ms keeps the elapsed-time readout and progress bar visibly live without
        // waking the main thread more often than a user can perceive.
        const val POSITION_TICK_MS = 200L
    }
}
