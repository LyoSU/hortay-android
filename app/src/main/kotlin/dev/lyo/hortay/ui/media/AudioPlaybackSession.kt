package dev.lyo.hortay.ui.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

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
 * Session-scoped state hard rule: cleared on TDLib `loggedOut` via an `AccountSession`
 * end hook, on Main — a fresh sign-in never inherits the previous account's playing
 * track or its player.
 *
 * Audio citizenship: the pooled audible player carries media [AudioAttributes] with
 * ExoPlayer-managed audio focus and becoming-noisy handling (see [ExoPlayerPool]); the
 * session re-tags each track as speech (voice note) or music (audio track). When ExoPlayer
 * drops `playWhenReady` on its own — another app took focus for good, headphones were
 * unplugged — the listener folds that into [Phase.Paused] so the UI never shows a pause
 * button for a silent track.
 *
 * Mini-player contract: rows that render the session's controls register through
 * [attachRow] / [detachRow] while composed. [showMiniPlayer] is true while a track is
 * loaded but no row showing that track is composed (scrolled away, other tab, other
 * screen) — the app-level mini-player then takes over the controls.
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
        val track: TrackInfo? = null,
    )

    /**
     * What the mini-player shows for the active track. [title] may be blank (rich blocks
     * without a file name); [isVoice] also selects the speech audio content type.
     */
    @Immutable
    data class TrackInfo(
        val title: String,
        val performer: String?,
        val isVoice: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<Playback?>(null)
    val state: StateFlow<Playback?> = _state.asStateFlow()

    // Process-lifetime playback speed: survives track changes and [clear] (a reading
    // preference, not account data).
    private val _speed = MutableStateFlow(PLAYBACK_SPEEDS.first())
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // fileId → number of composed rows rendering that track's controls. Main thread only
    // (composition effects), so a plain map is enough; [mountedKeys] publishes the key set.
    private val rowCounts = HashMap<Int, Int>()
    private val mountedKeys = MutableStateFlow<Set<Int>>(emptySet())

    /** True while a track is loaded and no composed row shows its controls. */
    val showMiniPlayer: StateFlow<Boolean> = combine(_state, mountedKeys) { playback, keys ->
        isMiniPlayerNeeded(playback?.key, keys)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    /**
     * Tap handler for a play/pause button. Same track playing → pause; same track paused →
     * resume; anything else → stop whatever's playing and download-then-play [fileId]. A tap
     * while [Phase.Loading] cancels the load, so a stuck download never locks the button.
     */
    fun toggle(fileId: Int, durationSec: Int, track: TrackInfo? = null) {
        val current = _state.value
        if (current != null && current.key == fileId) {
            when (current.phase) {
                Phase.Playing -> pause()
                Phase.Paused -> resume()
                Phase.Loading -> {
                    stopInternal()
                    _state.value = null
                }
            }
            return
        }
        play(fileId, durationSec, track)
    }

    /** Toggle play / pause of whatever is loaded — the mini-player's button. No-op when idle. */
    fun togglePlayPause() {
        val current = _state.value ?: return
        when (current.phase) {
            Phase.Playing -> pause()
            Phase.Paused -> resume()
            Phase.Loading -> Unit
        }
    }

    /** Step to the next speed in [PLAYBACK_SPEEDS]; applies to the current and later tracks. */
    fun cycleSpeed() {
        val next = nextPlaybackSpeed(_speed.value)
        _speed.value = next
        player?.setPlaybackSpeed(next)
    }

    /** A row showing [fileId]'s controls entered composition. Pair with [detachRow]. */
    fun attachRow(fileId: Int) {
        rowCounts[fileId] = (rowCounts[fileId] ?: 0) + 1
        mountedKeys.value = rowCounts.keys.toSet()
    }

    /** A row registered by [attachRow] left composition. */
    fun detachRow(fileId: Int) {
        val left = (rowCounts[fileId] ?: 0) - 1
        if (left > 0) rowCounts[fileId] = left else rowCounts.remove(fileId)
        mountedKeys.value = rowCounts.keys.toSet()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun play(fileId: Int, durationSec: Int, track: TrackInfo?) {
        stopInternal()
        _state.value = Playback(
            fileId,
            Phase.Loading,
            positionMs = 0L,
            durationMs = durationSec * 1000L,
            track = track,
        )
        loadJob = scope.launch {
            // A track whose last download failed or was cancelled restarts it; anything
            // else (idle, in flight, on disk) just ensures.
            val previous = cache.observe(fileId).value
            if (previous is MediaState.Failed || previous is MediaState.Paused) {
                cache.retry(fileId, DownloadPriority.Foreground)
            } else {
                cache.ensure(fileId, DownloadPriority.Foreground)
            }
            // Suspend until the file lands on disk (Ready with a non-empty path) or the
            // download ends without it (failed / cancelled) — waiting on Ready alone left
            // the track stuck in Loading forever after a failed download.
            val outcome = cache.observe(fileId)
                .first { st ->
                    (st is MediaState.Ready && st.path.isNotEmpty()) ||
                        st is MediaState.Failed || st is MediaState.Paused
                }
            // A newer tap may have superseded this track while the download was in flight.
            if (_state.value?.key != fileId) return@launch
            val path = (outcome as? MediaState.Ready)?.path ?: run {
                _state.value = null
                return@launch
            }
            val active = ensurePlayer()
            // Speech for voice notes, music otherwise; focus stays ExoPlayer-managed.
            active.setAudioAttributes(
                if (track?.isVoice == true) SPEECH_ATTRIBUTES else MUSIC_ATTRIBUTES,
                /* handleAudioFocus = */ true,
            )
            active.setPlaybackSpeed(_speed.value)
            active.setMediaItem(MediaItem.fromUri("file://$path"))
            active.prepare()
            active.playWhenReady = true
            _state.update { it?.copy(phase = Phase.Playing) }
            startTicker()
        }
    }

    /**
     * Silence the session because a video with sound is taking over (fullscreen viewer).
     * A playing track pauses in place (a later tap resumes it); a track still downloading is
     * cancelled so it can't start over the video once its file lands.
     */
    fun yieldToVideo() {
        val current = _state.value ?: return
        when (current.phase) {
            Phase.Playing -> pause()
            Phase.Loading -> {
                stopInternal()
                _state.value = null
            }
            Phase.Paused -> Unit
        }
    }

    private fun pause() {
        player?.playWhenReady = false
        tickJob?.cancel()
        _state.update { it?.copy(phase = Phase.Paused) }
    }

    /**
     * ExoPlayer dropped `playWhenReady` itself (permanent focus loss, headphones unplugged,
     * end of media) — mirror it as Paused so the button offers Play again.
     */
    private fun onPlayerPaused() {
        val current = _state.value ?: return
        if (current.phase != Phase.Playing) return
        tickJob?.cancel()
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs
        _state.update { it?.copy(phase = Phase.Paused, positionMs = pos) }
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
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onTrackEnded()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) onPlayerPaused()
            }
        }
        created.addListener(listener)
        player = created
        playerListener = listener
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

    /**
     * Stop playback and release the pooled player. Idempotent. Used on logout and by the
     * mini-player's close button. The session's listener is detached first — the pooled
     * instance goes on to other audible callers (fullscreen video), whose end / pause
     * events must not reach this session.
     */
    fun clear() {
        stopInternal()
        player?.let { p ->
            playerListener?.let { p.removeListener(it) }
            pool.release(p, muted = false)
        }
        player = null
        playerListener = null
        _state.value = null
    }

    private companion object {
        // 200 ms keeps the elapsed-time readout and progress bar visibly live without
        // waking the main thread more often than a user can perceive.
        const val POSITION_TICK_MS = 200L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val MUSIC_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}

/** Speeds the speed chip cycles through, in order. ExoPlayer keeps pitch natural on change. */
internal val PLAYBACK_SPEEDS: List<Float> = listOf(1f, 1.5f, 2f)

/** The speed after [current] in [PLAYBACK_SPEEDS], wrapping; an unknown value restarts at 1×. */
internal fun nextPlaybackSpeed(current: Float): Float {
    val index = PLAYBACK_SPEEDS.indexOfFirst { abs(it - current) < SPEED_EPSILON }
    return if (index < 0) PLAYBACK_SPEEDS.first() else PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size]
}

/** Locale-aware number part of the speed label: `1`, `1.5` (`1,5` in uk / de), `2`. */
internal fun formatPlaybackSpeed(speed: Float, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }.format(speed.toDouble())

/** Mini-player visibility rule: a track is loaded and none of its rows is composed. */
internal fun isMiniPlayerNeeded(activeKey: Int?, mountedKeys: Set<Int>): Boolean =
    activeKey != null && activeKey !in mountedKeys

private const val SPEED_EPSILON = 0.01f
