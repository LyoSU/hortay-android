package dev.lyo.hortay.ui.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.ArrayDeque

/**
 * Reusable [ExoPlayer] pool. Each `ExoPlayer.Builder().build()` allocates MediaCodec
 * decoders, surface threads, and a per-instance `WakeLockManager` partial wakelock —
 * cheap-looking on paper but expensive enough that fast scrolling through a feed of
 * autoplay videos stalled the main thread (we measured 38 instances created in 30 s
 * with many living 0 ms — pure DSP wake/sleep churn).
 *
 * **Two sub-pools**: muted vs. audio-capable. A muted instance is built with
 * [VideoOnlyRenderersFactory] which skips the audio renderer entirely — that drops
 * the `AudioMix` partial wakelock that AudioTrack would otherwise hold for the
 * lifetime of every player, even at volume=0. The vast majority of our usage is
 * muted (timeline autoplay, WebM stickers, custom emoji); only the fullscreen
 * video viewer needs audio. Pool reuse only happens between callers of the same
 * mute regime — handing an audio-capable instance to a sticker would re-arm
 * AudioMix even though we don't need it.
 *
 * Threading: ExoPlayer must be touched from the application looper (main thread).
 * All compose call sites already are, so no extra locking is needed beyond the
 * `@Synchronized` belt — kept for defensive correctness if a future caller offloads.
 */
class ExoPlayerPool(
    private val context: Context,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {

    private val mutedAvailable = ArrayDeque<ExoPlayer>(maxSize)
    private val audioAvailable = ArrayDeque<ExoPlayer>(maxSize)

    @Synchronized
    fun acquire(muted: Boolean): ExoPlayer {
        val pool = if (muted) mutedAvailable else audioAvailable
        return pool.pollFirst() ?: build(muted)
    }

    /**
     * Reset and pool, or destroy if the corresponding sub-pool is at capacity. Called
     * on Composable dispose.
     *
     * Reset semantics matter: a pooled instance is handed straight to the next caller's
     * `setMediaItem`/`prepare`, so any leftover queue, surface attachment, or playWhenReady
     * flag from the previous user must be cleared. `stop()` flushes the source and parks
     * the player in IDLE; `clearMediaItems()` empties the playlist; the attribute resets
     * give the next caller a known starting state.
     *
     * `setVideoEffects(emptyList())` is defensive: if a future caller ever attaches a
     * `setVideoEffects` chain (e.g. the experimental luma-key shader briefly tried for
     * guest-mode WebM stickers), the chain persists on the ExoPlayer instance and would
     * leak into the next consumer through pool reuse. Clearing on release means a single
     * fresh slate is the contract every acquire sees.
     */
    @Synchronized
    fun release(player: ExoPlayer, muted: Boolean) {
        player.stop()
        player.clearMediaItems()
        clearVideoEffects(player)
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1f
        val pool = if (muted) mutedAvailable else audioAvailable
        if (pool.size < maxSize) {
            pool.offerLast(player)
        } else {
            player.release()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun clearVideoEffects(player: ExoPlayer) {
        player.setVideoEffects(emptyList())
    }

    /** Tear down all pooled instances. Call on Activity/Process destroy. */
    @Synchronized
    fun shutdown() {
        sequenceOf(mutedAvailable, audioAvailable).forEach { queue ->
            while (true) {
                val p = queue.pollFirst() ?: break
                p.release()
            }
        }
    }

    private fun build(muted: Boolean): ExoPlayer {
        val builder = ExoPlayer.Builder(context).apply {
            if (muted) {
                setRenderersFactory(VideoOnlyRenderersFactory(context))
            }
            // WAKE_MODE_NONE: we never want playback to continue while the screen is
            // off. Compose disposal already pauses on ON_PAUSE, but disabling the
            // wake mode entirely prevents ExoPlayer's `WakeLockManager` from acquiring
            // its partial wakelock during the brief window between play() and pause().
            setWakeMode(C.WAKE_MODE_NONE)
        }
        return builder.build()
    }

    private companion object {
        // Sized for a typical timeline: one foreground (fullscreen viewer or autoplay
        // dead-centre), one previous, one next. Beyond that the user is scrolling fast
        // enough that off-viewport players never get to render a frame anyway, so
        // building+tearing-down on overflow is cheaper than holding more idle instances.
        const val DEFAULT_MAX_SIZE = 3
    }
}

/**
 * [DefaultRenderersFactory] that skips audio renderers entirely. Used by [ExoPlayerPool]
 * for muted instances — no AudioTrack means the AudioMix system wakelock is never taken,
 * which was the dominant remaining wakelock cost after we eliminated per-instance churn.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class VideoOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // intentionally empty — audio is silenced at the platform layer, not just at
        // volume=0. Saves AudioTrack allocation, AudioMix wakelock, and the audio
        // decoder MediaCodec.
    }
}
